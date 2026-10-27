package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The editor's GPU pipeline drawing into memory instead of onto the screen: [source] with a recipe,
 * framed and cropped, as a Bitmap — exactly what the editor shows, at a fraction of the CPU's cost.
 *
 * It is the [PhotoRenderer] the editor's view uses, on a pbuffer the size of the framed photo, so
 * the fit fills it edge to edge and reading the pixels back gives the photo and nothing around it.
 * A GL context only works on the thread it is current on, so everything runs on one thread of its
 * own; [render] blocks the caller until the frame is back.
 *
 * @throws IllegalStateException from the constructor when the device gives no OpenGL ES 3 context
 */
class OffscreenRecipeRenderer(private var source: Bitmap) : AutoCloseable {
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "imago-offscreen-gl") }
    private val renderer = PhotoRenderer()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        try {
            onThread {
                setUpEgl()
                renderer.onSurfaceCreated(null, null)
                renderer.setBitmap(source)
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    /** [parameters] over the source, framed, with its longest side at most [maxSide]. */
    fun render(parameters: RenderParameters, maxSide: Int): Bitmap = onThread { draw(parameters, maxSide) }

    /**
     * [parameters] over another photo, on the same context: what the library's previews use, one photo
     * after another, without paying for a context and the shaders each time.
     */
    fun render(source: Bitmap, parameters: RenderParameters, maxSide: Int): Bitmap = onThread {
        if (source !== this.source) {
            this.source = source
            renderer.setBitmap(source)
        }
        draw(parameters, maxSide)
    }

    /** On the GL thread: one frame of [parameters], read back. */
    private fun draw(parameters: RenderParameters, maxSide: Int): Bitmap {
        val (width, height) = framedSize(parameters, maxSide)
        useSurface(width, height)
        renderer.onSurfaceChanged(null, width, height)
        renderer.setParameters(parameters)
        renderer.onDrawFrame(null)
        return readPixels(width, height)
    }

    override fun close() {
        runCatching {
            onThread {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
            }
        }
        thread.shutdown()
    }

    /** The cropped photo's size, as the CPU render computes it, reduced to [maxSide]. */
    private fun framedSize(parameters: RenderParameters, maxSide: Int): Pair<Int, Int> {
        val turns = parameters.normalizedQuarterTurns()
        val width = (if (turns % 2 == 1) source.height else source.width) * parameters.cropWidth
        val height = (if (turns % 2 == 1) source.width else source.height) * parameters.cropHeight
        val scale = minOf(1f, maxSide / max(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun setUpEgl() {
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
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No OpenGL ES 3 pbuffer configuration"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "No OpenGL ES 3 context" }
        useSurface(1, 1)
    }

    /** The pbuffer follows the framed size, which changes while a crop animates. */
    private fun useSurface(width: Int, height: Int) {
        if (surface != EGL14.EGL_NO_SURFACE && width == surfaceWidth && height == surfaceHeight) return
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
        }
        surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE),
            0,
        )
        check(surface != EGL14.EGL_NO_SURFACE) { "No pbuffer of $width × $height" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "The context did not become current" }
        surfaceWidth = width
        surfaceHeight = height
    }

    /** GL counts rows from the bottom; the bitmap from the top. */
    private fun readPixels(width: Int, height: Int): Bitmap {
        val rowBytes = width * 4
        val pixels = ByteBuffer.allocateDirect(rowBytes * height).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        val flipped = ByteBuffer.allocateDirect(rowBytes * height).order(ByteOrder.nativeOrder())
        val row = ByteArray(rowBytes)
        for (y in height - 1 downTo 0) {
            pixels.position(y * rowBytes)
            pixels.get(row)
            flipped.put(row)
        }
        flipped.rewind()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(flipped) }
    }

    private fun <T> onThread(work: () -> T): T = try {
        thread.submit(Callable(work)).get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}
