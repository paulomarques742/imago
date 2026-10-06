package eu.studio742.imago.core.render

import kotlin.math.max
import kotlin.math.min
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS

/**
 * The local adaptation's base layer: the image's luminance as it enters step 8, reduced to one
 * eighth and blurred.
 *
 * It is what Lightroom calls *local adaptation* and darktable obtains with a blur filter before
 * compressing the range: the decision on what is a highlight or a shadow stops being made pixel by
 * pixel and starts being made by the neighbourhood. A small reflection on a dark background stops
 * being treated as if it were sky.
 *
 * Luminance is kept and not RGB for the same reason as in [DetailPyramid] — blur and reduction are
 * linear — but there is an order here that cannot be swapped: steps 1 to 7 are applied on the first
 * reduction, **before** the chain continues. Applying them afterwards would give something else,
 * because the gamma conversion does not commute with averaging, and [PhotoRenderer] does exactly the
 * same in the `LOCAL_BASE` pass.
 *
 * One eighth of the resolution loses nothing: the blur has a σ of [PhotoEffects.LOCAL_TONE_SIGMA]
 * full-image pixels, far larger than the eight pixels the reduction throws away.
 */
internal class LocalToneMask private constructor(
    private val data: FloatArray,
    private val width: Int,
    private val height: Int,
) {
    /** The neighbourhood's base luminance, sampled as the GL sampler would. */
    fun at(u: Float, v: Float): Float = DetailPyramid.sampleBilinear(data, width, height, u, v)

    companion object {
        /**
         * @param field the mask weights, or null if there are none.
         *
         * The base **has** to see the local exposure. If exposure varies by region and this layer is
         * built with the global one, the adaptation decides what is a highlight and what is a shadow on
         * an image that is not the one entering step 8 — and the two curves start correcting the wrong
         * place, which is precisely what this class exists to avoid.
         */
        fun build(
            bitmap: PixelSurface,
            parameters: RenderParameters,
            field: LocalMaskField? = null,
        ): LocalToneMask {
            val width = bitmap.width
            val height = bitmap.height
            val halfWidth = max(width / 2, 1)
            val halfHeight = max(height / 2, 1)

            val half = FloatArray(halfWidth * halfHeight)
            val top = IntArray(width)
            val bottom = IntArray(width)
            val rgb = FloatArray(3)
            val weights = FloatArray(MAX_LOCAL_MASKS)
            val tone = parameters.effectiveTone()
            for (row in 0 until halfHeight) {
                val topRow = min(row * 2, height - 1)
                val bottomRow = min(topRow + 1, height - 1)
                bitmap.getPixels(top, 0, width, 0, topRow, width, 1)
                bitmap.getPixels(bottom, 0, width, 0, bottomRow, width, 1)
                val base = row * halfWidth
                val v = (row + 0.5f) / halfHeight
                for (x in 0 until halfWidth) {
                    val left = min(x * 2, width - 1)
                    val right = min(left + 1, width - 1)
                    rgb[0] = averageChannel(top, bottom, left, right, 16)
                    rgb[1] = averageChannel(top, bottom, left, right, 8)
                    rgb[2] = averageChannel(top, bottom, left, right, 0)
                    if (field != null) field.resolve(parameters, (x + 0.5f) / halfWidth, v, weights, tone)
                    BitmapPhotoProcessor.applyBaseTone(rgb, tone)
                    half[base + x] = PhotoEffects.luminance(rgb[0], rgb[1], rgb[2])
                }
            }

            val quarterWidth = max(halfWidth / 2, 1)
            val quarterHeight = max(halfHeight / 2, 1)
            val quarter = DetailPyramid.reduce(half, halfWidth, halfHeight, quarterWidth, quarterHeight)
            val eighthWidth = max(quarterWidth / 2, 1)
            val eighthHeight = max(quarterHeight / 2, 1)
            val eighth = DetailPyramid.reduce(quarter, quarterWidth, quarterHeight, eighthWidth, eighthHeight)
            DetailPyramid.blurInPlace(
                data = eighth,
                width = eighthWidth,
                height = eighthHeight,
                sigma = PhotoEffects.LOCAL_TONE_SIGMA / 8f,
            )
            return LocalToneMask(eighth, eighthWidth, eighthHeight)
        }

        /** 2×2 average of a channel, the same four pixels the shader's four samples read. */
        private fun averageChannel(
            top: IntArray,
            bottom: IntArray,
            left: Int,
            right: Int,
            shift: Int,
        ): Float = (
            (top[left] ushr shift and 0xFF) + (top[right] ushr shift and 0xFF) +
                (bottom[left] ushr shift and 0xFF) + (bottom[right] ushr shift and 0xFF)
            ) / (4f * 255f)
    }
}
