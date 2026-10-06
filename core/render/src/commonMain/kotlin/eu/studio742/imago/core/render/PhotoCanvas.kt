package eu.studio742.imago.core.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The editor's photo, with the recipe applied and the zoom and pan transformation.
 *
 * On Android it is the OpenGL ES renderer ([GlPhotoCanvas]); on desktop, the CPU engine with a
 * draft that keeps up with the sliders. Both draw in the same rectangle ([calculatePhotoViewport]),
 * so the crop frames and the mask handles, which the editor draws on top, stay in the right place
 * on both platforms.
 *
 * @param maskOverlay index of the mask to highlight in red, or -1 for none.
 */
@Composable
expect fun PhotoCanvas(
    bitmap: coil3.Bitmap,
    parameters: RenderParameters,
    showOriginal: Boolean,
    transform: PhotoTransform,
    minZoom: Float = MIN_PHOTO_ZOOM,
    maskOverlay: Int = -1,
    modifier: Modifier = Modifier,
)
