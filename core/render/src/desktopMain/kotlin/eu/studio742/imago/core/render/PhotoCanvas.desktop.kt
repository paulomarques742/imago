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

actual val photoCanvasShowsClipping: Boolean = false

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
    highPrecisionSource: FloatPixelBuffer?,
    clipping: ClippingWarning,
    onFirstFrame: () -> Unit,
) {
    val source = remember(bitmap) { bitmap.toPixelBuffer() }
    val draft = remember(source) { RecipePixels.reduce(source, DRAFT_SIDE) }
    val floatDraft = remember(highPrecisionSource) { highPrecisionSource?.let { RecipePixels.reduce(it, DRAFT_SIDE) } }
    // Painted over the full photo and given to the draft too: a brush raster does not depend on the
    // size of what it is drawn on, and a stroke being drawn only adds its newest dabs.
    val brushCache = remember(source) { BrushRasterCache() }
    // Each size and precision of the photo with its corrections, worked out when they change and not
    // on every frame of a slider.
    val retouched = remember(source) { RetouchedSources(capacity = 4) }
    val active = if (showOriginal) parameters.original else parameters
    var frame by remember(source) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(source, highPrecisionSource, active, maskOverlay) {
        if (draft !== source) {
            // The draft says it is a draft: without that, the grain would come out with the granularity
            // of its eight hundred pixels and change look when full resolution replaced it.
            val scale = draft.width.toFloat() / source.width
            frame = withContext(Dispatchers.Default) {
                renderFrame(draft, active, maskOverlay, brushCache.of(active, source, retouched), retouched, scale, floatDraft)
            }
            delay(FULL_RESOLUTION_PAUSE_MS)
        }
        frame = withContext(Dispatchers.Default) {
            renderFrame(source, active, maskOverlay, brushCache.of(active, source, retouched), retouched, floatSource = highPrecisionSource)
        }
    }

    val hasFrame = frame != null
    LaunchedEffect(hasFrame) { if (hasFrame) onFirstFrame() }

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

/** The Auto Mask and the ranges read the photo as the corrections left it, as the export does. */
private fun BrushRasterCache.of(parameters: RenderParameters, source: PixelBuffer, retouched: RetouchedSources): BrushRasters =
    if (parameters.hasBrushes) {
        val corrected = retouched.of(source, parameters.retouches)
        rasters(parameters, source.width.toFloat() / source.height, corrected) { BrushReference.of(corrected) }
    } else {
        BrushRasters.NONE
    }

private fun renderFrame(
    source: PixelBuffer,
    parameters: RenderParameters,
    maskOverlay: Int,
    brushes: BrushRasters,
    retouched: RetouchedSources,
    renderScale: Float = 1f,
    floatSource: FloatPixelBuffer? = null,
): ImageBitmap {
    val framed = if (floatSource == null) RecipePixels.render(source, parameters, renderScale, brushes, retouched) else
        RecipePixels.renderFloat(floatSource, parameters, renderScale, brushes, retouched = retouched).toPixelBuffer()
    if (maskOverlay in 0 until minOf(parameters.masks.size, MAX_LOCAL_MASKS)) {
        tintMask(framed, source.width, source.height, parameters, maskOverlay, brushes)
    }
    return framed.toSkiaBitmap().asComposeImageBitmap()
}

/** The draft's longer side: about 50 ms on an eight-core laptop. */
private const val DRAFT_SIDE = 800
private const val FULL_RESOLUTION_PAUSE_MS = 180L
