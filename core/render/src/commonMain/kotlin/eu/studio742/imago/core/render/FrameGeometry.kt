package eu.studio742.imago.core.render

import kotlin.math.cos
import kotlin.math.sin

/**
 * The bridge between the original image's space and the visible frame's space.
 *
 * It exists because of the masks. Everything else in the pipeline lives in one of the two spaces and
 * never needs the other: the GPU keeps the `stage` in image space and only applies the geometry when
 * writing to the screen, and the CPU receives the bitmap already cropped and rotated. A mask is the
 * first concept defined in one space and consumed in the other — it is stuck to the **content**, but
 * the user draws it over the **framing**.
 *
 * [imageFromFramed] is the literal transcription of [PhotoShaders.COMPOSITE]'s `geometryCoordinate`,
 * with the crop step on top. [framedFromImage] is its inverse, and `FrameGeometryTest` checks that
 * they cancel out — it is the only way for the two not to diverge silently.
 */
data class FrameGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropWidth: Float = 1f,
    val cropHeight: Float = 1f,
    val straighten: Float = 0f,
    val quarterTurns: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
) {
    /**
     * The aspect ratio **after** the quarter turns.
     *
     * It is the same `PhotoRenderer` puts in `uImageAspect`, and has to be: it is what straightening
     * uses so as not to tilt, and using the sensor's aspect ratio on a photo rotated 90° gave a
     * different angle from what the preview shows.
     */
    private val orientedAspect: Float = run {
        val swaps = quarterTurns % 2 == 1
        val width = if (swaps) sourceHeight else sourceWidth
        val height = if (swaps) sourceWidth else sourceHeight
        width.toFloat() / height.coerceAtLeast(1)
    }

    /**
     * The original image's aspect ratio, **before** the quarter turns.
     *
     * It is not the same as [orientedAspect] and cannot be: masks live in original image coordinates,
     * and that is the aspect ratio that keeps a radial circular.
     */
    val imageAspect: Float = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1)

    private val straightenRadians: Float = Math.toRadians(straighten.toDouble()).toFloat()
    private val straightenScale: Float = straightenCoverScale(orientedAspect, straighten)

    /**
     * From the visible frame to the original image, both normalised with the origin at the top-left
     * corner. Writes into [out] so as not to allocate per texel.
     */
    fun imageFromFramed(u: Float, v: Float, out: FloatArray) {
        val croppedX = cropX + u * cropWidth
        val croppedY = cropY + v * cropHeight

        val pixelX = (croppedX - 0.5f) * orientedAspect
        val pixelY = croppedY - 0.5f
        val cosine = cos(straightenRadians)
        val sine = sin(straightenRadians)
        val unrotatedX = (cosine * pixelX + sine * pixelY) / straightenScale
        val unrotatedY = (-sine * pixelX + cosine * pixelY) / straightenScale
        var x = unrotatedX / orientedAspect + 0.5f
        var y = unrotatedY + 0.5f

        if (mirrorH) x = 1f - x
        if (mirrorV) y = 1f - y

        when (quarterTurns) {
            1 -> { out[0] = y; out[1] = 1f - x }
            2 -> { out[0] = 1f - x; out[1] = 1f - y }
            3 -> { out[0] = 1f - y; out[1] = x }
            else -> { out[0] = x; out[1] = y }
        }
    }

    /** From the original image to the visible frame. The exact inverse of [imageFromFramed]. */
    fun framedFromImage(u: Float, v: Float, out: FloatArray) {
        var x: Float
        var y: Float
        when (quarterTurns) {
            1 -> { x = 1f - v; y = u }
            2 -> { x = 1f - u; y = 1f - v }
            3 -> { x = v; y = 1f - u }
            else -> { x = u; y = v }
        }

        if (mirrorH) x = 1f - x
        if (mirrorV) y = 1f - y

        val unrotatedX = (x - 0.5f) * orientedAspect
        val unrotatedY = y - 0.5f
        val cosine = cos(straightenRadians)
        val sine = sin(straightenRadians)
        val pixelX = (cosine * unrotatedX - sine * unrotatedY) * straightenScale
        val pixelY = (sine * unrotatedX + cosine * unrotatedY) * straightenScale
        val croppedX = pixelX / orientedAspect + 0.5f
        val croppedY = pixelY + 0.5f

        out[0] = if (cropWidth != 0f) (croppedX - cropX) / cropWidth else 0f
        out[1] = if (cropHeight != 0f) (croppedY - cropY) / cropHeight else 0f
    }

}

/**
 * The geometry of these parameters, for a source image with these dimensions.
 *
 * The dimensions are those from **before** crop and rotation. A caller on the CPU path has to capture
 * them before transforming the bitmap, because `applyRecipeGeometry` and
 * `FullResolutionExporter.applyGeometry` throw them away when cropping.
 */
fun RenderParameters.frameGeometry(sourceWidth: Int, sourceHeight: Int) = FrameGeometry(
    sourceWidth = sourceWidth,
    sourceHeight = sourceHeight,
    cropX = cropX,
    cropY = cropY,
    cropWidth = cropWidth,
    cropHeight = cropHeight,
    straighten = straighten,
    quarterTurns = normalizedQuarterTurns(),
    mirrorH = mirrorH,
    mirrorV = mirrorV,
)
