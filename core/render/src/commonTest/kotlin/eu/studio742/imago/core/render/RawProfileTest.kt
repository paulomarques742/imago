package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME
import eu.studio742.imago.core.model.RAW_RENDERING_LINEAR
import eu.studio742.imago.core.model.RAW_RENDERING_PROFILED
import eu.studio742.imago.core.model.RawDevelopment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawProfileTest {
    @Test fun `black stays black, white stays white, and the cameras' middle grey lands near the middle`() {
        assertEquals(0f, RawProfile.curve(0f), 0f)
        assertEquals(0f, RawProfile.curve(-0.2f), 0f)
        assertEquals(1f, RawProfile.curve(1f), 1e-6f)
        assertEquals(0.494f, RawProfile.curve(0.09f), 0.002f)
        // Brighter than the bare sRGB transfer in the middle, which is what left a RAW dark.
        assertTrue(RawProfile.curve(0.09f) > encodeSrgb(0.09f) + 0.1f)
    }

    @Test fun `the curve rises everywhere and goes on past white with the slope it has there`() {
        var previous = -1f
        for (step in 0..400) {
            val value = RawProfile.curve(step / 200f)
            assertTrue("rises at ${step / 200f}", value > previous)
            previous = value
        }
        val below = (RawProfile.curve(1f) - RawProfile.curve(0.999f)) / 0.001f
        val above = (RawProfile.curve(1.001f) - RawProfile.curve(1f)) / 0.001f
        assertEquals(below, above, 0.001f)
        assertEquals(RawProfile.KNEE / (1f + RawProfile.KNEE), above, 0.001f)
    }

    @Test fun `colour keeps its hue, monochrome is the toned luminance`() {
        val out = FloatArray(3)
        RawProfile.toDisplay(0.30f, 0.12f, 0.06f, RawProfile.COLOR, out)
        assertEquals(RawProfile.curve(0.30f), out[0], 1e-6f)
        assertEquals(RawProfile.curve(0.06f), out[2], 1e-6f)
        // The middle channel sits where it was between the other two.
        assertEquals((0.12f - 0.06f) / (0.30f - 0.06f), (out[1] - out[2]) / (out[0] - out[2]), 1e-5f)
        RawProfile.toDisplay(0.30f, 0.12f, 0.06f, RawProfile.MONOCHROME, out)
        val grey = RawProfile.curve(0.30f * 0.2126f + 0.12f * 0.7152f + 0.06f * 0.0722f)
        out.forEach { assertEquals(grey, it, 1e-6f) }
    }

    @Test fun `a linear source leaves through the profile, a JPEG and process 19 through sRGB`() {
        fun develop(profile: Int, linear: Boolean = true) =
            RecipePixels.renderFloat(FloatPixelBuffer(1, 1, floatArrayOf(.09f, .09f, .09f), linear = linear), RenderParameters(rawProfile = profile)).rgb[0]
        assertEquals(RawProfile.curve(.09f), develop(RawProfile.COLOR), 0.002f)
        assertEquals(RawProfile.curve(.09f), develop(RawProfile.MONOCHROME), 0.002f)
        assertEquals(encodeSrgb(.09f), develop(RawProfile.NONE), 0.002f)
        assertEquals("a JPEG has no profile", .09f, develop(RawProfile.COLOR, linear = false), 0.002f)
    }

    @Test fun `the recipe chooses the profile only on the rendering that has them`() {
        val recipe = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "")
        assertEquals(RawProfile.COLOR, recipe.toRenderParameters().rawProfile)
        assertEquals(RawProfile.MONOCHROME, recipe.copy(profile = RAW_PROFILE_MONOCHROME).toRenderParameters().rawProfile)
        val edited19 = recipe.copy(rawDevelopment = RawDevelopment(rendering = RAW_RENDERING_LINEAR), profile = RAW_PROFILE_MONOCHROME)
        assertEquals(RawProfile.NONE, edited19.toRenderParameters().rawProfile)
        assertEquals(RawProfile.NONE, recipe.copy(processVersion = 19).toRenderParameters().rawProfile)
        // A preset from process 12 shown on a photo of the current rendering, as applying it would show it.
        assertEquals(RawProfile.COLOR, recipe.copy(processVersion = 12).toRenderParameters(rawRendering = RAW_RENDERING_PROFILED).rawProfile)
        assertTrue(CURRENT_PROCESS_VERSION >= 20)
        assertEquals(RawProfile.HEADROOM_CEILING, recipe.toRenderParameters().highlightCeiling, 0f)
        assertEquals(1f, recipe.toRenderParameters(rawRendering = RAW_RENDERING_PROFILED).highlightCeiling, 0f)
        assertTrue(RenderParameters(highlightCeiling = RawProfile.HEADROOM_CEILING).isNeutral)
    }

    @Test fun `before shows the photo developed, without the choices made on it`() {
        assertEquals(RenderParameters(rawProfile = RawProfile.COLOR), RenderParameters(exposure = 1f, rawProfile = RawProfile.MONOCHROME).original)
        assertEquals(RenderParameters(), RenderParameters(exposure = 1f).original)
        assertTrue(RenderParameters(rawProfile = RawProfile.COLOR).isNeutral)
    }

    @Test fun `rendering 3 pulls the light above white back with Highlights, and the others clip as they did`() {
        // Twice the sensor's white: a sky the camera recorded and the display band of rendering 2 cut.
        fun develop(ceiling: Float, highlights: Float, linear: Boolean = true, value: Float = 2f) = RecipePixels.renderFloat(
            FloatPixelBuffer(1, 1, floatArrayOf(value, value, value), linear = linear),
            RenderParameters(rawProfile = RawProfile.COLOR, highlights = highlights, highlightCeiling = ceiling),
        ).rgb[0]
        val ceiling = RawProfile.HEADROOM_CEILING
        assertEquals("nothing moves at zero", develop(1f, 0f), develop(ceiling, 0f), 1e-6f)
        assertEquals("rendering 2 cuts it", 1f, develop(1f, -100f), 1e-3f)
        assertTrue("rendering 3 recovers it", develop(ceiling, -100f) < 0.9f)
        assertTrue("and only as far as asked", develop(ceiling, -100f) < develop(ceiling, -50f))
        assertTrue("a pixel at the sensor's top stays clipped", develop(ceiling, -100f, value = 20f) > 0.99f)
        assertEquals("a JPEG has no light above white", develop(1f, -100f, linear = false, value = .8f), develop(ceiling, -100f, linear = false, value = .8f), 1e-6f)
    }

    @Test fun `the highlights curve with a ceiling is still monotonic and fixed at both ends of its band`() {
        val ceiling = RawProfile.HEADROOM_CEILING
        for (amount in listOf(-1f, -.5f, .5f, 1f)) {
            var previous = -1f
            for (step in 0..200) {
                val value = ceiling * step / 200f
                val out = locallyAdaptedHighlights(value, NO_LOCAL_BASE, amount, ceiling)
                assertTrue("rises at $value / $amount", out >= previous)
                previous = out
            }
            assertEquals(ceiling, locallyAdaptedHighlights(ceiling, NO_LOCAL_BASE, amount, ceiling), 1e-6f)
            assertEquals(.35f, locallyAdaptedHighlights(.35f, NO_LOCAL_BASE, amount, ceiling), 1e-6f)
            assertEquals(ceiling + .5f, locallyAdaptedHighlights(ceiling + .5f, NO_LOCAL_BASE, amount, ceiling), 0f)
        }
    }

    @Test fun `the development's exposure lies under the slider and stays in before`() {
        val recipe = EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "",
            rawDevelopment = RawDevelopment(rendering = RAW_RENDERING_PROFILED, exposure = -0.75f))
        val parameters = recipe.toRenderParameters()
        assertEquals(-0.75f, parameters.rawExposure, 0f)
        assertEquals(0f, parameters.exposure, 0f)
        assertEquals(-0.75f, parameters.original.rawExposure, 0f)
        assertTrue(RenderParameters(rawExposure = -0.75f).isNeutral)
        assertEquals(0f, recipe.copy(rawDevelopment = RawDevelopment(rendering = RAW_RENDERING_LINEAR, exposure = -0.75f)).toRenderParameters().rawExposure, 0f)
        fun develop(params: RenderParameters) = RecipePixels.renderFloat(FloatPixelBuffer(1, 1, floatArrayOf(.2f, .1f, .05f)), params).rgb.toFloatArray()
        org.junit.Assert.assertArrayEquals(
            develop(RenderParameters(rawProfile = RawProfile.COLOR, exposure = -0.75f)),
            develop(RenderParameters(rawProfile = RawProfile.COLOR, rawExposure = -0.75f)),
            1e-6f,
        )
    }
}

class RawProfileVariantsTest {
    private fun shown(profile: Int, red: Float, green: Float, blue: Float): FloatArray =
        FloatArray(3).also { RawProfile.toDisplay(red, green, blue, profile, it) }

    @Test fun `the four profiles keep black, white and the light above white`() {
        for (profile in listOf(RawProfile.COLOR, RawProfile.NEUTRAL, RawProfile.VIVID, RawProfile.MONOCHROME)) {
            assertEquals(0f, shown(profile, 0f, 0f, 0f)[0], 1e-6f)
            assertEquals(1f, shown(profile, 1f, 1f, 1f)[1], 1e-5f)
            assertTrue("above white is kept ($profile)", shown(profile, 3f, 3f, 3f)[2] > 1f)
        }
    }

    @Test fun `neutral is flatter than colour and vivid is livelier`() {
        // Two greys, a dark and a light one, in the middle of the scale where contrast lives.
        fun spread(profile: Int) = shown(profile, 0.45f, 0.45f, 0.45f)[0] - shown(profile, 0.05f, 0.05f, 0.05f)[0]
        val dark = shown(RawProfile.COLOR, 0.02f, 0.02f, 0.02f)[0]
        assertTrue("a dark grey is lifted by neutral and crushed by vivid", shown(RawProfile.NEUTRAL, 0.02f, 0.02f, 0.02f)[0] > dark &&
            shown(RawProfile.VIVID, 0.02f, 0.02f, 0.02f)[0] < dark)
        val light = shown(RawProfile.COLOR, 0.6f, 0.6f, 0.6f)[0]
        assertTrue("and a light one is held down by neutral and lifted by vivid", shown(RawProfile.NEUTRAL, 0.6f, 0.6f, 0.6f)[0] < light &&
            shown(RawProfile.VIVID, 0.6f, 0.6f, 0.6f)[0] > light)
        assertTrue(spread(RawProfile.NEUTRAL) < spread(RawProfile.COLOR) && spread(RawProfile.COLOR) < spread(RawProfile.VIVID) + 0.2f)
        fun chroma(profile: Int): Float {
            val c = shown(profile, 0.30f, 0.12f, 0.06f)
            return c.max() - c.min()
        }
        assertTrue("vivid has more colour than colour", chroma(RawProfile.VIVID) > chroma(RawProfile.COLOR) * 1.1f)
        assertTrue("neutral does not add colour", chroma(RawProfile.NEUTRAL) < chroma(RawProfile.VIVID))
    }

    @Test fun `the recipe's profile picks the variant, and one this app does not know asks for an update`() {
        val recipe = eu.studio742.imago.core.model.EditRecipe(assetId = "raw", originalChecksum = "", createdAt = "", updatedAt = "")
        assertEquals(RawProfile.NEUTRAL, recipe.copy(profile = eu.studio742.imago.core.model.RAW_PROFILE_NEUTRAL).toRenderParameters().rawProfile)
        assertEquals(RawProfile.VIVID, recipe.copy(profile = eu.studio742.imago.core.model.RAW_PROFILE_VIVID).toRenderParameters().rawProfile)
        assertTrue(eu.studio742.imago.core.model.RAW_PROFILES.size == 4)
        // The recipe still reads the first one the way it did.
        assertEquals(RawProfile.COLOR, recipe.toRenderParameters().rawProfile)
    }
}
