package eu.studio742.imago.core.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun PhotoCanvas(
    bitmap: coil3.Bitmap,
    parameters: RenderParameters,
    showOriginal: Boolean,
    transform: PhotoTransform,
    minZoom: Float,
    maskOverlay: Int,
    modifier: Modifier,
) = GlPhotoCanvas(bitmap, parameters, showOriginal, transform, minZoom, maskOverlay, modifier)
