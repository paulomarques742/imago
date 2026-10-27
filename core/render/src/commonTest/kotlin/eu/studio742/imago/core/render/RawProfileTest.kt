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
    }

    @Test fun `before shows the photo developed, without the choices made on it`() {
        assertEquals(RenderParameters(rawProfile = RawProfile.COLOR), RenderParameters(exposure = 1f, rawProfile = RawProfile.MONOCHROME).original)
        assertEquals(RenderParameters(), RenderParameters(exposure = 1f).original)
        assertTrue(RenderParameters(rawProfile = RawProfile.COLOR).isNeutral)
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
