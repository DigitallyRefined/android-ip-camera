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
import android.os.SystemClock
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
    private var eglConfig: EGLConfig? = null

    /** Optional second encoder the same frames are drawn into (the RTSP sub stream). */
    private class SecondaryOutput(val surface: Surface, val width: Int, val height: Int, fps: Int) {
        val limiter = FrameRateLimiter(fps)
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    }
    private class SecondaryRequest(val output: SecondaryOutput?) {
        val done = CountDownLatch(1)
        @Volatile var attached = false
    }
    private var secondary: SecondaryOutput? = null
    private val secondaryRequest = java.util.concurrent.atomic.AtomicReference<SecondaryRequest?>()
    private var texId = 0
    private var program = 0
    private var aPos = 0; private var aTex = 0; private var uST = 0; private var uMirror = 0
    private var uDenoise = 0; private var uTexel = 0
    @Volatile var mirror = false
    /** Spatial noise-reduction strength, 0 (off) to 1 (widest radius). Read on every frame, so
     *  the slider takes effect without restarting the camera or rebuilding the encoder. */
    @Volatile var denoise: Float = 0f
    /** Clockwise rotation (degrees) applied to the camera frame. This is the only rotation source:
     *  the surface transform's sensor orientation is deliberately ignored (quantised to 90° steps). */
    @Volatile var rotation: Int = 0
    @Volatile var frameWidth: Int = 0
    @Volatile var frameHeight: Int = 0
    /** Elapsed realtime of the last camera frame, see CameraXCapture.lastFrameAtMs. */
    @Volatile var lastFrameAtMs = 0L
        private set
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
            // SurfaceTexture's transform contains the camera's sensor orientation. Ignore it and
            // keep only the standard GL vertical flip, so the picture is never auto-rotated by the
            // sensor — only [rotation] turns it.
            System.arraycopy(GL_FLIP_ONLY, 0, stMatrix, 0, GL_FLIP_ONLY.size)
            // When standardBuffer is set (CameraX path) use a camera-compatible size so the
            // camera's output aspect ratio matches the pipe surface (avoids stretching when
            // CameraX picks a resolution that doesn't match the desired output size).
            val (bufW, bufH) = if (standardBuffer) standardBufferSize() else (width to height)
            surfaceTexture = SurfaceTexture(texId).also { it.setDefaultBufferSize(bufW, bufH) }
            surfaceTexture.setOnFrameAvailableListener { frameAvailable.set(true) }
            inputSurface = Surface(surfaceTexture)
            ready.countDown()
            while (running) {
                applySecondaryRequest()
                if (frameAvailable.compareAndSet(true, false)) {
                    surfaceTexture.updateTexImage()
                    lastFrameAtMs = SystemClock.elapsedRealtime()
                    val timestamp = surfaceTexture.timestamp.takeIf { it > 0L } ?: System.nanoTime()
                    val sec = secondary
                    val mainDue = frameRateLimiter.shouldEmit(timestamp)
                    val secondaryDue = sec != null && sec.limiter.shouldEmit(timestamp)
                    if (mainDue) {
                        drawFrame(width, height)
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                    if (sec != null && secondaryDue) {
                        EGL14.eglMakeCurrent(eglDisplay, sec.eglSurface, sec.eglSurface, eglContext)
                        drawFrame(sec.width, sec.height)
                        EGLExt.eglPresentationTimeANDROID(eglDisplay, sec.eglSurface, timestamp)
                        EGL14.eglSwapBuffers(eglDisplay, sec.eglSurface)
                        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    }
                } else {
                    Thread.sleep(2)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GL loop: " + e.message)
            if (ready.count > 0) ready.countDown()
        } finally {
            releaseSecondary()
            secondaryRequest.getAndSet(null)?.done?.countDown()
            releaseEgl()
        }
    }

    /**
     * Starts (or with null, stops) drawing every frame into a second encoder [surface] of
     * [w]×[h] at up to [fps]. Blocks until the GL thread has switched, so after a stop the caller
     * may release the surface. Returns whether a [surface] is now being drawn into; no-op (false)
     * once the pipe has stopped.
     */
    fun setSecondaryOutput(surface: Surface?, w: Int = 0, h: Int = 0, fps: Int = 0): Boolean {
        if (!running) return false
        val request = SecondaryRequest(surface?.let { SecondaryOutput(it, w, h, fps) })
        secondaryRequest.getAndSet(request)?.done?.countDown()
        if (!request.done.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "GL thread did not apply the secondary output change in time")
        }
        return request.attached
    }

    private fun applySecondaryRequest() {
        val request = secondaryRequest.getAndSet(null) ?: return
        try {
            releaseSecondary()
            val output = request.output ?: return
            output.eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, output.surface,
                intArrayOf(EGL14.EGL_NONE), 0)
            if (output.eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Could not create the secondary EGL surface")
            } else {
                secondary = output
                request.attached = true
            }
        } finally {
            request.done.countDown()
        }
    }

    private fun releaseSecondary() {
        val sec = secondary ?: return
        secondary = null
        if (sec.eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            EGL14.eglDestroySurface(eglDisplay, sec.eglSurface)
        }
    }

    private fun drawFrame(outW: Int, outH: Int) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glViewport(0, 0, outW, outH)

        val rot = ((rotation % 360) + 360) % 360
        // Use externally-provided frame dimensions (from CameraX Preview use case) to
        // letterbox when the camera output aspect ratio differs from the pipe surface.
        var fw = frameWidth; var fh = frameHeight
        if (fw > 0 && fh > 0) {
            // The quad is rotated [rot]° below, so the drawn content dimensions are swapped
            // for right-angle rotations — letterbox against the rotated shape.
            if (rot == 90 || rot == 270) {
                val t = fw; fw = fh; fh = t
            }
            val outAspect = outW.toFloat() / outH.toFloat()
            val frameAspect = fw.toFloat() / fh.toFloat()
            var vpW = outW; var vpH = outH; var vpX = 0; var vpY = 0
            if (outAspect > frameAspect) {
                vpW = (outH * frameAspect).toInt()
                vpX = (outW - vpW) / 2
            } else {
                vpH = (outW / frameAspect).toInt()
                vpY = (outH - vpH) / 2
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
        GLES20.glUniform1f(uDenoise, denoise.coerceIn(0f, 1f))
        // One texel in the camera buffer's own resolution; SurfaceTexture may differ from the
        // encoder surface size (CameraX negotiates its own buffer).
        val texW = if (frameWidth > 0) frameWidth else width
        val texH = if (frameHeight > 0) frameHeight else height
        GLES20.glUniform2f(uTexel, 1f / texW, 1f / texH)
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
        eglConfig = cfg[0]
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
        // Optional edge-preserving (bilateral) filter. It only runs when uDenoise > 0, so the
        // default pipeline keeps its single texture fetch. The GL thread updates uDenoise and
        // uTexel per frame, letting the web slider change the radius without a camera restart.
        // A fixed 5x5 grid is sampled with a radius that grows with the slider, so higher levels
        // reach noise that spans more than one pixel (the old 3x3 kernel only touched fine grain).
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            uniform float uMirror;
            uniform float uDenoise;
            uniform vec2 uTexel;

            vec4 tap(vec2 uv, vec3 c, float k) {
                vec3 s = texture2D(sTex, uv).rgb;
                vec3 d = s - c;
                return vec4(s, 1.0) * exp(-dot(d, d) * k);
            }

            void main() {
                vec2 tc = vec2(mix(vTex.x, 1.0 - vTex.x, uMirror), vTex.y);
                vec3 c = texture2D(sTex, tc).rgb;
                if (uDenoise <= 0.001) {
                    gl_FragColor = vec4(c, 1.0);
                    return;
                }

                // A wider colour tolerance smooths more of the sensor grain at higher levels.
                float sigma = 0.03 + 0.12 * uDenoise;
                float k = 1.0 / (2.0 * sigma * sigma);
                // The slider scales the spacing of the 5x5 taps from half a texel to five texels.
                float radius = 0.5 + 4.5 * uDenoise;
                vec2 spacing = uTexel * radius;
                vec4 sum = vec4(0.0);
                for (int y = 0; y < 5; y++) {
                    for (int x = 0; x < 5; x++) {
                        sum += tap(tc + vec2(float(x - 2), float(y - 2)) * spacing, c, k);
                    }
                }

                gl_FragColor = vec4(sum.rgb / sum.a, 1.0);
            }
        """.trimIndent()
        var p = linkProgram(vs, fs)
        if (p == 0) {
            // A driver that cannot compile the 25-tap kernel must still stream. Fall back to the
            // original passthrough shader; the denoise uniforms are then absent (-1) and ignored.
            Log.w(TAG, "noise-reduction shader rejected by the driver; using passthrough")
            p = linkProgram(vs, PASSTHROUGH_FS)
        }
        aPos = GLES20.glGetAttribLocation(p, "aPos")
        aTex = GLES20.glGetAttribLocation(p, "aTex")
        uST = GLES20.glGetUniformLocation(p, "uST")
        uMirror = GLES20.glGetUniformLocation(p, "uMirror")
        uDenoise = GLES20.glGetUniformLocation(p, "uDenoise")
        uTexel = GLES20.glGetUniformLocation(p, "uTexel")
        return p
    }

    private fun linkProgram(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "program: " + GLES20.glGetProgramInfoLog(p))
            GLES20.glDeleteProgram(p)
            return 0
        }
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

    /**
     * Sizes the camera's buffers to the resolution CameraX negotiated for this surface, so the
     * camera renders at the selected stream size rather than the placeholder from
     * [standardBufferSize]. Call before handing [inputSurface] to CameraX.
     */
    fun setCameraBufferSize(w: Int, h: Int) {
        surfaceTexture.setDefaultBufferSize(w, h)
        frameWidth = w
        frameHeight = h
    }

    /** Returns a standard camera-compatible buffer size matching the output aspect ratio. */
    private fun standardBufferSize(): Pair<Int, Int> {
        // Camera buffers are landscape even when the encoded frame is portrait.
        val long = maxOf(width, height)
        val aspect = long.toFloat() / minOf(width, height).toFloat()
        return if (aspect >= 1.5f) { // 16:9-ish
            // 1280x720 is widely supported by modern cameras; if the requested size is smaller
            // than that, use 640x360 (same 16:9 ratio, less likely to be rejected by the HAL).
            if (long >= 1280) 1280 to 720 else 640 to 360
        } else { // 4:3-ish
            if (long >= 640) 640 to 480 else 320 to 240
        }
    }

    companion object {
        private const val TAG = "CameraGlPipe"

        /** Standard GL vertical flip; SurfaceTexture always applies this regardless of camera. */
        private val GL_FLIP_ONLY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )

        /** Minimal shader for drivers that reject the optional denoise kernel. */
        private const val PASSTHROUGH_FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;" +
            " uniform float uMirror;\n" +
            "void main(){ vec2 tc = vec2(mix(vTex.x, 1.0 - vTex.x, uMirror), vTex.y);" +
            " gl_FragColor = texture2D(sTex, tc); }"

        /**
         * Shape of the picture this pipe draws for a camera output of [cameraWidth]×[cameraHeight].
         * Only the manual [rotation] turns the frame, so an odd number of quarter turns swaps the
         * sides. Sizing the encoder to this keeps a portrait picture from being pillarboxed into a
         * landscape frame.
         */
        fun frameSize(cameraWidth: Int, cameraHeight: Int, rotation: Int): Pair<Int, Int> {
            val norm = ((rotation % 360) + 360) % 360
            val quarterTurns = (norm + 45) / 90
            return if (quarterTurns % 2 == 1) cameraHeight to cameraWidth else cameraWidth to cameraHeight
        }

        /**
         * A [width]×[height] frame scaled down so its short side is [shortSide], keeping the
         * shape and even dimensions (encoders need them). Never scales up.
         */
        fun scaledFrame(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
            val short = minOf(width, height)
            if (shortSide >= short || shortSide <= 0) return width to height
            val scale = shortSide.toDouble() / short
            fun even(v: Double) = (Math.round(v / 2) * 2).toInt().coerceAtLeast(2)
            return even(width * scale) to even(height * scale)
        }
    }
}
