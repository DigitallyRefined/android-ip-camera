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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-copy camera→encoder bridge for the Camera1 backend. Camera1 renders into a SurfaceTexture
 * (which the LEGACY HAL drives fine), a GL thread draws it into the encoder's input Surface — no CPU
 * copy, so the HW encoder sustains 1080p30. Hand [surfaceTexture] to Camera1.setPreviewTexture.
 */
class CameraGlPipe(private val encoderSurface: Surface, private val width: Int, private val height: Int) {
    private var thread: Thread? = null
    @Volatile private var running = true
    private val ready = CountDownLatch(1)
    private val frameAvailable = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texId = 0
    private var program = 0
    private var aPos = 0; private var aTex = 0; private var uST = 0
    lateinit var surfaceTexture: SurfaceTexture
        private set
    lateinit var inputSurface: Surface
        private set

    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f, 0f,0f,  1f,-1f, 1f,0f,  -1f,1f, 0f,1f,  1f,1f, 1f,1f)); position(0)
    }
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
            surfaceTexture = SurfaceTexture(texId).also { it.setDefaultBufferSize(width, height) }
            surfaceTexture.setOnFrameAvailableListener { frameAvailable.set(true) }
            inputSurface = Surface(surfaceTexture)
            ready.countDown()
            while (running) {
                if (frameAvailable.compareAndSet(true, false)) {
                    surfaceTexture.updateTexImage()
                    surfaceTexture.getTransformMatrix(stMatrix)
                    drawFrame()
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, surfaceTexture.timestamp)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniformMatrix4fv(uST, 1, false, stMatrix, 0)
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
        val fs = "#extension GL_OES_EGL_image_external : require\n" +
                 "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;\n" +
                 "void main(){ gl_FragColor = texture2D(sTex, vTex); }"
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        aPos = GLES20.glGetAttribLocation(p, "aPos")
        aTex = GLES20.glGetAttribLocation(p, "aTex")
        uST = GLES20.glGetUniformLocation(p, "uST")
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
    }

    companion object { private const val TAG = "CameraGlPipe" }
}
