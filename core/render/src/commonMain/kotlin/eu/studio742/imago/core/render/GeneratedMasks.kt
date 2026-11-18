package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.MaskShape
import java.util.stream.IntStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A generated mask's raster: the model's coarse output, refined against the original photo.
 *
 * A model sees the photo at 320 or 768 pixels, so its edges are soft and in the wrong place by a few of the
 * photo's pixels. A guided filter (He, Sun and Tang) fits the mask, window by window, as a linear function of
 * the photo's colour, which moves its edges onto the photo's own edges: hair, branches against the sky. The
 * window statistics are weighted by how sure the model was, (2p − 1)², as in Google's Sky Optimization
 * (Liba et al., 2020), so that where the model hesitated the photo decides.
 *
 * It is the fast variant: the coefficients are worked out at a quarter of the raster and applied at full
 * resolution, which gave the same mask as the full filter, to under 0.002 on average, in a tenth of the time.
 */
internal object GeneratedMasks {
    /** The coefficients' grid, as a fraction of the raster. */
    private const val SUBSAMPLE = 4

    /** The window's radius, as a fraction of the raster's long side. */
    private const val RADIUS = 1f / 80f

    /** Regularisation: how flat a window must be before the filter stops following its colours. */
    private const val EPSILON = 1e-4

    fun paint(raster: BrushRaster, component: MaskRenderComponent, reference: BrushReference) {
        val generated = component.generated
        val weights = generated?.pixels()
        if (generated == null || weights == null) {
            raster.clear()
            return
        }
        val width = raster.width
        val height = raster.height
        val lowWidth = max(1, (width + SUBSAMPLE - 1) / SUBSAMPLE)
        val lowHeight = max(1, (height + SUBSAMPLE - 1) / SUBSAMPLE)
        val radius = max(1, (max(width, height) * RADIUS / SUBSAMPLE).roundToInt())

        val guide = lowGuide(reference, width, height, lowWidth, lowHeight)
        val prior = resample(weights, generated.width, generated.height, lowWidth, lowHeight)
        val (a, b) = coefficients(guide, prior, lowWidth, lowHeight, radius)

        val invert = component.shape == MaskShape.BACKGROUND
        IntStream.range(0, height).parallel().forEach { row ->
            val colour = FloatArray(3)
            val sy = ((row + 0.5f) / SUBSAMPLE - 0.5f).coerceIn(0f, (lowHeight - 1).toFloat())
            for (column in 0 until width) {
                val sx = ((column + 0.5f) / SUBSAMPLE - 0.5f).coerceIn(0f, (lowWidth - 1).toFloat())
                val texel = row * width + column
                reference.colour(texel, colour)
                var q = bilinear(b, lowWidth, lowHeight, sx, sy, 1, 0)
                for (channel in 0 until 3) q += bilinear(a, lowWidth, lowHeight, sx, sy, 3, channel) * colour[channel]
                val weight = q.toFloat().coerceIn(0f, 1f)
                raster.set(texel, if (invert) 1f - weight else weight)
            }
        }
        raster.markDirty(0, 0, width, height)
    }

    /** The original's colours averaged over each cell of the coefficients' grid. */
    private fun lowGuide(reference: BrushReference, width: Int, height: Int, lowWidth: Int, lowHeight: Int): DoubleArray {
        val guide = DoubleArray(lowWidth * lowHeight * 3)
        val counts = IntArray(lowWidth * lowHeight)
        val colour = FloatArray(3)
        for (y in 0 until height) for (x in 0 until width) {
            reference.colour(y * width + x, colour)
            val cell = (y / SUBSAMPLE) * lowWidth + x / SUBSAMPLE
            for (channel in 0 until 3) guide[cell * 3 + channel] += colour[channel].toDouble()
            counts[cell]++
        }
        for (cell in counts.indices) for (channel in 0 until 3) guide[cell * 3 + channel] /= counts[cell]
        return guide
    }

    /** The model's weights (0 to 255) on the coefficients' grid, bilinear, as 0 to 1. */
    private fun resample(weights: ByteArray, width: Int, height: Int, toWidth: Int, toHeight: Int): DoubleArray {
        val source = DoubleArray(width * height) { (weights[it].toInt() and 0xff) / 255.0 }
        return DoubleArray(toWidth * toHeight) { index ->
            val sx = ((index % toWidth + 0.5f) * width / toWidth - 0.5f).coerceIn(0f, (width - 1).toFloat())
            val sy = ((index / toWidth + 0.5f) * height / toHeight - 0.5f).coerceIn(0f, (height - 1).toFloat())
            bilinear(source, width, height, sx, sy, 1, 0)
        }
    }

    /** The linear coefficients per cell, already averaged over each window: three for a, one for b. */
    private fun coefficients(guide: DoubleArray, prior: DoubleArray, width: Int, height: Int, radius: Int): Pair<DoubleArray, DoubleArray> {
        val cells = width * height
        val confidence = DoubleArray(cells) { (2.0 * prior[it] - 1.0).let { d -> d * d } + 1e-3 }
        val weightSum = box(confidence, width, height, radius)
        fun mean(values: (Int) -> Double): DoubleArray {
            val sums = box(DoubleArray(cells) { confidence[it] * values(it) }, width, height, radius)
            for (cell in 0 until cells) sums[cell] /= weightSum[cell]
            return sums
        }
        val meanI = Array(3) { channel -> mean { guide[it * 3 + channel] } }
        val meanP = mean { prior[it] }
        val meanIP = Array(3) { channel -> mean { guide[it * 3 + channel] * prior[it] } }
        val meanII = Array(6) { pair ->
            val (i, j) = PAIRS[pair]
            mean { guide[it * 3 + i] * guide[it * 3 + j] }
        }
        val a = DoubleArray(cells * 3)
        val b = DoubleArray(cells)
        for (cell in 0 until cells) {
            val c00 = meanII[0][cell] - meanI[0][cell] * meanI[0][cell] + EPSILON
            val c01 = meanII[1][cell] - meanI[0][cell] * meanI[1][cell]
            val c02 = meanII[2][cell] - meanI[0][cell] * meanI[2][cell]
            val c11 = meanII[3][cell] - meanI[1][cell] * meanI[1][cell] + EPSILON
            val c12 = meanII[4][cell] - meanI[1][cell] * meanI[2][cell]
            val c22 = meanII[5][cell] - meanI[2][cell] * meanI[2][cell] + EPSILON
            val v0 = meanIP[0][cell] - meanI[0][cell] * meanP[cell]
            val v1 = meanIP[1][cell] - meanI[1][cell] * meanP[cell]
            val v2 = meanIP[2][cell] - meanI[2][cell] * meanP[cell]
            // The covariance is symmetric and, with epsilon, positive definite: its inverse by cofactors.
            val i00 = c11 * c22 - c12 * c12
            val i01 = c02 * c12 - c01 * c22
            val i02 = c01 * c12 - c02 * c11
            val i11 = c00 * c22 - c02 * c02
            val i12 = c01 * c02 - c00 * c12
            val i22 = c00 * c11 - c01 * c01
            val determinant = c00 * i00 + c01 * i01 + c02 * i02
            val a0 = (i00 * v0 + i01 * v1 + i02 * v2) / determinant
            val a1 = (i01 * v0 + i11 * v1 + i12 * v2) / determinant
            val a2 = (i02 * v0 + i12 * v1 + i22 * v2) / determinant
            a[cell * 3] = a0
            a[cell * 3 + 1] = a1
            a[cell * 3 + 2] = a2
            b[cell] = meanP[cell] - a0 * meanI[0][cell] - a1 * meanI[1][cell] - a2 * meanI[2][cell]
        }
        // Each texel takes the mean of the coefficients of every window it is in.
        val meanA = DoubleArray(cells * 3)
        for (channel in 0 until 3) {
            val averaged = box(DoubleArray(cells) { a[it * 3 + channel] }, width, height, radius)
            for (cell in 0 until cells) meanA[cell * 3 + channel] = averaged[cell]
        }
        return meanA to box(b, width, height, radius)
    }

    /** The mean over a (2r + 1)² window, at the edges over the part of it inside the grid. */
    private fun box(values: DoubleArray, width: Int, height: Int, radius: Int): DoubleArray {
        val stride = width + 1
        val integral = DoubleArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0.0
            for (x in 0 until width) {
                row += values[y * width + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        return DoubleArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val left = max(0, x - radius)
            val right = min(width, x + radius + 1)
            val top = max(0, y - radius)
            val bottom = min(height, y + radius + 1)
            val sum = integral[bottom * stride + right] - integral[top * stride + right] -
                integral[bottom * stride + left] + integral[top * stride + left]
            sum / ((right - left) * (bottom - top))
        }
    }

    private fun bilinear(values: DoubleArray, width: Int, height: Int, x: Float, y: Float, stride: Int, channel: Int): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = (x - x0).toDouble()
        val fy = (y - y0).toDouble()
        val top = values[(y0 * width + x0) * stride + channel] * (1 - fx) + values[(y0 * width + x1) * stride + channel] * fx
        val bottom = values[(y1 * width + x0) * stride + channel] * (1 - fx) + values[(y1 * width + x1) * stride + channel] * fx
        return top * (1 - fy) + bottom * fy
    }

    private val PAIRS = arrayOf(0 to 0, 0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 2)
}
