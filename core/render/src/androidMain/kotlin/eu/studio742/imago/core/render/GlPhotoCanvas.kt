package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

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
    // Drawn inside the window, not behind it: see [GlTextureStage] for the black stage this ended.
    val stage = remember { GlTextureStage(renderer) }
    DisposableEffect(stage) { onDispose { stage.release() } }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                // The photo covers its rectangle and the clear colour the rest: nothing to blend with.
                isOpaque = true
                surfaceTextureListener = stage
            }
        },
        update = {
            renderer.setBitmap(bitmap)
            renderer.setHighPrecisionSource(highPrecisionSource)
            renderer.setParameters(parameters)
            renderer.setShowOriginal(showOriginal)
            renderer.setMaskOverlay(maskOverlay)
            renderer.setTransform(transform, minZoom)
            stage.requestRender()
        },
    )
}
