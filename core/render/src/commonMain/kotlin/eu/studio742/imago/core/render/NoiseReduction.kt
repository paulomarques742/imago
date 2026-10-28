package eu.studio742.imago.core.render

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Step 10: noise reduction, luminance and colour apart, run on the toned image before anything that
 * has a neighbourhood — so Texture, Clarity and the sharpening see the photo without its noise.
 *
 * **Luminance.** What is left of a pixel once its neighbourhood (a Gaussian of [LUMA_SIGMA] pixels) is
 * taken away is noise or detail; the two differ in size, and a small residual is mostly noise. The
 * residual is multiplied by `|r|^p / (|r|^p + t^p)`: near zero it falls away, far from it it passes.
 *  - the Luminance slider sets the threshold `t`;
 *  - Detail lowers `t`, so that more of the fine texture counts as detail;
 *  - Contrast raises the exponent `p` from 2 to 4, which sharpens the knee: the contrast that is above
 *    it is kept more exactly, and what is below goes more completely.
 *
 * **Colour.** The chroma of a pixel — what is left of its colour once its luminance is taken — moves
 * towards the chroma of a much larger neighbourhood, by a weight that falls to zero where the two are
 * far apart: a colour edge is not a blotch. The neighbourhood is a Gaussian measured at half
 * resolution, of [chromaSigma] pixels of the full one, which Smoothness widens; Detail narrows the
 * distance at which an edge counts, and the Colour slider is how much of the way a blotch is taken.
 *
 * The numbers are IMAGO's, chosen on noisy samples. The preview does this in `PhotoShaders.NOISE` and
 * the export in [NoiseReductionPass] from the same formulas; `PhotoShaderParityTest` pins the constants.
 */
internal object NoiseReduction {
    /** The luminance's neighbourhood, in pixels of the image. */
    const val LUMA_SIGMA = 1.2f

    /** The colour's neighbourhood, in pixels of the full image: from 2 to 12. */
    fun chromaSigma(smoothness: Float): Float = 2f + 10f * (smoothness / 100f).coerceIn(0f, 1f)

    /** The size of a luminance residual below which it is noise, in luminance. */
    fun lumaThreshold(amount: Float, detail: Float): Float {
        val a = (amount / 100f).coerceIn(0f, 1f)
        val d = (detail / 100f).coerceIn(0f, 1f)
        return (0.003f + 0.08f * a) * (1.3f - 0.8f * d)
    }

    /** The share of a luminance residual that stays. */
    fun lumaKeep(residual: Float, threshold: Float, contrast: Float): Float {
        val p = 2f + 2f * (contrast / 100f).coerceIn(0f, 1f)
        val size = abs(residual).pow(p)
        return size / (size + threshold.pow(p))
    }

    /** The distance between a pixel's chroma and its neighbourhood's beyond which it is an edge. */
    fun chromaKnee(detail: Float): Float = 0.02f + 0.30f * (1f - 0.85f * (detail / 100f).coerceIn(0f, 1f))

    /** How much of the way a pixel's chroma goes to its neighbourhood's. */
    fun chromaWeight(amount: Float, detail: Float, distance: Float): Float =
        min(1f, amount / 100f * 1.6f) * (1f - PhotoEffects.smoothstep(0f, chromaKnee(detail), distance))
}

/**
 * The export's side of step 10: the blurred copies the pass needs, taken from the toned image before the
 * pass writes over it. A pixel needs only itself and these, so the pass can run in bands.
 */
internal class NoiseReductionPass private constructor(
    private val parameters: RenderParameters,
    private val width: Int,
    private val height: Int,
    private val lumaBlur: FloatArray?,
    private val chroma: Array<FloatArray>?,
    private val chromaWidth: Int,
    private val chromaHeight: Int,
) {
    /** One row, in place: [row] holds `width` RGBA floats. */
    fun applyRow(y: Int, row: FloatArray) {
        val v = (y + 0.5f) / height
        for (x in 0 until width) {
            val red = row[x * 4]
            val green = row[x * 4 + 1]
            val blue = row[x * 4 + 2]
            val luminance = PhotoEffects.luminance(red, green, blue)
            var cleaned = luminance
            if (lumaBlur != null) {
                val blurred = lumaBlur[y * width + x]
                val residual = luminance - blurred
                val threshold = NoiseReduction.lumaThreshold(parameters.noiseLuminance, parameters.noiseLuminanceDetail)
                cleaned = blurred + residual * NoiseReduction.lumaKeep(residual, threshold, parameters.noiseContrast)
            }
            var chromaRed = red - luminance
            var chromaGreen = green - luminance
            var chromaBlue = blue - luminance
            if (chroma != null) {
                val u = (x + 0.5f) / width
                val blurRed = DetailPyramid.sampleBilinear(chroma[0], chromaWidth, chromaHeight, u, v)
                val blurGreen = DetailPyramid.sampleBilinear(chroma[1], chromaWidth, chromaHeight, u, v)
                val blurBlue = DetailPyramid.sampleBilinear(chroma[2], chromaWidth, chromaHeight, u, v)
                val blurLuminance = PhotoEffects.luminance(blurRed, blurGreen, blurBlue)
                val neighbourRed = blurRed - blurLuminance
                val neighbourGreen = blurGreen - blurLuminance
                val neighbourBlue = blurBlue - blurLuminance
                val dr = chromaRed - neighbourRed
                val dg = chromaGreen - neighbourGreen
                val db = chromaBlue - neighbourBlue
                val weight = NoiseReduction.chromaWeight(
                    parameters.noiseColor,
                    parameters.noiseColorDetail,
                    sqrt(dr * dr + dg * dg + db * db),
                )
                chromaRed -= dr * weight
                chromaGreen -= dg * weight
                chromaBlue -= db * weight
            }
            row[x * 4] = (cleaned + chromaRed).coerceIn(0f, 1f)
            row[x * 4 + 1] = (cleaned + chromaGreen).coerceIn(0f, 1f)
            row[x * 4 + 2] = (cleaned + chromaBlue).coerceIn(0f, 1f)
        }
    }

    companion object {
        fun build(bitmap: PixelSurface, parameters: RenderParameters): NoiseReductionPass {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = FloatArray(width * 4)
            val scratch = IntArray(width)

            var lumaBlur: FloatArray? = null
            if (parameters.noiseLuminance > 0f) {
                val luminance = FloatArray(width * height)
                for (y in 0 until height) {
                    bitmap.readRgbaRow(y, pixels, scratch)
                    for (x in 0 until width) {
                        luminance[y * width + x] = PhotoEffects.luminance(pixels[x * 4], pixels[x * 4 + 1], pixels[x * 4 + 2])
                    }
                }
                DetailPyramid.blurInPlace(luminance, width, height, NoiseReduction.LUMA_SIGMA)
                lumaBlur = luminance
            }

            var chroma: Array<FloatArray>? = null
            val halfWidth = max(width / 2, 1)
            val halfHeight = max(height / 2, 1)
            if (parameters.noiseColor > 0f) {
                // Half resolution: a blotch of colour is wide, and this is a quarter of the work and memory.
                val channels = Array(3) { FloatArray(halfWidth * halfHeight) }
                val below = FloatArray(width * 4)
                for (y in 0 until halfHeight) {
                    val top = min(y * 2, height - 1)
                    val bottom = min(top + 1, height - 1)
                    bitmap.readRgbaRow(top, pixels, scratch)
                    bitmap.readRgbaRow(bottom, below, scratch)
                    for (x in 0 until halfWidth) {
                        val left = min(x * 2, width - 1)
                        val right = min(left + 1, width - 1)
                        for (channel in 0..2) {
                            channels[channel][y * halfWidth + x] = 0.25f * (
                                pixels[left * 4 + channel] + pixels[right * 4 + channel] +
                                    below[left * 4 + channel] + below[right * 4 + channel]
                                )
                        }
                    }
                }
                val sigma = NoiseReduction.chromaSigma(parameters.noiseColorSmoothness) / 2f
                channels.forEach { DetailPyramid.blurInPlace(it, halfWidth, halfHeight, sigma) }
                chroma = channels
            }
            return NoiseReductionPass(parameters, width, height, lumaBlur, chroma, halfWidth, halfHeight)
        }
    }
}
