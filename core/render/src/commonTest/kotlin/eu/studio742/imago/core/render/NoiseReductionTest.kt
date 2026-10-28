package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseReductionTest {
    private val width = 64
    private val height = 48

    /** A deterministic noise in [-1, 1]: the same picture on every run. */
    private class Noise(private var state: Long = 12345L) {
        fun next(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) % 20001L) / 10000f - 1f
        }
    }

    private fun render(rgb: FloatArray, parameters: RenderParameters) =
        RecipePixels.renderFloat(FloatPixelBuffer(width, height, rgb.copyOf(), linear = false), parameters).rgb.toFloatArray()

    private fun luminance(rgb: FloatArray, i: Int) = PhotoEffects.luminance(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2])

    private fun deviation(values: List<Float>): Float {
        val mean = values.average().toFloat()
        return sqrt(values.map { (it - mean) * (it - mean) }.average().toFloat())
    }

    private fun noisyGrey(amplitude: Float): FloatArray {
        val noise = Noise()
        return FloatArray(width * height * 3).also { rgb ->
            for (i in 0 until width * height) {
                val v = 0.5f + amplitude * noise.next()
                rgb[i * 3] = v; rgb[i * 3 + 1] = v; rgb[i * 3 + 2] = v
            }
        }
    }

    private fun luminanceOptions(amount: Float, detail: Float = 50f, contrast: Float = 0f) =
        RenderParameters(noiseLuminance = amount, noiseLuminanceDetail = detail, noiseContrast = contrast)

    private fun colourOptions(amount: Float, detail: Float = 50f, smoothness: Float = 50f) =
        RenderParameters(noiseColor = amount, noiseColorDetail = detail, noiseColorSmoothness = smoothness)

    @Test fun `luminance noise falls with the slider`() {
        val noisy = noisyGrey(0.04f)
        val inside = (8 until height - 8).flatMap { y -> (8 until width - 8).map { x -> y * width + x } }
        val before = deviation(inside.map { luminance(noisy, it) })
        val some = deviation(render(noisy, luminanceOptions(25f)).let { out -> inside.map { luminance(out, it) } })
        val lots = deviation(render(noisy, luminanceOptions(80f)).let { out -> inside.map { luminance(out, it) } })
        assertTrue("25 takes noise out: $before -> $some", some < before * 0.8f)
        assertTrue("80 takes more: $some -> $lots", lots < some * 0.8f && lots < before * 0.45f)
    }

    @Test fun `a strong edge is not blurred away`() {
        val rgb = FloatArray(width * height * 3) { i -> if ((i / 3) % width < width / 2) 0.2f else 0.8f }
        val out = render(rgb, luminanceOptions(70f))
        val row = height / 2
        val dark = luminance(out, row * width + width / 2 - 1)
        val light = luminance(out, row * width + width / 2)
        assertTrue("the step is still ${light - dark}", light - dark > 0.5f)
    }

    @Test fun `Detail keeps the fine texture that a low one calls noise`() {
        // A fine texture of ±0.03, about what the noise of this slider's strength is: only Detail tells them apart.
        val noise = Noise(7L)
        val rgb = FloatArray(width * height * 3).also { rgb ->
            for (i in 0 until width * height) {
                val v = 0.5f + 0.03f * noise.next().let { if (it > 0f) 1f else -1f }
                rgb[i * 3] = v; rgb[i * 3 + 1] = v; rgb[i * 3 + 2] = v
            }
        }
        val inside = (8 until height - 8).flatMap { y -> (8 until width - 8).map { x -> y * width + x } }
        fun kept(detail: Float) = deviation(render(rgb, luminanceOptions(50f, detail = detail)).let { out -> inside.map { luminance(out, it) } })
        assertTrue("detail 100 keeps more than detail 0: ${kept(100f)} vs ${kept(0f)}", kept(100f) > kept(0f) * 1.5f)
    }

    @Test fun `colour noise falls and a colour edge stays`() {
        val noise = Noise(99L)
        val rgb = FloatArray(width * height * 3).also { rgb ->
            for (i in 0 until width * height) {
                // Same luminance everywhere; the red and the blue wander against each other.
                val shift = 0.05f * noise.next()
                rgb[i * 3] = 0.5f + shift; rgb[i * 3 + 1] = 0.5f; rgb[i * 3 + 2] = 0.5f - shift * 2f
            }
        }
        val inside = (8 until height - 8).flatMap { y -> (8 until width - 8).map { x -> y * width + x } }
        fun spread(out: FloatArray) = deviation(inside.map { out[it * 3] - out[it * 3 + 2] })
        val before = spread(rgb)
        val after = spread(render(rgb, colourOptions(100f)))
        assertTrue("colour spread $before -> $after", after < before * 0.5f)

        val edge = FloatArray(width * height * 3) { i ->
            val left = (i / 3) % width < width / 2
            when (i % 3) { 0 -> if (left) 0.75f else 0.25f; 1 -> if (left) 0.25f else 0.75f; else -> 0.4f }
        }
        val out = render(edge, colourOptions(100f, detail = 100f))
        val row = height / 2
        val leftRed = out[(row * width + width / 2 - 1) * 3]
        val rightRed = out[(row * width + width / 2) * 3]
        assertTrue("the red still goes from ${leftRed} to ${rightRed}", leftRed - rightRed > 0.3f)
    }

    @Test fun `off is off, and only process 21 reads the recipe`() {
        val noisy = noisyGrey(0.04f)
        val untouched = render(noisy, RenderParameters())
        assertTrue(untouched.indices.all { abs(untouched[it] - noisy[it]) < 0.01f })
        assertTrue(!RenderParameters().needsNoiseStage)
        assertTrue(RenderParameters(noiseColor = 25f).needsNoiseStage)
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "",
            detail = Detail(noiseReductionLuminance = 40f, noiseReductionColor = 25f))
        assertEquals(40f, recipe.toRenderParameters().noiseLuminance, 0f)
        assertEquals(25f, recipe.toRenderParameters().noiseColor, 0f)
        assertEquals(0f, recipe.copy(processVersion = 20).toRenderParameters().noiseLuminance, 0f)
        assertEquals(0f, recipe.copy(processVersion = 20).toRenderParameters().noiseColor, 0f)
        // What goes with an amount at 0 is not a change.
        val off = recipe.copy(detail = Detail(noiseReductionLuminance = 0f, noiseReductionContrast = 80f,
            noiseReductionColor = 0f, noiseReductionColorSmoothness = 100f))
        assertTrue(off.toRenderParameters().isNeutral)
    }

    @Test fun `the formulas of the shader and the export are one`() {
        val shader = PhotoShaders.NOISE
        listOf(
            "float threshold = (0.003 + 0.08 * a) * (1.3 - 0.8 * d);" to "the luminance threshold",
            "float p = 2.0 + 2.0 * clamp(uLumaContrast / 100.0, 0.0, 1.0);" to "the knee's exponent",
            "cleaned = blurred + residual * (size / (size + pow(threshold, p)));" to "the share of the residual kept",
            "float knee = 0.02 + 0.30 * (1.0 - 0.85 * clamp(uColorDetail / 100.0, 0.0, 1.0));" to "the colour edge",
            "min(1.0, uColorAmount / 100.0 * 1.6) * (1.0 - smoothstep(0.0, knee, length(apart)))" to "the colour weight",
        ).forEach { (fragment, what) ->
            assertTrue("The noise shader should contain $what — \"$fragment\".", shader.contains(fragment))
        }
        assertEquals(0.003f * 1.3f * 1f, NoiseReduction.lumaThreshold(0f, 0f), 1e-6f)
        assertEquals(0.083f * 0.5f, NoiseReduction.lumaThreshold(100f, 100f), 1e-6f)
        assertEquals(0.32f, NoiseReduction.chromaKnee(0f), 1e-6f)
        assertEquals(2f, NoiseReduction.chromaSigma(0f), 0f)
        assertEquals(12f, NoiseReduction.chromaSigma(100f), 0f)
        assertEquals(1f, NoiseReduction.lumaKeep(10f, 0.01f, 0f), 1e-3f)
        assertEquals(0f, NoiseReduction.lumaKeep(0f, 0.01f, 100f), 0f)
        assertTrue(abs(NoiseReduction.lumaKeep(0.01f, 0.01f, 0f) - 0.5f) < 1e-5f)
    }
}

class IdleColourNoiseTest {
    private fun recipe(vararg edits: (EditRecipe) -> EditRecipe) =
        edits.fold(EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "")) { r, edit -> edit(r) }

    @Test fun `the colour reduction a recipe starts with wakes with the first colour, tone or detail edit`() {
        // On its own, or with only geometry, it stays asleep: the photo keeps its pixels.
        assertEquals(0f, recipe().toRenderParameters().noiseColor, 0f)
        val cropped = recipe({ it.copy(geometry = it.geometry.copy(cropRect = eu.studio742.imago.core.model.CropRect(0.1f, 0.1f, 0.8f, 0.8f))) })
        assertEquals(0f, cropped.toRenderParameters().noiseColor, 0f)
        assertTrue(cropped.toRenderParameters().isColorNeutral)
        // With tone, colour or detail in the edit it acts.
        val toned = recipe({ it.copy(tone = it.tone.copy(exposure = 0.3f)) })
        assertEquals(25f, toned.toRenderParameters().noiseColor, 0f)
        // And a person who set it themselves is heard, with nothing else touched.
        val chosen = recipe({ it.copy(detail = it.detail.copy(noiseReductionColor = 60f)) })
        assertEquals(60f, chosen.toRenderParameters().noiseColor, 0f)
        assertTrue(chosen.changesTheImage())
    }
}
