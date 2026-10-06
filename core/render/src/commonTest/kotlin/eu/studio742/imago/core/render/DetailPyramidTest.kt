package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pyramid blur runs in place, with a ring of the original rows, so as not to duplicate a whole
 * plane on a 24 MP export. It is easy code to break without noticing, so it is compared with a naive
 * convolution written here on purpose.
 */
class DetailPyramidTest {
    @Test
    fun inPlaceBlurMatchesANaiveSeparableConvolution() {
        listOf(1f, 2.6f / 2f, 3f, 6f).forEach { sigma ->
            val width = 23
            val height = 17
            val source = FloatArray(width * height) { index ->
                val x = index % width
                val y = index / width
                (x * 7 + y * 13) % 11 / 10f
            }
            val actual = source.copyOf()
            DetailPyramid.blurInPlace(actual, width, height, sigma)
            val expected = naiveBlur(source, width, height, sigma)

            actual.indices.forEach { index ->
                assertEquals("sigma=$sigma index=$index", expected[index], actual[index], 1e-4f)
            }
        }
    }

    @Test
    fun blurPreservesAConstantField() {
        val data = FloatArray(40 * 30) { 0.42f }
        DetailPyramid.blurInPlace(data, 40, 30, 3f)
        assertTrue(data.all { abs(it - 0.42f) < 1e-5f })
    }

    @Test
    fun blurSurvivesPlanesThinnerThanItsRadius() {
        // One eighth of a small photo can have fewer rows than clarity's blur radius; the replicated
        // margin has to cope with that without leaving the plane.
        val data = FloatArray(5 * 2) { it / 10f }
        DetailPyramid.blurInPlace(data, 5, 2, 6f)
        assertTrue(data.all { it.isFinite() && it in 0f..1f })
    }

    @Test
    fun bilinearSamplingMatchesTheClampToEdgeSampler() {
        val data = floatArrayOf(
            0f, 1f,
            2f, 3f,
        )
        // Centro do texel (0,0) — devolve o valor exacto.
        assertEquals(0f, DetailPyramid.sampleBilinear(data, 2, 2, 0.25f, 0.25f), 1e-6f)
        // Geometric centre — the average of the four.
        assertEquals(1.5f, DetailPyramid.sampleBilinear(data, 2, 2, 0.5f, 0.5f), 1e-6f)
        // Outside the corner texel's centre — clamped at the margin, without extrapolating.
        assertEquals(0f, DetailPyramid.sampleBilinear(data, 2, 2, 0f, 0f), 1e-6f)
        assertEquals(3f, DetailPyramid.sampleBilinear(data, 2, 2, 1f, 1f), 1e-6f)
    }

    private fun naiveBlur(source: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val weights = PhotoEffects.halfGaussianKernel(sigma)
        val radius = weights.lastIndex
        val horizontal = FloatArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var total = source[y * width + x] * weights[0]
                for (offset in 1..radius) {
                    val left = source[y * width + (x - offset).coerceIn(0, width - 1)]
                    val right = source[y * width + (x + offset).coerceIn(0, width - 1)]
                    total += (left + right) * weights[offset]
                }
                horizontal[y * width + x] = total
            }
        }
        val vertical = FloatArray(source.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var total = horizontal[y * width + x] * weights[0]
                for (offset in 1..radius) {
                    val above = horizontal[(y - offset).coerceIn(0, height - 1) * width + x]
                    val below = horizontal[(y + offset).coerceIn(0, height - 1) * width + x]
                    total += (above + below) * weights[offset]
                }
                vertical[y * width + x] = total
            }
        }
        return vertical
    }
}
