package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.Calibration
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RAW_RENDERING_LINEAR
import eu.studio742.imago.core.model.RAW_RENDERING_PROFILED
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    private fun through(p: RenderParameters, r: Float, g: Float, b: Float): FloatArray =
        floatArrayOf(r, g, b).also { CalibrationModel.apply(it, p.calibrationMatrix, p.calibrationShadowsTint) }

    @Test fun `nothing moved is no matrix and no change`() {
        assertNull(RenderParameters().calibrationMatrix)
        assertTrue(!RenderParameters().needsCalibration)
        assertTrue(RenderParameters(calibrationShadowsTint = 10f).needsCalibration)
        assertNull("the tint alone is not a matrix", RenderParameters(calibrationShadowsTint = 10f).calibrationMatrix)
    }

    @Test fun `grey stays grey however far the primaries go`() {
        val sets = listOf(
            RenderParameters(calibrationRedHue = 100f, calibrationGreenSaturation = -100f),
            RenderParameters(calibrationBlueHue = -100f, calibrationBlueSaturation = 100f, calibrationRedSaturation = 70f),
            RenderParameters(calibrationRedHue = 60f, calibrationGreenHue = -80f, calibrationBlueHue = 30f),
        )
        for (p in sets) for (level in listOf(0.05f, 0.4f, 0.9f)) {
            val grey = through(p, level, level, level)
            assertEquals("red at $level", level, grey[0], 1e-4f)
            assertEquals("green at $level", level, grey[1], 1e-4f)
            assertEquals("blue at $level", level, grey[2], 1e-4f)
        }
    }

    @Test fun `each primary's hue moves that primary and leaves the other two exactly where they were`() {
        val p = RenderParameters(calibrationRedHue = 60f)
        val red = through(p, 1f, 0f, 0f)
        assertTrue("the red turns: ${red.toList()}", red[1] > 0.05f || red[2] > 0.05f)
        // A pure green and a pure blue stay on their own channel; only their strength may change a little.
        val green = through(p, 0f, 1f, 0f)
        assertEquals(0f, green[0], 1e-5f); assertEquals(1f, green[1], 1e-5f); assertEquals(0f, green[2], 1e-5f)
        val blue = through(p, 0f, 0f, 1f)
        assertEquals(0f, blue[0], 1e-5f); assertEquals(0f, blue[1], 1e-5f); assertEquals(1f, blue[2], 1e-5f)
    }

    @Test fun `a primary's saturation scales that primary's colour and nothing else's`() {
        val less = through(RenderParameters(calibrationBlueSaturation = -100f), 0f, 0f, 1f)
        val more = through(RenderParameters(calibrationBlueSaturation = 100f), 0f, 0f, 1f)
        fun chroma(c: FloatArray) = c.max() - c.min()
        assertTrue("less saturation, less chroma: ${chroma(less)}", chroma(less) < 0.8f)
        assertTrue("more saturation is not less: ${chroma(more)}", chroma(more) >= chroma(through(RenderParameters(), 0f, 0f, 1f)) - 0.05f)
        val red = through(RenderParameters(calibrationBlueSaturation = -100f), 1f, 0f, 0f)
        assertEquals(0f, red[1], 1e-4f); assertEquals(0f, red[2], 1e-4f)
    }

    @Test fun `the shadows' tint moves the shadows and leaves the rest`() {
        val magenta = RenderParameters(calibrationShadowsTint = 100f)
        val green = RenderParameters(calibrationShadowsTint = -100f)
        val dark = through(magenta, 0.02f, 0.02f, 0.02f)
        assertTrue("magenta takes green out of a shadow", dark[1] < 0.02f && dark[0] > 0.02f && dark[2] > 0.02f)
        assertTrue("and green puts it in", through(green, 0.02f, 0.02f, 0.02f)[1] > 0.02f)
        assertEquals("a midtone is left alone", 0.5f, through(magenta, 0.5f, 0.5f, 0.5f)[1], 1e-6f)
        assertEquals("and a highlight", 0.9f, through(magenta, 0.9f, 0.9f, 0.9f)[0], 1e-6f)
    }

    @Test fun `only a RAW of process 21 reads the calibration`() {
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "",
            calibration = Calibration(redHue = 40f, shadowsTint = -30f))
        assertEquals(40f, recipe.toRenderParameters(rawRendering = RAW_RENDERING_PROFILED).calibrationRedHue, 0f)
        assertEquals(-30f, recipe.toRenderParameters(rawRendering = RAW_RENDERING_PROFILED).calibrationShadowsTint, 0f)
        assertEquals("the first rendering never had it", 0f, recipe.toRenderParameters(rawRendering = RAW_RENDERING_LINEAR).calibrationRedHue, 0f)
        assertEquals("a process before 21 never had it", 0f,
            recipe.copy(processVersion = 20).toRenderParameters(rawRendering = RAW_RENDERING_PROFILED).calibrationRedHue, 0f)
        assertTrue(!recipe.copy(calibration = Calibration()).toRenderParameters().needsCalibration)
    }

    @Test fun `a linear RAW is calibrated and a JPEG is not`() {
        val p = RenderParameters(rawProfile = RawProfile.COLOR, calibrationRedHue = 80f, calibrationRedSaturation = -50f)
        fun render(linear: Boolean, parameters: RenderParameters) = RecipePixels.renderFloat(
            FloatPixelBuffer(1, 1, floatArrayOf(0.3f, 0.05f, 0.04f), linear = linear), parameters,
        ).rgb.toFloatArray()
        val calibrated = render(true, p)
        val plain = render(true, p.copy(calibrationRedHue = 0f, calibrationRedSaturation = 0f))
        assertTrue("the red moved: ${calibrated.toList()} vs ${plain.toList()}", (0..2).any { abs(calibrated[it] - plain[it]) > 0.02f })
        assertEquals("a JPEG has no camera primaries", render(false, RenderParameters()).toList(), render(false, p).toList())
    }

    @Test fun `the shader and the export do the same`() {
        val shader = PhotoShaders.TONE
        listOf(
            "linear = max(vec3(grey) + uCalibration * (linear - vec3(grey)), vec3(0.0));" to "the primaries' matrix, on what is left of the colour without its grey",
            "float weight = 1.0 - smoothstep(0.0, 0.25, luminance(linear));" to "the tint's weight",
            "return linear * vec3(1.0 + 0.06 * k, 1.0 - 0.12 * k, 1.0 + 0.06 * k);" to "the tint's gains",
            "if (uCalibrationOn == 1 && uSourceLinear == 1 && uRawProfile != 0) linear = calibrate(linear);" to "its place before the exposure",
        ).forEach { (fragment, what) -> assertTrue("The tone shader should have $what — \"$fragment\".", shader.contains(fragment)) }
        assertTrue(PhotoShaders.LOCAL_BASE.contains("uniform int uCalibrationOn;"))
        assertEquals(0.25f, CalibrationModel.TINT_REACH, 0f)
        assertEquals(0.12f, CalibrationModel.TINT_GREEN, 0f)
        assertEquals(0.06f, CalibrationModel.TINT_SIDES, 0f)
    }
}
