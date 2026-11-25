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
import android.opengl.GLUtils
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
    private var uDenoise = 0; private var uTexel = 0; private var uGrayscale = 0
    /** Shared burned-in OSD renderer (null disables the overlay). */
    @Volatile var overlayRenderer: CameraOverlayRenderer? = null
    private var overlayProgram = 0
    private var overlayAPos = 0
    private var overlayATex = 0
    private val overlayTextures = HashMap<Long, Int>()
    private val overlayGenerations = HashMap<Long, Long>()
    private val overlayQuad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    @Volatile var mirror = false
    /** Spatial noise-reduction strength, 0 (off) to 1 (widest radius). Read on every frame, so
     *  the slider takes effect without restarting the camera or rebuilding the encoder. */
    @Volatile var denoise: Float = 0f
    /** Grayscale Night Mode: drop chrominance after denoising. Read on every frame, so toggling
     *  it needs neither a shader rebuild nor a camera restart. */
    @Volatile var grayscale: Boolean = false
    /** Clockwise rotation (degrees) applied to the camera frame. This is the only rotation source:
     *  the surface transform's sensor orientation is deliberately ignored (quantised to 90° steps). */
    @Volatile var rotation: Int = 0
    /** Rectangle of the displayed (rotated, mirrored) picture to keep; null keeps the whole frame. */
    @Volatile var crop: CropRegion? = null
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
    private val croppedQuad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quadScratch = FloatArray(16)
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
            overlayProgram = buildOverlayProgram()
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
        val cropRegion = crop?.takeUnless { it.isFullFrame() }
        // Use externally-provided frame dimensions (from CameraX Preview use case) to
        // letterbox when the camera output aspect ratio differs from the pipe surface.
        var fw = frameWidth; var fh = frameHeight
        var contentX = 0; var contentY = 0; var contentW = outW; var contentH = outH
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
            contentX = vpX; contentY = vpY; contentW = vpW; contentH = vpH
        }
        // With a crop the selected sub-rectangle is expanded to fill the whole encoder frame,
        // so the encoder surface itself needs no letterbox (its size follows the crop aspect).
        if (cropRegion != null) {
            GLES20.glViewport(0, 0, outW, outH)
        } else {
            GLES20.glViewport(contentX, contentY, contentW, contentH)
        }

        // When rotation is a non-zero multiple of 90°, build a scratch buffer with the
        // quad's positions rotated clockwise about the origin (texture coords untouched).
        val buf: FloatBuffer
        if (rot == 90 || rot == 180 || rot == 270) {
            quad.rewind()
            quad.get(quadScratch)
            quad.position(0)
            val q = rotatedQuad
            q.rewind()
            for (i in 0 until 4) {
                val x = quadScratch[i * 4]
                val y = quadScratch[i * 4 + 1]
                val u = quadScratch[i * 4 + 2]
                val v = quadScratch[i * 4 + 3]
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

        // Crop in displayed (rotated and mirrored) coordinates by scaling the quad positions
        // after rotation. Texture coordinates are untouched, so this is a pure sample/scale.
        val drawBuf: FloatBuffer
        if (cropRegion != null) {
            val glLeft = contentX.toFloat() / outW * 2f - 1f
            val glRight = (contentX + contentW).toFloat() / outW * 2f - 1f
            val glBottom = contentY.toFloat() / outH * 2f - 1f
            val glTop = (contentY + contentH).toFloat() / outH * 2f - 1f
            val cropL = glLeft + (glRight - glLeft) * cropRegion.left
            val cropR = glLeft + (glRight - glLeft) * cropRegion.right
            val cropT = glTop - (glTop - glBottom) * cropRegion.top
            val cropB = glTop - (glTop - glBottom) * cropRegion.bottom
            val halfW = (cropR - cropL) / 2f
            val halfH = (cropT - cropB) / 2f
            buf.rewind()
            buf.get(quadScratch)
            buf.position(0)
            val q = croppedQuad
            q.rewind()
            for (i in 0 until 4) {
                val x = quadScratch[i * 4]
                val y = quadScratch[i * 4 + 1]
                val u = quadScratch[i * 4 + 2]
                val v = quadScratch[i * 4 + 3]
                val nx = if (halfW != 0f) (x - cropL) / halfW - 1f else x
                val ny = if (halfH != 0f) (y - cropB) / halfH - 1f else y
                q.put(nx); q.put(ny); q.put(u); q.put(v)
            }
            q.position(0)
            drawBuf = q
        } else {
            drawBuf = buf
        }

        drawBuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, drawBuf)
        GLES20.glEnableVertexAttribArray(aPos)
        drawBuf.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, drawBuf)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uMirror, if (mirror) 1f else 0f)
        GLES20.glUniform1f(uDenoise, denoise.coerceIn(0f, 1f))
        GLES20.glUniform1f(uGrayscale, if (grayscale) 1f else 0f)
        // One texel in the camera buffer's own resolution; SurfaceTexture may differ from the
        // encoder surface size (CameraX negotiates its own buffer).
        val texW = if (frameWidth > 0) frameWidth else width
        val texH = if (frameHeight > 0) frameHeight else height
        GLES20.glUniform2f(uTexel, 1f / texW, 1f / texH)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        drawOverlay(
            outW, outH,
            if (cropRegion != null) 0 else contentX,
            if (cropRegion != null) 0 else contentY,
            if (cropRegion != null) outW else contentW,
            if (cropRegion != null) outH else contentH,
        )
    }

    /**
     * Draw the burned-in OSD after the camera picture. Each output size (main stream and the RTSP
     * sub stream) gets its own texture so alternating frame sizes do not re-upload every frame.
     * The overlay is positioned against the picture's [contentX]/[contentY]/[contentW]/[contentH]
     * rectangle, not the whole surface, so it never lands in a letterbox bar.
     */
    private fun drawOverlay(
        outW: Int,
        outH: Int,
        contentX: Int,
        contentY: Int,
        contentW: Int,
        contentH: Int,
    ) {
        val program = overlayProgram
        val renderer = overlayRenderer ?: return
        if (program == 0 || contentW < 2 || contentH < 2) return
        val frame = renderer.frame(contentW, contentH) ?: return
        val key = contentW.toLong() * 100_000L + contentH
        val texture = overlayTextures.getOrPut(key) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            ids[0]
        }
        if (overlayGenerations[key] != frame.generation) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame.bitmap, 0)
            overlayGenerations[key] = frame.generation
        }

        val bw = frame.bitmap.width.toFloat()
        val bh = frame.bitmap.height.toFloat()
        val drawX = contentX + frame.x
        val drawY = contentY + frame.y
        val left = drawX.toFloat() / outW * 2f - 1f
        val right = (drawX + bw) / outW * 2f - 1f
        val top = 1f - drawY.toFloat() / outH * 2f
        val bottom = 1f - (drawY + bh) / outH * 2f
        overlayQuad.rewind()
        // Bitmap row 0 is the top of the OSD, so map it to GL texture v=0 (the texture's first row).
        overlayQuad.put(floatArrayOf(
            left, top, 0f, 0f,
            left, bottom, 0f, 1f,
            right, top, 1f, 0f,
            right, bottom, 1f, 1f,
        ))
        overlayQuad.position(0)

        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        // Android Bitmaps are premultiplied, so use the premultiplied-alpha blend equation.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glVertexAttribPointer(overlayAPos, 2, GLES20.GL_FLOAT, false, 16, overlayQuad)
        GLES20.glEnableVertexAttribArray(overlayAPos)
        overlayQuad.position(2)
        GLES20.glVertexAttribPointer(overlayATex, 2, GLES20.GL_FLOAT, false, 16, overlayQuad)
        GLES20.glEnableVertexAttribArray(overlayATex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(0)
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val cfg = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
        val attrib = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
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

    private fun buildOverlayProgram(): Int {
        val vs = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;\n" +
                 "void main(){ gl_Position = aPos; vTex = aTex; }"
        val fs = "precision mediump float; varying vec2 vTex; uniform sampler2D sTex;\n" +
                 "void main(){ gl_FragColor = texture2D(sTex, vTex); }"
        val p = linkProgram(vs, fs)
        if (p == 0) {
            Log.w(TAG, "OSD overlay shader rejected by the driver; overlay disabled")
            return 0
        }
        overlayAPos = GLES20.glGetAttribLocation(p, "aPos")
        overlayATex = GLES20.glGetAttribLocation(p, "aTex")
        return p
    }

    private fun buildProgram(): Int {        val vs = "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uST; varying vec2 vTex;\n" +
                 "void main(){ gl_Position = aPos; vTex = (uST * aTex).xy; }"
        // Optional edge-preserving (bilateral) filter. It only runs when uDenoise > 0, so the
        // default pipeline keeps its single texture fetch. The GL thread updates uDenoise and
        // uTexel per frame, letting the web slider change the radius without a camera restart.
        // The taps are unrolled (some mobile drivers miscompile sampler loops) and split into
        // progressive groups so low levels cost 5 fetches instead of all 25: a 5-tap cross, then
        // the 3x3 diagonals, then the two wider rings. Every group is conditional on the uniform so
        // a level-1 slider setting does not pay level-100 GPU cost on older 1080p devices.
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            uniform float uMirror;
            uniform float uDenoise;
            uniform float uGrayscale;
            uniform vec2 uTexel;

            vec4 tap(vec2 uv, vec3 c, float k) {
                vec3 s = texture2D(sTex, uv).rgb;
                vec3 d = s - c;
                return vec4(s, 1.0) * exp(-dot(d, d) * k);
            }

            vec4 ringTap(vec2 tc, vec3 c, float k, vec2 dir, float radius) {
                return tap(tc + dir * radius * uTexel, c, k);
            }

            void main() {
                vec2 tc = vec2(mix(vTex.x, 1.0 - vTex.x, uMirror), vTex.y);
                vec3 c = texture2D(sTex, tc).rgb;
                vec3 outRgb = c;
                if (uDenoise > 0.001) {
                    // Low-light grain can be much larger than one pixel, so be more tolerant than a
                    // classic edge-preserving filter. Highp avoids mediump quantising the offsets.
                    float sigma = 0.05 + 0.28 * uDenoise;
                    float k = 1.0 / (2.0 * sigma * sigma);
                    vec2 t = uTexel;
                    const float diagonal = 0.70710678;
                    vec4 sum = vec4(c, 1.0);

                    // Levels 1-25: centre plus the four cardinal neighbours (5 texture fetches).
                    sum += tap(tc + vec2(-t.x,  0.0), c, k);
                    sum += tap(tc + vec2( t.x,  0.0), c, k);
                    sum += tap(tc + vec2( 0.0, -t.y), c, k);
                    sum += tap(tc + vec2( 0.0,  t.y), c, k);

                    // Levels 26-50: complete the dense 3x3 with the four diagonals (9 fetches).
                    if (uDenoise > 0.25) {
                        sum += tap(tc + vec2(-t.x, -t.y), c, k);
                        sum += tap(tc + vec2( t.x, -t.y), c, k);
                        sum += tap(tc + vec2(-t.x,  t.y), c, k);
                        sum += tap(tc + vec2( t.x,  t.y), c, k);
                    }

                    // Levels 51-75: first wider ring, smoothly growing from 1.5 to 3 texels.
                    if (uDenoise > 0.50) {
                        float r1 = 1.5 + 1.5 * clamp((uDenoise - 0.50) / 0.25, 0.0, 1.0);
                        sum += ringTap(tc, c, k, vec2(-1.0,  0.0), r1) * 0.75;
                        sum += ringTap(tc, c, k, vec2( 1.0,  0.0), r1) * 0.75;
                        sum += ringTap(tc, c, k, vec2( 0.0, -1.0), r1) * 0.75;
                        sum += ringTap(tc, c, k, vec2( 0.0,  1.0), r1) * 0.75;
                        sum += ringTap(tc, c, k, vec2(-diagonal, -diagonal), r1) * 0.75;
                        sum += ringTap(tc, c, k, vec2( diagonal, -diagonal), r1) * 0.75;
                        sum += ringTap(tc, c, k, vec2(-diagonal,  diagonal), r1) * 0.75;
                        sum += ringTap(tc, c, k, vec2( diagonal,  diagonal), r1) * 0.75;
                    }

                    // Levels 76-100: second ring, smoothly growing from 2.5 to 8 texels (the full
                    // 25 fetches, matching the former behaviour at level 100).
                    if (uDenoise > 0.75) {
                        float r2 = 2.5 + 5.5 * clamp((uDenoise - 0.75) / 0.25, 0.0, 1.0);
                        sum += ringTap(tc, c, k, vec2(-1.0,  0.0), r2) * 0.40;
                        sum += ringTap(tc, c, k, vec2( 1.0,  0.0), r2) * 0.40;
                        sum += ringTap(tc, c, k, vec2( 0.0, -1.0), r2) * 0.40;
                        sum += ringTap(tc, c, k, vec2( 0.0,  1.0), r2) * 0.40;
                        sum += ringTap(tc, c, k, vec2(-diagonal, -diagonal), r2) * 0.40;
                        sum += ringTap(tc, c, k, vec2( diagonal, -diagonal), r2) * 0.40;
                        sum += ringTap(tc, c, k, vec2(-diagonal,  diagonal), r2) * 0.40;
                        sum += ringTap(tc, c, k, vec2( diagonal,  diagonal), r2) * 0.40;
                    }

                    outRgb = sum.rgb / sum.a;
                }
                if (uGrayscale > 0.5) {
                    // Rec. 601 luma keeps brightness and detail while removing the chroma that
                    // shows up as coloured noise in low light. The still paths use the same weights.
                    float luma = dot(outRgb, vec3(0.299, 0.587, 0.114));
                    outRgb = vec3(luma);
                }
                gl_FragColor = vec4(outRgb, 1.0);
            }
        """.trimIndent()
        var p = linkProgram(vs, fs)
        var shaderKind = "highp"
        if (p == 0) {
            // Fragment shaders may lack highp on old GPUs; try mediump before giving up.
            Log.w(TAG, "highp noise-reduction shader rejected; retrying with mediump")
            p = linkProgram(vs, fs.replace("precision highp float;", "precision mediump float;"))
            shaderKind = "mediump"
        }
        if (p == 0) {
            // A driver that cannot compile the kernel must still stream. Fall back to the original
            // passthrough shader; the denoise uniforms are then absent (-1) and ignored.
            Log.w(TAG, "noise-reduction shader rejected by the driver; using passthrough")
            p = linkProgram(vs, PASSTHROUGH_FS)
            shaderKind = "passthrough"
        }
        Log.i(TAG, "noise-reduction shader active: $shaderKind")
        aPos = GLES20.glGetAttribLocation(p, "aPos")
        aTex = GLES20.glGetAttribLocation(p, "aTex")
        uST = GLES20.glGetUniformLocation(p, "uST")
        uMirror = GLES20.glGetUniformLocation(p, "uMirror")
        uDenoise = GLES20.glGetUniformLocation(p, "uDenoise")
        uGrayscale = GLES20.glGetUniformLocation(p, "uGrayscale")
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

        /**
         * From the EGL_ANDROID_recordable extension. The constant is only exposed as an
         * `EGLExt` field from API 26, but the extension and its stable value are available
         * on every Android version this app supports, so declare it locally.
         */
        private const val EGL_RECORDABLE_ANDROID = 0x3142

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
            " uniform float uMirror; uniform float uGrayscale;\n" +
            "void main(){ vec2 tc = vec2(mix(vTex.x, 1.0 - vTex.x, uMirror), vTex.y);" +
            " vec4 c = texture2D(sTex, tc);" +
            " if (uGrayscale > 0.5) c.rgb = vec3(dot(c.rgb, vec3(0.299, 0.587, 0.114)));" +
            " gl_FragColor = c; }"

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
