package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RAW_NOISE_REDUCTION_PROCESS_VERSION
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawNoiseReductionTest {
    private val width = 120
    private val height = 96

    /** A deterministic Gaussian-like noise of unit spread: the same picture on every run. */
    private class Noise(private var state: Long = 12345L) {
        private fun uniform(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 11) and 0xFFFFFF).toFloat() / 0xFFFFFF
        }
        fun next(): Float = (uniform() + uniform() + uniform() + uniform() - 2f) * sqrt(3f)
    }

    /** A linear development of [base] colours with sensor-like noise: its spread grows with the light. */
    private fun developed(
        base: (Int, Int) -> FloatArray,
        luminanceNoise: Float,
        colourNoise: Float = luminanceNoise,
        seed: Long = 12345L,
    ): FloatPixelBuffer {
        val noise = Noise(seed)
        val rgb = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val colour = base(x, y)
            val shared = noise.next() * luminanceNoise
            for (c in 0..2) {
                val spread = sqrt(colour[c].coerceAtLeast(1e-4f) / 0.1f)
                rgb[(y * width + x) * 3 + c] = colour[c] + (shared + noise.next() * colourNoise) * spread
            }
        }
        return FloatPixelBuffer(width, height, rgb, linear = true)
    }

    private fun flat(red: Float = 0.1f, green: Float = 0.1f, blue: Float = 0.1f) = { _: Int, _: Int -> floatArrayOf(red, green, blue) }

    private fun parameters(luminance: Float = 0f, colour: Float = 0f, detail: Float = 50f, colourDetail: Float = 50f) = RenderParameters(
        noiseLuminance = luminance, noiseLuminanceDetail = detail,
        noiseColor = colour, noiseColorDetail = colourDetail,
        noiseModel = RawNoiseReduction.RAW_NOISE_MODEL,
    )

    private fun reduced(image: FloatPixelBuffer, parameters: RenderParameters): FloatPixelBuffer =
        image.copy().also { RawNoiseReduction.applyInPlace(it, parameters, NoiseProfile.measure(image)) }

    private val inside = (16 until height - 16).flatMap { y -> (16 until width - 16).map { x -> y * width + x } }

    private fun deviation(values: List<Float>): Float {
        val mean = values.average().toFloat()
        return sqrt(values.map { (it - mean) * (it - mean) }.average().toFloat())
    }

    private fun luminance(image: FloatPixelBuffer, i: Int) = PhotoEffects.luminance(image.rgb[i * 3], image.rgb[i * 3 + 1], image.rgb[i * 3 + 2])
    private fun redness(image: FloatPixelBuffer, i: Int) = image.rgb[i * 3] - luminance(image, i)
    private fun blueness(image: FloatPixelBuffer, i: Int) = image.rgb[i * 3 + 2] - luminance(image, i)

    @Test fun `the profile finds the noise the photo has, and more where it has more`() {
        val quiet = NoiseProfile.measure(developed(flat(), 0.002f))
        val noisy = NoiseProfile.measure(developed(flat(), 0.02f))
        val level = noisy.top / 2f
        assertTrue("ten times the noise is measured as several times more: ${quiet.band(0, level)} -> ${noisy.band(0, level)}",
            noisy.band(0, quiet.top / 2f) > 5f * quiet.band(0, quiet.top / 2f))
        assertTrue(noisy.chroma(level) > 5f * quiet.chroma(quiet.top / 2f))
    }

    @Test fun `luminance noise falls with the slider, at any level of noise`() {
        for (amount in listOf(0.005f, 0.03f)) {
            val image = developed(flat(), amount, colourNoise = 0f)
            val before = deviation(inside.map { luminance(image, it) })
            val some = reduced(image, parameters(luminance = 25f)).let { out -> deviation(inside.map { luminance(out, it) }) }
            val lots = reduced(image, parameters(luminance = 80f)).let { out -> deviation(inside.map { luminance(out, it) }) }
            assertTrue("25 takes noise out ($amount): $before -> $some", some < before * 0.8f)
            assertTrue("80 takes much more ($amount): $some -> $lots", lots < some * 0.7f && lots < before * 0.4f)
        }
    }

    @Test fun `a strong edge is not blurred away`() {
        val image = developed({ x, _ -> if (x < width / 2) floatArrayOf(0.02f, 0.02f, 0.02f) else floatArrayOf(0.3f, 0.3f, 0.3f) }, 0.004f)
        val out = reduced(image, parameters(luminance = 80f))
        val row = height / 2
        val left = (0 until 6).map { luminance(out, row * width + width / 2 - 2 - it) }.average()
        val right = (0 until 6).map { luminance(out, row * width + width / 2 + 1 + it) }.average()
        assertTrue("the step is still there: $left -> $right", right - left > 0.25)
        // And it is a step, not a ramp: two pixels either side of the edge are already on their own level.
        assertTrue(luminance(out, row * width + width / 2 + 2) > 0.25f)
        assertTrue(luminance(out, row * width + width / 2 - 3) < 0.06f)
    }

    @Test fun `colour noise goes and the mean colour stays`() {
        val image = developed(flat(0.12f, 0.1f, 0.08f), 0.002f, colourNoise = 0.02f)
        val out = reduced(image, parameters(colour = 100f))
        val before = deviation(inside.map { redness(image, it) })
        val after = deviation(inside.map { redness(out, it) })
        assertTrue("the colour noise goes: $before -> $after", after < before * 0.15f)
        val meanBefore = inside.map { redness(image, it) }.average()
        val meanAfter = inside.map { redness(out, it) }.average()
        assertEquals("the warm grey stays warm", meanBefore, meanAfter, abs(meanBefore) * 0.03)
        assertEquals(inside.map { blueness(image, it) }.average(), inside.map { blueness(out, it) }.average(), 0.0015)
    }

    @Test fun `a colour edge with its light stays, a blotch without one goes`() {
        // A red half against a darker blue half: colour and light change together.
        val image = developed({ x, _ -> if (x < width / 2) floatArrayOf(0.2f, 0.05f, 0.05f) else floatArrayOf(0.03f, 0.03f, 0.12f) }, 0.002f, colourNoise = 0.01f)
        val out = reduced(image, parameters(colour = 100f))
        val row = height / 2
        val red = (0 until 4).map { redness(out, row * width + width / 2 - 3 - it) }.average()
        val blue = (0 until 4).map { redness(out, row * width + width / 2 + 2 + it) }.average()
        assertTrue("the red side is still red next to the edge: $red", red > 0.08)
        assertTrue("the blue side has no red bled into it: $blue", blue < 0.0)
        // A purple blotch with the light of the grey around it: false colour.
        val blotch = developed({ x, y ->
            if (abs(x - width / 2) < 3 && abs(y - height / 2) < 3) floatArrayOf(0.14f, 0.08f, 0.14f) else floatArrayOf(0.1f, 0.1f, 0.1f)
        }, 0.002f, colourNoise = 0.01f)
        val cleaned = reduced(blotch, parameters(colour = 100f))
        val centre = height / 2 * width + width / 2
        assertTrue("the blotch fades: ${redness(blotch, centre)} -> ${redness(cleaned, centre)}", abs(redness(cleaned, centre)) < abs(redness(blotch, centre)) * 0.5f)
    }

    @Test fun `an impulse in a flat place goes, only with the luminance on`() {
        val image = developed(flat(), 0.002f, colourNoise = 0.0005f)
        val spot = height / 2 * width + width / 2
        for (c in 0..2) image.rgb[spot * 3 + c] = 0.6f
        val kept = reduced(image, parameters(colour = 25f))
        assertTrue("without the luminance an impulse is the photo's", luminance(kept, spot) > 0.4f)
        val held = reduced(image, parameters(luminance = 10f))
        assertTrue("held with it: ${luminance(held, spot)}", luminance(held, spot) < 0.15f)
    }

    @Test fun `the strips an export is developed in do not show`() {
        val image = developed(flat(0.12f, 0.1f, 0.08f), 0.01f)
        val profile = NoiseProfile.measure(image)
        val settings = parameters(luminance = 60f, colour = 80f)
        val whole = image.copy()
        val strips = image.copy()
        val saved = RawNoiseReduction.stripRows
        try {
            RawNoiseReduction.stripRows = 1000
            RawNoiseReduction.applyInPlace(whole, settings, profile)
            RawNoiseReduction.stripRows = 17
            RawNoiseReduction.applyInPlace(strips, settings, profile)
        } finally {
            RawNoiseReduction.stripRows = saved
        }
        assertArrayEquals(whole.rgb.toFloatArray(), strips.rgb.toFloatArray(), 1e-6f)
    }

    @Test fun `process 24 reduces a RAW before the tone, an older recipe and a JPEG keep step 10`() {
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "",
            detail = Detail(noiseReductionLuminance = 40f, noiseReductionColor = 25f))
        assertEquals(RawNoiseReduction.RAW_NOISE_MODEL, recipe.toRenderParameters().noiseModel)
        assertEquals(1, recipe.copy(processVersion = RAW_NOISE_REDUCTION_PROCESS_VERSION - 1).toRenderParameters().noiseModel)
        assertTrue(RawNoiseReduction.isActive(recipe.toRenderParameters()))
        assertFalse(RawNoiseReduction.isActive(recipe.copy(processVersion = RAW_NOISE_REDUCTION_PROCESS_VERSION - 1).toRenderParameters()))
        assertFalse(RawNoiseReduction.isActive(parameters()))
        // A JPEG's float source is not linear: the noise is left to step 10, after the tone.
        val noisy = developed(flat(), 0.02f)
        val jpeg = FloatPixelBuffer(width, height, noisy.rgb.toFloatArray(), linear = false)
        val viaStep10 = RecipePixels.renderFloat(jpeg, parameters(luminance = 50f)).rgb.toFloatArray()
        val withoutAny = RecipePixels.renderFloat(jpeg.copy(), RenderParameters(noiseLuminance = 50f)).rgb.toFloatArray()
        assertArrayEquals(withoutAny, viaStep10, 0f)
    }

    @Test fun `a preview filters what an export does, at its own scale`() {
        // The same photo at half the size: the bands are in pixels of the original, so the reduction a
        // preview does is in proportion to the export's, not twice as wide.
        assertEquals(1f, RawNoiseReduction.sigma(0, 1f), 0f)
        assertEquals(0.5f, RawNoiseReduction.sigma(0, 0.5f), 0f)
        assertEquals(1, RawNoiseReduction.spacing(0, 0.5f))
        assertEquals(6, RawNoiseReduction.spacing(3, 1f))
        assertEquals(8, RawNoiseReduction.reduction(15f))
        assertEquals(2, RawNoiseReduction.reduction(5f))
        assertEquals(1, RawNoiseReduction.reduction(2f))
    }

    @Test fun `white noise keeps in each band what the kernels say`() {
        // Σ(A − B)² for Gaussians of 0, 1, 2, 4 and 8: what the profile scales the coarse bands by.
        assertEquals(0.872f, RawNoiseReduction.whiteBand(0f, 1f), 0.01f)
        assertEquals(0.189f, RawNoiseReduction.whiteBand(1f, 2f), 0.01f)
        assertEquals(0.095f, RawNoiseReduction.whiteBand(2f, 4f), 0.01f)
        assertEquals(0.047f, RawNoiseReduction.whiteBand(4f, 8f), 0.005f)
    }
}
