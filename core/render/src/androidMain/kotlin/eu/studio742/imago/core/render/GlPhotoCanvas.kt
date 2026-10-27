package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

@Composable
fun GlPhotoCanvas(
    bitmap: Bitmap,
    parameters: RenderParameters,
    showOriginal: Boolean,
    transform: PhotoTransform,
    minZoom: Float = MIN_PHOTO_ZOOM,
    /**
     * Index of the mask to highlight in red, or -1 for none.
     *
     * It is a parameter of its own and not a field of [RenderParameters] because it is a visual aid of
     * the editor: in the parameters it would take part in `isNeutral` and the export would have to
     * ignore it explicitly — two things that should not depend on an overlay.
     */
    maskOverlay: Int = -1,
    modifier: Modifier = Modifier,
    highPrecisionSource: FloatPixelBuffer? = null,
) {
    val renderer = remember { PhotoRenderer() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewHolder = remember { arrayOfNulls<GLSurfaceView>(1) }
    // The renderer's inputs as the last composition gave them, so they can be handed over again
    // outside one.
    val applyInputs = remember { arrayOfNulls<() -> Unit>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setEGLContextFactory(Es31ContextFactory)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                // The stage stayed black when this view was born into a screen already in place — the
                // first time a photo opens in the editor, and every photo swiped to inside it — and not
                // when it was born with the screen, coming back to a photo already loaded. Waiting
                // changed nothing; a tap always brought the photo. A tap does three things: the renderer
                // gets its inputs again and draws a frame, and the window lays out and redraws, which is
                // when a SurfaceView behind it is placed and shown. Asking only for frames, and only
                // once the renderer reported a drawn photo, did not help. So the surface starts all
                // three itself whenever the system says it is ready or changed, now and twice more
                // across half a second. The first frames of each surface are logged (tag "ImagoGl")
                // with what they did, in case this is still not it.
                val surfaceView = this
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        SURFACE_FRAME_DELAYS_MS.forEach { delay ->
                            surfaceView.postDelayed({
                                applyInputs[0]?.invoke()
                                surfaceView.requestRender()
                                surfaceView.requestLayout()
                                surfaceView.invalidate()
                            }, delay)
                        }
                    }
                })
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) requestRender()
                }
                viewHolder[0] = this
            }
        },
        update = { view ->
            val inputs = {
                renderer.setBitmap(bitmap)
                renderer.setHighPrecisionSource(highPrecisionSource)
                renderer.setParameters(parameters)
                renderer.setShowOriginal(showOriginal)
                renderer.setMaskOverlay(maskOverlay)
                renderer.setTransform(transform, minZoom)
            }
            applyInputs[0] = inputs
            inputs()
            view.requestRender()
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewHolder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> viewHolder[0]?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewHolder[0] = null
        }
    }
}

/** The frames asked for when the surface is ready or changes: right away, and two more across half a second. */
private val SURFACE_FRAME_DELAYS_MS = longArrayOf(0L, 150L, 500L)

private object Es31ContextFactory : GLSurfaceView.EGLContextFactory {
    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    private const val EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB

    override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext =
        egl.eglCreateContext(
            display,
            config,
            EGL10.EGL_NO_CONTEXT,
            intArrayOf(
                EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL_CONTEXT_MINOR_VERSION_KHR, 1,
                EGL10.EGL_NONE,
            ),
        )

    override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
        egl.eglDestroyContext(display, context)
    }
}
