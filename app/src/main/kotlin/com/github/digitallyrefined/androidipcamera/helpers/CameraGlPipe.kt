package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-copy camera→encoder bridge for the Camera1 backend. Camera1 renders into a SurfaceTexture
 * (which the LEGACY HAL drives fine), a GL thread draws it into the encoder's input Surface — no CPU
 * copy, so the HW encoder sustains 1080p30. Hand [surfaceTexture] to Camera1.setPreviewTexture.
 */
class CameraGlPipe(
    private val encoderSurface: Surface,
    val width: Int,
    val height: Int,
    targetFps: Int,
    /** When true, [setDefaultBufferSize] uses a standard camera-compatible size instead of
     *  [width]×[height]. This prevents aspect-ratio mismatches when CameraX selects a different
     *  output resolution than the pipe surface size. Should be `true` for CameraX, `false` for
     *  Camera1 (where the pipe dimensions already match the camera output). */
    private val standardBuffer: Boolean = false
) {
    private var thread: Thread? = null
    @Volatile private var running = true
    private val ready = CountDownLatch(1)
    private val frameAvailable = AtomicBoolean(false)
    private val frameRateLimiter = FrameRateLimiter(targetFps)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texId = 0
    private var program = 0
    private var aPos = 0; private var aTex = 0; private var uST = 0; private var uMirror = 0
    private var uTonemap = 0; private var uBlack = 0; private var uGain = 0; private var uGamma = 0
    @Volatile var mirror = false
    /**
     * Luma tone curve applied on the GPU to every frame drawn through this pipe, or null for none.
     * This is what makes a long-exposure dark frame legible as outlines rather than a black
     * rectangle — the sensor collected the light, but its absolute level is still far down the
     * luma range, so it has to be developed before encoding. Read per frame from the GL thread, so
     * it can be changed live without rebinding the camera or restarting the encoder.
     */
    @Volatile var lowLightTone: LowLight.LumaTone? = null
    /** Extra clockwise rotation (degrees) applied on top of the camera's own transform. The sensor
     *  rotation is already baked in by the SurfaceTexture transform, so this is the per-camera
     *  rotate= control (quantised to 90° steps). Mirrors what the MJPEG encoder bakes in. */
    @Volatile var rotation: Int = 0
    @Volatile var frameWidth: Int = 0
    @Volatile var frameHeight: Int = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var inputSurface: Surface
        private set

    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f, 0f,0f,  1f,-1f, 1f,0f,  -1f,1f, 0f,1f,  1f,1f, 1f,1f)); position(0)
    }
    private val rotatedQuad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val stMatrix = FloatArray(16)

    fun start() {
        thread = Thread({ run() }, "CameraGlPipe").apply { start() }
        ready.await()
    }

    private fun run() {
        try {
            initEgl()
            texId = createOesTexture()
            program = buildProgram()
            // When standardBuffer is set (CameraX path) use a camera-compatible size so the
            // camera's output aspect ratio matches the pipe surface (avoids stretching when
            // CameraX picks a resolution that doesn't match the desired output size).
            val (bufW, bufH) = if (standardBuffer) standardBufferSize() else (width to height)
            surfaceTexture = SurfaceTexture(texId).also { it.setDefaultBufferSize(bufW, bufH) }
            surfaceTexture.setOnFrameAvailableListener { frameAvailable.set(true) }
            inputSurface = Surface(surfaceTexture)
            ready.countDown()
            while (running) {
                if (frameAvailable.compareAndSet(true, false)) {
                    surfaceTexture.updateTexImage()
                    val timestamp = surfaceTexture.timestamp.takeIf { it > 0L } ?: System.nanoTime()
                    if (frameRateLimiter.shouldEmit(timestamp)) {
                        surfaceTexture.getTransformMatrix(stMatrix)
                        drawFrame()
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                } else {
                    Thread.sleep(2)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GL loop: " + e.message)
            if (ready.count > 0) ready.countDown()
        } finally {
            releaseEgl()
        }
    }

    private fun drawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val rot = ((rotation % 360) + 360) % 360
        // Use externally-provided frame dimensions (from CameraX Preview use case) to
        // letterbox when the camera output aspect ratio differs from the pipe surface.
        var fw = frameWidth; var fh = frameHeight
        if (fw > 0 && fh > 0) {
            // The stMatrix may rotate the camera frame (e.g. 90° when the device is held in
            // portrait). When it does, the effective display dimensions are swapped, so we
            // must swap the frame width/height to compute the correct viewport adjustment.
            if (Math.abs(stMatrix[0]) < 0.5f && Math.abs(stMatrix[4]) > 0.5f) {
                val t = fw; fw = fh; fh = t
            }
            // The quad is rotated [rot]° below, so the drawn content dimensions are swapped
            // for right-angle rotations — letterbox against the rotated shape.
            if (rot == 90 || rot == 270) {
                val t = fw; fw = fh; fh = t
            }
            val outAspect = width.toFloat() / height.toFloat()
            val frameAspect = fw.toFloat() / fh.toFloat()
            var vpW = width; var vpH = height; var vpX = 0; var vpY = 0
            if (outAspect > frameAspect) {
                vpW = (height * frameAspect).toInt()
                vpX = (width - vpW) / 2
            } else {
                vpH = (width / frameAspect).toInt()
                vpY = (height - vpH) / 2
            }
            if (vpW < 1) vpW = 1; if (vpH < 1) vpH = 1
            GLES20.glViewport(vpX, vpY, vpW, vpH)
        }

        // When rotation is a non-zero multiple of 90°, build a scratch buffer with the
        // quad's positions rotated clockwise about the origin (texture coords untouched).
        val buf: FloatBuffer
        if (rot == 90 || rot == 180 || rot == 270) {
            val src = FloatArray(16)
            quad.rewind()
            quad.get(src)
            quad.position(0)
            val q = rotatedQuad
            q.rewind()
            for (i in 0 until 4) {
                val x = src[i * 4]
                val y = src[i * 4 + 1]
                val u = src[i * 4 + 2]
                val v = src[i * 4 + 3]
                val (nx, ny) = when (rot) {
                    90 -> y to -x
                    180 -> -x to -y
                    270 -> -y to x
                    else -> x to y
                }
                q.put(nx); q.put(ny); q.put(u); q.put(v)
            }
            q.position(0)
            buf = q
        } else {
            quad.position(0)
            buf = quad
        }

        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uMirror, if (mirror) 1f else 0f)
        // Low-light tone curve: null / identity means leave the frame completely untouched.
        val tone = lowLightTone?.takeIf { !it.isIdentity }
        GLES20.glUniform1f(uTonemap, if (tone != null) 1f else 0f)
        if (tone != null) {
            GLES20.glUniform1f(uBlack, tone.blackLevel.toFloat())
            GLES20.glUniform1f(uGain, tone.gain)
            GLES20.glUniform1f(uGamma, tone.gamma.coerceIn(0.05f, 20f))
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val cfg = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        val attrib = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        EGL14.eglChooseConfig(eglDisplay, attrib, 0, cfg, 0, 1, num, 0)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg[0], encoderSurface,
            intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun createOesTexture(): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun buildProgram(): Int {
        val vs = "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uST; varying vec2 vTex;\n" +
                 "void main(){ gl_Position = aPos; vTex = (uST * aTex).xy; }"
        // Low-light development, mirroring LowLight.LumaTone.toLut() step for step (that function is
        // the reference definition; the two must be changed together):
        //
        //   1. luma to the 0..255 scale the curve is defined on,
        //   2. subtract uBlack, scale by uGain, normalise to 0..1,
        //   3. raise to uGamma — which is < 1 and therefore lifts,
        //   4. fold back as a per-pixel scale, so colour and white balance survive (a per-channel
        //      curve on a noisy dark frame shifts hue frame to frame).
        //
        // Two mistakes are easy to make here and both are silent: applying the curve to the 0..1
        // colour without lifting it to 0..255 drives (luma - uBlack) negative and blacks the frame
        // out, and inverting the exponent blows the highlights out to solid white.
        //
        // highp is requested because pow() in half precision visibly bands the shadow gradients
        // this pass exists to reveal, but it is only *optional* in a GLES2 fragment shader, so the
        // mediump fallback is kept. A precision failure would fail shader compilation and take the
        // whole H.264 stream down, which is far worse than slightly banded shadows.
        val fs = "#extension GL_OES_EGL_image_external : require\n" +
                 "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                 "precision highp float;\n" +
                 "#else\n" +
                 "precision mediump float;\n" +
                 "#endif\n" +
                 "varying vec2 vTex; uniform samplerExternalOES sTex;\n" +
                 "uniform float uMirror; uniform float uTonemap; uniform float uBlack;\n" +
                 "uniform float uGain; uniform float uGamma;\n" +
                 "void main(){\n" +
                 "  vec2 tc = vec2(mix(vTex.x, 1.0 - vTex.x, uMirror), vTex.y);\n" +
                 "  vec3 c = texture2D(sTex, tc).rgb;\n" +
                 "  if (uTonemap > 0.5) {\n" +
                 "    float l = dot(c, vec3(0.2126, 0.7152, 0.0722)) * 255.0;\n" +
                 "    float x = clamp((l - uBlack) * uGain / 255.0, 0.0, 1.0);\n" +
                 "    float v = pow(x, uGamma) * 255.0;\n" +
                 "    c = c * (v / max(l, 1.0e-3));\n" +
                 "  }\n" +
                 "  gl_FragColor = vec4(c, 1.0);\n" +
                 "}"
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        aPos = GLES20.glGetAttribLocation(p, "aPos")
        aTex = GLES20.glGetAttribLocation(p, "aTex")
        uST = GLES20.glGetUniformLocation(p, "uST")
        uMirror = GLES20.glGetUniformLocation(p, "uMirror")
        uTonemap = GLES20.glGetUniformLocation(p, "uTonemap")
        uBlack = GLES20.glGetUniformLocation(p, "uBlack")
        uGain = GLES20.glGetUniformLocation(p, "uGain")
        uGamma = GLES20.glGetUniformLocation(p, "uGamma")
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun releaseEgl() {
        try { if (::inputSurface.isInitialized) inputSurface.release() } catch (_: Exception) {}
        try { if (::surfaceTexture.isInitialized) surfaceTexture.release() } catch (_: Exception) {}
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        frameRateLimiter.reset()
    }

    /** Returns a standard camera-compatible buffer size matching the output aspect ratio. */
    private fun standardBufferSize(): Pair<Int, Int> {
        val aspect = width.toFloat() / height.toFloat()
        return if (aspect >= 1.5f) { // 16:9-ish
            // 1280x720 is widely supported by modern cameras; if the requested size is smaller
            // than that, use 640x360 (same 16:9 ratio, less likely to be rejected by the HAL).
            if (width >= 1280) 1280 to 720 else 640 to 360
        } else { // 4:3-ish
            if (width >= 640) 640 to 480 else 320 to 240
        }
    }

    companion object { private const val TAG = "CameraGlPipe" }
}
