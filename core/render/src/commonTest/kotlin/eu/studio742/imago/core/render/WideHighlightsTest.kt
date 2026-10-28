package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideHighlightsTest {
    private fun shift(value: Float, amount: Float, wide: Boolean) =
        locallyAdaptedHighlights(value, NO_LOCAL_BASE, amount, wide = wide) - value

    @Test fun `process 21 moves a light midtone far more than the first band did`() {
        // The first band moved 0.5 by 0.04 and 0.6 by 0.10 at -100: the slider seemed to do nothing there.
        assertEquals(-0.043f, shift(0.5f, -1f, wide = false), 0.01f)
        assertEquals(-0.17f, shift(0.5f, -1f, wide = true), 0.02f)
        assertEquals(-0.34f, shift(0.7f, -1f, wide = true), 0.02f)
        assertTrue(shift(0.9f, -1f, wide = true) < -0.4f)
        for (value in listOf(0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f)) {
            assertTrue("recovers more at $value", shift(value, -1f, wide = true) < shift(value, -1f, wide = false) - 0.02f)
        }
        for (value in listOf(0.4f, 0.5f, 0.6f, 0.7f)) {
            assertTrue("lifts more at $value", shift(value, 1f, wide = true) > shift(value, 1f, wide = false) + 0.01f)
        }
    }

    @Test fun `the wide band keeps what the first one proved`() {
        for (amount in listOf(-1f, -.5f, .5f, 1f)) {
            assertEquals(1f, locallyAdaptedHighlights(1f, NO_LOCAL_BASE, amount, wide = true), 1e-6f)
            assertEquals(WIDE_HIGHLIGHT_PIVOT, locallyAdaptedHighlights(WIDE_HIGHLIGHT_PIVOT, NO_LOCAL_BASE, amount, wide = true), 1e-6f)
            assertEquals("below the band nothing moves", 0.1f, locallyAdaptedHighlights(0.1f, NO_LOCAL_BASE, amount, wide = true), 0f)
            var previous = -1f
            for (step in 0..400) {
                val out = locallyAdaptedHighlights(step / 400f, NO_LOCAL_BASE, amount, wide = true)
                assertTrue("rises at ${step / 400f} / $amount", out >= previous)
                previous = out
            }
        }
        assertEquals("a dark point in a bright neighbourhood stays", 0.15f, locallyAdaptedHighlights(0.15f, 0.95f, -1f, wide = true), 0f)
    }

    @Test fun `shadows keep the first band`() {
        // Shadows are the mirror of the first Highlights, and process 21 did not touch them.
        val before = locallyAdaptedShadows(0.4f, NO_LOCAL_BASE, 1f)
        assertEquals(1f - locallyAdaptedHighlights(0.6f, NO_LOCAL_BASE, -1f), before, 1e-6f)
    }

    @Test fun `only a recipe from process 21 gets the wide band`() {
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "", tone = Tone(highlights = -100f))
        assertTrue(recipe.toRenderParameters().wideHighlights)
        assertFalse("what was edited before keeps its band", recipe.copy(processVersion = 20).toRenderParameters().wideHighlights)
        assertFalse(recipe.copy(processVersion = 8).toRenderParameters().wideHighlights)
        // The band is not a choice of the photo: neither is neutral changed by it.
        assertTrue(RenderParameters(wideHighlights = true).isNeutral)
    }

    @Test fun `a JPEG at -100 gets a visibly darker light tone in the render`() {
        fun render(wide: Boolean) = RecipePixels.renderFloat(
            FloatPixelBuffer(1, 1, floatArrayOf(.7f, .7f, .7f), linear = false),
            RenderParameters(highlights = -100f, wideHighlights = wide),
        ).rgb[0]
        assertTrue(render(true) < render(false) - 0.08f)
    }
}
