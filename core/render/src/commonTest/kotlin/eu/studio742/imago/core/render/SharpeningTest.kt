package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RAW_INITIAL_SHARPEN_AMOUNT
import eu.studio742.imago.core.model.developedAsRaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpeningTest {
    private val width = 48
    private val height = 8

    /** A vertical step from [dark] to [light] at the middle, as a non-linear photo. */
    private fun step(parameters: RenderParameters, dark: Float = 0.3f, light: Float = 0.7f): FloatArray {
        val rgb = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val value = if (x < width / 2) dark else light
            for (c in 0..2) rgb[(y * width + x) * 3 + c] = value
        }
        return RecipePixels.renderFloat(FloatPixelBuffer(width, height, rgb, linear = false), parameters).rgb.toFloatArray()
    }

    private fun at(rgb: FloatArray, x: Int, y: Int = height / 2) = rgb[(y * width + x) * 3]

    private fun sharpen(amount: Float = 100f, radius: Float = 1f, detail: Float = 100f, masking: Float = 0f) =
        RenderParameters(sharpenAmount = amount, sharpenRadius = radius, sharpenDetail = detail, sharpenMasking = masking)

    @Test fun `an edge gets its overshoot on both sides and a flat area is left alone`() {
        val plain = step(RenderParameters())
        val sharp = step(sharpen())
        val edge = width / 2
        assertTrue("the light side overshoots", at(sharp, edge) > at(plain, edge) + 0.02f)
        assertTrue("the dark side undershoots", at(sharp, edge - 1) < at(plain, edge - 1) - 0.02f)
        assertEquals("far from the edge nothing moves", at(plain, 4), at(sharp, 4), 1e-4f)
        assertEquals(at(plain, width - 4), at(sharp, width - 4), 1e-4f)
    }

    @Test fun `amount is the strength and zero is off`() {
        val small = step(sharpen(amount = 30f))
        val big = step(sharpen(amount = 120f))
        val edge = width / 2
        assertTrue(at(big, edge) - at(step(RenderParameters()), edge) > 3f * (at(small, edge) - at(step(RenderParameters()), edge)))
        assertTrue(!sharpen(amount = 0f).needsSharpenStage)
        assertTrue(sharpen(amount = 1f).needsSharpenStage)
    }

    @Test fun `a larger radius spreads the overshoot further from the edge`() {
        val edge = width / 2
        val plain = step(RenderParameters())
        val narrow = step(sharpen(radius = 0.5f))
        val wide = step(sharpen(radius = 3f))
        val farNarrow = at(narrow, edge + 5) - at(plain, edge + 5)
        val farWide = at(wide, edge + 5) - at(plain, edge + 5)
        assertTrue("0.5 px has nothing five pixels away, 3 px still has", farWide > farNarrow + 0.01f)
    }

    @Test fun `a low Detail holds the halo of a strong edge down`() {
        val edge = width / 2
        val plain = step(RenderParameters(), dark = 0.1f, light = 0.9f)
        val loose = at(step(sharpen(detail = 100f), 0.1f, 0.9f), edge) - at(plain, edge)
        val tight = at(step(sharpen(detail = 0f), 0.1f, 0.9f), edge) - at(plain, edge)
        assertTrue("detail 100 overshoots more than detail 0", loose > tight * 2f)
    }

    @Test fun `masking spares what is not an edge`() {
        // A faint texture: steps of 0.01, an eighth of the strong edge's contrast.
        val faint = step(sharpen(masking = 0f), 0.50f, 0.51f)
        val masked = step(sharpen(masking = 100f), 0.50f, 0.51f)
        val edge = width / 2
        val plain = step(RenderParameters(), 0.50f, 0.51f)
        val moveFaint = Math.abs(at(faint, edge) - at(plain, edge))
        val moveMasked = Math.abs(at(masked, edge) - at(plain, edge))
        assertTrue("masking 100 leaves the faint step nearly alone", moveMasked < moveFaint * 0.5f)
        // A strong edge is still sharpened at full masking.
        val strong = step(sharpen(masking = 100f), 0.1f, 0.9f)
        assertTrue(at(strong, edge) > at(step(RenderParameters(), 0.1f, 0.9f), edge) + 0.02f)
    }

    @Test fun `the formulas of the shader and the export are one`() {
        // The limiter and the mask at their ends.
        assertEquals(0.04f, Sharpening.limit(0f), 1e-6f)
        assertEquals(1f, Sharpening.limit(100f), 1e-6f)
        assertEquals(1f, Sharpening.mask(0f, 0f), 0f)
        assertEquals(0f, Sharpening.mask(0f, 100f), 1e-6f)
        assertEquals(1f, Sharpening.mask(1f, 100f), 1e-6f)
        // A pixel on its own blur has no signal.
        assertEquals(0f, Sharpening.delta(0.4f, 0.4f, 0.3f, 100f, 50f, 0f), 0f)
        for (value in listOf(-.5f, -.1f, 0.1f, .5f)) {
            val delta = Sharpening.delta(value, 0f, 0f, 100f, 50f, 0f)
            assertTrue("same sign as the signal and never bigger", delta * value > 0f && Math.abs(delta) <= Math.abs(value))
        }
    }

    @Test fun `only process 21 reads the sharpening, and an off sharpening stays neutral`() {
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "",
            detail = Detail(sharpenAmount = 60f, sharpenRadius = 2f, sharpenDetail = 50f, sharpenMasking = 30f))
        val now = recipe.toRenderParameters()
        assertEquals(60f, now.sharpenAmount, 0f)
        assertEquals(2f, now.sharpenRadius, 0f)
        assertEquals(0f, recipe.copy(processVersion = 20).toRenderParameters().sharpenAmount, 0f)
        val off = recipe.copy(detail = Detail(sharpenAmount = 0f, sharpenRadius = 3f, sharpenMasking = 80f, noiseReductionColor = 0f))
        assertEquals("the radius of an off sharpening is not a change", RenderParameters().sharpenRadius, off.toRenderParameters().sharpenRadius, 0f)
        assertTrue(off.toRenderParameters().isNeutral)
    }

    @Test fun `a RAW starts sharpened once and a JPEG never does`() {
        val fresh = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "")
        assertEquals(0f, fresh.detail.sharpenAmount, 0f)
        assertEquals(RAW_INITIAL_SHARPEN_AMOUNT, fresh.developedAsRaw().detail.sharpenAmount, 0f)
        // What the person set is not overwritten, nor is a development that was already made.
        val chosen = fresh.copy(detail = Detail(sharpenAmount = 10f))
        assertEquals(10f, chosen.developedAsRaw().detail.sharpenAmount, 0f)
        assertEquals(RAW_INITIAL_SHARPEN_AMOUNT, fresh.developedAsRaw().developedAsRaw().detail.sharpenAmount, 0f)
        val zeroed = fresh.developedAsRaw().copy(detail = Detail(sharpenAmount = 0f))
        assertEquals("a person who turned it off keeps it off", 0f, zeroed.developedAsRaw().detail.sharpenAmount, 0f)
    }
}
