package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
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
) {
    val renderer = remember { PhotoRenderer() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewHolder = remember { arrayOfNulls<GLSurfaceView>(1) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setEGLContextFactory(Es31ContextFactory)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                viewHolder[0] = this
            }
        },
        update = { view ->
            renderer.setBitmap(bitmap)
            renderer.setParameters(parameters)
            renderer.setShowOriginal(showOriginal)
            renderer.setMaskOverlay(maskOverlay)
            renderer.setTransform(transform, minZoom)
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
