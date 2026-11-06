package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.RawDevelopment
import eu.studio742.imago.core.model.Tone
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** A RAW's render is split across cores: the strips must not show, not in a single bit. */
class ParallelRenderTest {
    private val width = 200
    private val height = 320

    private fun scene(): FloatPixelBuffer {
        var state = 99L
        fun noise(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 40) and 0xFFFF).toFloat() / 0xFFFF - 0.5f
        }
        val rgb = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val light = 0.05f + 0.5f * x / width + (if ((y / 7 + x / 11) % 2 == 0) 0.2f else 0f)
            for (c in 0..2) rgb[(y * width + x) * 3 + c] = light * (1f + 0.2f * c) + noise() * 0.02f
        }
        return FloatPixelBuffer(width, height, rgb, linear = true)
    }

    private fun recipe() = EditRecipe(
        assetId = "p", originalChecksum = "", createdAt = "", updatedAt = "",
        tone = Tone(exposure = 0.4f, contrast = 20f, shadows = 30f, highlights = -30f),
        presence = Presence(texture = 40f, clarity = 30f, dehaze = 20f, vibrance = 20f, saturation = 10f),
        detail = Detail(sharpenAmount = 80f, sharpenMasking = 30f, noiseReductionLuminance = 30f, noiseReductionColor = 40f),
        rawDevelopment = RawDevelopment(rendering = 3),
    )

    @Test fun `a RAW rendered in strips is the same as in one pass, bit for bit`() {
        val parameters = recipe().toRenderParameters()
        val strips = RecipePixels.renderFloat(scene(), parameters).rgb.toFloatArray()
        val whole = try {
            parallelRows = false
            RecipePixels.renderFloat(scene(), parameters).rgb.toFloatArray()
        } finally {
            parallelRows = true
        }
        assertArrayEquals(whole, strips, 0f)
    }

    @Test fun `the blur in strips of columns is the blur of the whole plane`() {
        val plane = FloatArray(301 * 77) { ((it * 2654435761L) ushr 7 and 1023).toFloat() / 1023f }
        val blurred = plane.copyOf().also { DetailPyramid.blurInPlace(it, 301, 77, 2.5f) }
        // The same Gaussian written out: rows, then columns, edges replicated.
        val weights = PhotoEffects.halfGaussianKernel(2.5f)
        val rows = FloatArray(plane.size)
        for (y in 0 until 77) for (x in 0 until 301) {
            var total = plane[y * 301 + x] * weights[0]
            for (o in 1..weights.lastIndex) total += (plane[y * 301 + maxOf(x - o, 0)] + plane[y * 301 + minOf(x + o, 300)]) * weights[o]
            rows[y * 301 + x] = total
        }
        val expected = FloatArray(plane.size)
        for (y in 0 until 77) for (x in 0 until 301) {
            var total = rows[y * 301 + x] * weights[0]
            for (o in 1..weights.lastIndex) total += (rows[maxOf(y - o, 0) * 301 + x] + rows[minOf(y + o, 76) * 301 + x]) * weights[o]
            expected[y * 301 + x] = total
        }
        assertArrayEquals(expected, blurred, 0f)
    }
}
