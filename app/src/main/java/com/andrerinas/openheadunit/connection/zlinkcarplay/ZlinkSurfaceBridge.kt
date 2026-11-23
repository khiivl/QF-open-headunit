package com.andrerinas.openheadunit.connection.zlinkcarplay

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * A surface that outlives the CarPlay screen. The video decoder always draws into [inputSurface]; whichever
 * window surface is attached gets the newest picture copied onto it. When the screen is left, the picture stays
 * here, and when it comes back, it is drawn onto the new window at once. Nothing is decoded again, so a screen
 * that is still sends no frames and still comes back with its picture.
 */
class ZlinkSurfaceBridge(
    width: Int,
    height: Int,
    private val canDraw: () -> Boolean,
    private val onDrawn: () -> Unit
) {
    private val thread = HandlerThread("zlink-gl", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: Surface? = null

    private var texture = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMatrix = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val matrix = FloatArray(16)
    private var hasFrame = false
    @Volatile private var released = false

    lateinit var inputSurface: Surface
        private set

    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init {
        val ready = CountDownLatch(1)
        handler.post {
            try {
                setup(width, height)
            } catch (e: Exception) {
                AppLog.e("zlink bridge: setup failed: ${e.message}")
            }
            ready.countDown()
        }
        ready.await()
    }

    private fun setup(width: Int, height: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        config = cfgs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        idleSurface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = link(
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uM; varying vec2 vTex;" +
                "void main(){ gl_Position = aPos; vTex = (uM * aTex).xy; }",
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 vTex; uniform samplerExternalOES uTexture;" +
                "void main(){ gl_FragColor = texture2D(uTexture, vTex); }"
        )
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMatrix = GLES20.glGetUniformLocation(program, "uM")

        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceTexture.setOnFrameAvailableListener({
            if (released) return@setOnFrameAvailableListener
            try {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(matrix)
                hasFrame = true
                draw()
            } catch (e: Exception) {
                AppLog.w("zlink bridge: frame failed: ${e.message}")
            }
        }, handler)
        inputSurface = Surface(surfaceTexture)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    /** Draw onto this window from now on, or onto none ([surface] null). The newest picture is drawn at once. */
    fun setOutput(surface: Surface?) {
        handler.post {
            if (released) return@post
            if (window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, idleSurface, idleSurface, context)
                EGL14.eglDestroySurface(display, window)
                window = EGL14.EGL_NO_SURFACE
            }
            windowSurface = surface
            if (surface != null && surface.isValid) {
                try {
                    window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                    EGL14.eglMakeCurrent(display, window, window, context)
                    EGL14.eglSwapInterval(display, 0)
                } catch (e: Exception) {
                    AppLog.w("zlink bridge: window failed: ${e.message}")
                    window = EGL14.EGL_NO_SURFACE
                }
            }
            draw()
        }
    }

    /** The picture may be drawn now (the sink was muted before): draw the newest one. */
    fun poke() { handler.post { if (!released) draw() } }

    private fun draw() {
        if (window == EGL14.EGL_NO_SURFACE || !hasFrame || !canDraw()) return
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, h, 0)
        if (w[0] <= 0 || h[0] <= 0) return
        EGL14.eglMakeCurrent(display, window, window, context)
        GLES20.glViewport(0, 0, w[0], h[0])
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (EGL14.eglSwapBuffers(display, window)) onDrawn()
    }

    fun release() {
        released = true
        handler.post {
            try {
                surfaceTexture.setOnFrameAvailableListener(null)
                inputSurface.release()
                surfaceTexture.release()
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, idleSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (_: Exception) {}
            thread.quitSafely()
        }
    }
}
