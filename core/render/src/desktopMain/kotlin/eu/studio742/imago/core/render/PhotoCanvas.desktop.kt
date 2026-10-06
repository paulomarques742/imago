package eu.studio742.imago.core.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import kotlin.math.roundToInt

/**
 * The editor's photo on desktop, through the CPU engine.
 *
 * Every change of parameters first draws a draft, small enough to keep up with a slider being
 * dragged, and only after a pause the full-resolution version. A new change cancels the previous
 * one: what reaches the screen is always the most recent recipe.
 */
@Composable
actual fun PhotoCanvas(
    bitmap: coil3.Bitmap,
    parameters: RenderParameters,
    showOriginal: Boolean,
    transform: PhotoTransform,
    minZoom: Float,
    maskOverlay: Int,
    modifier: Modifier,
) {
    val source = remember(bitmap) { bitmap.toPixelBuffer() }
    val draft = remember(source) { RecipePixels.reduce(source, DRAFT_SIDE) }
    val active = if (showOriginal) RenderParameters() else parameters
    var frame by remember(source) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(source, active, maskOverlay) {
        if (draft !== source) {
            // The draft says it is a draft: without that, the grain would come out with the granularity
            // of its eight hundred pixels and change look when full resolution replaced it.
            val scale = draft.width.toFloat() / source.width
            frame = withContext(Dispatchers.Default) { renderFrame(draft, active, maskOverlay, scale) }
            delay(FULL_RESOLUTION_PAUSE_MS)
        }
        frame = withContext(Dispatchers.Default) { renderFrame(source, active, maskOverlay) }
    }

    Canvas(modifier) {
        val image = frame ?: return@Canvas
        // The frame's dimensions come from the whole image, as in GL: the draft is just a smaller
        // version of the same rectangle.
        val turns = active.normalizedQuarterTurns()
        val rotatedWidth = if (turns % 2 == 1) source.height else source.width
        val rotatedHeight = if (turns % 2 == 1) source.width else source.height
        val viewport = calculatePhotoViewport(
            surfaceWidth = size.width.roundToInt(),
            surfaceHeight = size.height.roundToInt(),
            imageWidth = (rotatedWidth * active.cropWidth).roundToInt().coerceAtLeast(1),
            imageHeight = (rotatedHeight * active.cropHeight).roundToInt().coerceAtLeast(1),
            zoom = transform.zoom,
            panX = transform.panX,
            panY = transform.panY,
            minZoom = minZoom,
        )
        // GL's viewport counts from the bottom up; Compose, from the top down.
        val top = size.height.roundToInt() - viewport.bottom - viewport.height
        drawImage(
            image = image,
            dstOffset = IntOffset(viewport.left, top),
            dstSize = IntSize(viewport.width, viewport.height),
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun renderFrame(
    source: PixelBuffer,
    parameters: RenderParameters,
    maskOverlay: Int,
    renderScale: Float = 1f,
): ImageBitmap {
    val framed = RecipePixels.render(source, parameters, renderScale)
    if (maskOverlay in 0 until minOf(parameters.masks.size, MAX_LOCAL_MASKS)) tintMask(framed, source, parameters, maskOverlay)
    return framed.toSkiaBitmap().asComposeImageBitmap()
}

/** The same blend as the shader: 45% of the mask's weight towards a fixed red. */
private fun tintMask(framed: PixelBuffer, source: PixelBuffer, parameters: RenderParameters, index: Int) {
    val field = LocalMaskField.build(framed.width, framed.height, parameters, parameters.frameGeometry(source.width, source.height))
    val weights = FloatArray(MAX_LOCAL_MASKS)
    for (y in 0 until framed.height) for (x in 0 until framed.width) {
        field.at((x + .5f) / framed.width, (y + .5f) / framed.height, weights)
        val amount = weights[index] * 0.45f
        if (amount <= 0f) continue
        val i = y * framed.width + x
        val p = framed.pixels[i]
        fun mix(channel: Int, target: Float) = (channel + (target * 255f - channel) * amount).roundToInt().coerceIn(0, 255)
        framed.pixels[i] = (p and 0xFF000000.toInt()) or (mix(p ushr 16 and 255, 0.90f) shl 16) or
            (mix(p ushr 8 and 255, 0.20f) shl 8) or mix(p and 255, 0.25f)
    }
}

/** The draft's longer side: about 50 ms on an eight-core laptop. */
private const val DRAFT_SIDE = 800
private const val FULL_RESOLUTION_PAUSE_MS = 180L
