package eu.studio742.imago.core.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.TextureView
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The editor's GL pipeline drawing into a [TextureView].
 *
 * It replaced a `GLSurfaceView`, whose surface lives behind the app's window and only shows where the
 * window leaves it a hole. Born into a screen already in place — the first time a photo opened in the
 * editor, every photo swiped to inside it — that hole was not made: the log showed every frame drawing
 * the photo at the screen's size, and the stage stayed black until a tap. A TextureView is drawn inside
 * the window like any other view, so there is no hole to make.
 *
 * What a `GLSurfaceView` did for free is here by hand: one thread of its own for the GL context, the
 * window surface made from the view's texture when it becomes available and dropped when it goes, and
 * frames drawn only when asked for. The context outlives the texture — leaving the app and coming back
 * keeps the uploaded photo — and goes with [release].
 */
internal class GlTextureStage(private val renderer: PhotoRenderer) : TextureView.SurfaceTextureListener {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "imago-editor-gl") }
    private val framePending = AtomicBoolean(false)
    @Volatile private var released = false

    // Touched on [thread] only.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** A frame with the renderer's current inputs; asks made while one is waiting become that one. */
    fun requestRender() {
        if (released || !framePending.compareAndSet(false, true)) return
        onThread {
            framePending.set(false)
            drawFrame()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        onThread {
            if (context == EGL14.EGL_NO_CONTEXT) setUpContext()
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "No window surface" }
            makeCurrent()
            if (!contextHasObjects) {
                renderer.onSurfaceCreated(null, null)
                contextHasObjects = true
            }
            renderer.onSurfaceChanged(null, width, height)
        }
        requestRender()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        onThread {
            if (surface == EGL14.EGL_NO_SURFACE) return@onThread
            makeCurrent()
            renderer.onSurfaceChanged(null, width, height)
        }
        requestRender()
    }

    /** The window surface goes before the texture does: drawing into a released texture is an error. */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        if (released) return true
        try {
            thread.submit(Callable { dropWindowSurface() }).get()
        } catch (_: RejectedExecutionException) {
            // Released meanwhile: the teardown already dropped it.
        }
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /** Ends the context and the thread; the view is going away. */
    fun release() {
        if (released) return
        released = true
        onThread {
            dropWindowSurface()
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            context = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
        }
        thread.shutdown()
    }

    private var contextHasObjects = false

    private fun drawFrame() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        makeCurrent()
        renderer.onDrawFrame(null)
        EGL14.eglSwapBuffers(display, surface)
    }

    private fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "The context did not become current" }
    }

    private fun dropWindowSurface() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        surface = EGL14.EGL_NO_SURFACE
    }

    /** OpenGL ES 3.1 where the device gives it, as the `GLSurfaceView` asked for; 3.0 is what the shaders need. */
    private fun setUpContext() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL did not initialise" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No OpenGL ES 3 window configuration"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 1, EGL14.EGL_NONE), 0,
        ).takeIf { it != EGL14.EGL_NO_CONTEXT } ?: EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "No OpenGL ES 3 context" }
    }

    /**
     * [work] on the GL thread. A failure is logged and the frame is lost, not the thread: the context
     * is current on it, and a thread that died would take the next frames' context with it.
     */
    private fun onThread(work: () -> Unit) {
        try {
            thread.execute {
                try {
                    work()
                } catch (failure: Exception) {
                    Log.w(LOG_TAG, "GL work failed", failure)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Released: nothing is drawn any more.
        }
    }

    private companion object {
        const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
        const val LOG_TAG = "ImagoGl"
    }
}
