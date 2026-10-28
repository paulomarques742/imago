package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LensCorrection
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensCorrectionTest {
    private val width = 64
    private val height = 16

    private fun render(rgb: FloatArray, w: Int, h: Int, parameters: RenderParameters) =
        RecipePixels.renderFloat(FloatPixelBuffer(w, h, rgb.copyOf(), linear = false), parameters).rgb.toFloatArray()

    private fun saturation(rgb: FloatArray, x: Int, y: Int, w: Int): Float {
        val i = (y * w + x) * 3
        val high = maxOf(rgb[i], rgb[i + 1], rgb[i + 2])
        return if (high > 0f) (high - minOf(rgb[i], rgb[i + 1], rgb[i + 2])) / high else 0f
    }

    @Test fun `the vignette is nothing at the centre, and grows to the corners with the sign of the slider`() {
        val lighter = RenderParameters(lensVignetteAmount = 100f)
        val darker = RenderParameters(lensVignetteAmount = -100f)
        fun ev(p: RenderParameters, u: Float, v: Float) = LensVignette.ev(p.lensVignetteAmount, p.lensVignetteMidpoint, u, v, 1.5f)
        assertEquals(0f, ev(lighter, 0.5f, 0.5f), 0f)
        assertEquals(LensVignette.MAX_EV, ev(lighter, 0f, 0f), 1e-4f)
        assertEquals(-LensVignette.MAX_EV, ev(darker, 1f, 1f), 1e-4f)
        assertTrue("a point halfway out is between", ev(lighter, 0.25f, 0.25f) in 0f..LensVignette.MAX_EV)
        assertTrue(
            "and the midpoint says where it starts",
            ev(RenderParameters(lensVignetteAmount = 100f, lensVignetteMidpoint = 100f), 0.25f, 0.25f) <
                ev(RenderParameters(lensVignetteAmount = 100f, lensVignetteMidpoint = 0f), 0.25f, 0.25f),
        )
    }

    @Test fun `a flat photo gets lighter corners and an untouched centre`() {
        val w = 48
        val h = 32
        val flat = FloatArray(w * h * 3) { 0.4f }
        val out = render(flat, w, h, RenderParameters(lensVignetteAmount = 100f))
        val centre = out[((h / 2) * w + w / 2) * 3]
        val corner = out[0]
        assertEquals("the centre stays", 0.4f, centre, 0.01f)
        assertTrue("the corner is lighter: $corner", corner > centre + 0.1f)
        val darker = render(flat, w, h, RenderParameters(lensVignetteAmount = -100f))
        assertTrue(darker[0] < centre - 0.1f)
    }

    @Test fun `the frame's corner is measured on the whole image, wherever the crop falls`() {
        val p = RenderParameters(lensVignetteAmount = 100f)
        val whole = LensVignette.evFramed(p, null, 100, 100, 0f, 0f, FloatArray(2))
        // The same corner of a crop that keeps only the middle half of the photo.
        val cropped = FrameGeometry(sourceWidth = 400, sourceHeight = 400, cropX = 0.25f, cropY = 0.25f, cropWidth = 0.5f, cropHeight = 0.5f)
        val inside = LensVignette.evFramed(p, cropped, 200, 200, 0f, 0f, FloatArray(2))
        assertTrue("the crop's corner is far from the lens' corner: $inside vs $whole", inside < whole * 0.5f)
    }

    @Test fun `the hue of the primaries and of a purple`() {
        assertEquals(0f, Defringe.hue(1f, 0f, 0f), 0.01f)
        assertEquals(120f, Defringe.hue(0f, 1f, 0f), 0.01f)
        assertEquals(240f, Defringe.hue(0f, 0f, 1f), 0.01f)
        assertTrue(Defringe.hue(0.6f, 0.2f, 0.7f) in 270f..300f)
        assertEquals(1f, Defringe.membership(285f, Defringe.PURPLE_HUE, 50f), 1e-6f)
        assertEquals(0f, Defringe.membership(120f, Defringe.PURPLE_HUE, 100f), 0f)
        assertTrue(
            "a wider range takes in more",
            Defringe.membership(240f, Defringe.PURPLE_HUE, 100f) > Defringe.membership(240f, Defringe.PURPLE_HUE, 0f),
        )
    }

    @Test fun `a purple fringe along a hard edge loses its colour and a purple field away from one does not`() {
        // White on the left, black on the right, and two pixels of purple where they meet.
        val edge = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val c = when {
                x < 32 -> floatArrayOf(1f, 1f, 1f)
                x < 34 -> floatArrayOf(0.6f, 0.2f, 0.7f)
                else -> floatArrayOf(0f, 0f, 0f)
            }
            for (k in 0..2) edge[(y * width + x) * 3 + k] = c[k]
        }
        val before = saturation(edge, 32, 8, width)
        val purple = render(edge, width, height, RenderParameters(lensPurpleAmount = 100f))
        assertTrue("the fringe's saturation $before -> ${saturation(purple, 32, 8, width)}", saturation(purple, 32, 8, width) < before * 0.5f)
        val greenOnly = render(edge, width, height, RenderParameters(lensGreenAmount = 100f))
        assertTrue("green does not touch purple", saturation(greenOnly, 32, 8, width) > before * 0.9f)
        assertEquals("the white stays", 1f, purple[(8 * width + 5) * 3], 0.02f)
        // A purple patch on a grey of its own luminance has no edge to be a fringe of.
        val patchLuminance = PhotoEffects.luminance(0.5f, 0.1f, 0.6f)
        val field = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val c = if (x < 32) floatArrayOf(0.5f, 0.1f, 0.6f) else floatArrayOf(patchLuminance, patchLuminance, patchLuminance)
            for (k in 0..2) field[(y * width + x) * 3 + k] = c[k]
        }
        val kept = render(field, width, height, RenderParameters(lensPurpleAmount = 100f))
        assertTrue("a purple object is not a fringe", saturation(kept, 30, 8, width) > saturation(field, 30, 8, width) * 0.9f)
    }

    @Test fun `only process 21 reads the lens and a half that is off leaves its companion alone`() {
        val recipe = EditRecipe(
            assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "",
            lens = LensCorrection(vignetteAmount = 40f, vignetteMidpoint = 70f, purpleAmount = 30f, purpleRange = 80f),
        )
        val p = recipe.toRenderParameters()
        assertEquals(40f, p.lensVignetteAmount, 0f)
        assertEquals(70f, p.lensVignetteMidpoint, 0f)
        assertEquals(30f, p.lensPurpleAmount, 0f)
        assertEquals(80f, p.lensPurpleRange, 0f)
        assertEquals(0f, recipe.copy(processVersion = 20).toRenderParameters().lensVignetteAmount, 0f)
        val off = recipe.copy(
            lens = LensCorrection(vignetteAmount = 0f, vignetteMidpoint = 10f, purpleAmount = 0f, purpleRange = 99f, greenAmount = 0f, greenRange = 3f),
        )
        assertTrue(
            "the midpoint of an off vignette is not an edit",
            off.toRenderParameters().lensVignetteMidpoint == 50f && off.toRenderParameters().lensPurpleRange == 50f,
        )
        assertTrue(!off.toRenderParameters().needsLensVignette && !off.toRenderParameters().needsDefringe)
    }

    @Test fun `the shader's constants are the export's`() {
        assertTrue(PhotoShaders.TONE.contains("return uLensVignetteAmount / 100.0 * 1.5 * weight * weight;"))
        assertTrue(PhotoShaders.TONE.contains("float start = 0.1 + 0.8 * clamp(uLensVignetteMidpoint / 100.0, 0.0, 1.0);"))
        assertTrue(PhotoShaders.LOCAL_BASE.contains("+ lensEv(vTexCoord)"))
        assertEquals(1.5f, LensVignette.MAX_EV, 0f)
        val fringe = PhotoShaders.DEFRINGE
        listOf(
            "float edge = smoothstep(0.02, 0.08, 0.5 * sqrt(slopeX * slopeX + slopeY * slopeY));",
            "membership(hue, 285.0, uPurpleRange)",
            "membership(hue, 115.0, uGreenRange)",
            "float reach = 10.0 + 50.0 * clamp(range / 100.0, 0.0, 1.0);",
            "float colour = smoothstep(0.05, 0.25, saturation);",
        ).forEach { assertTrue("DEFRINGE should contain $it", fringe.contains(it)) }
        assertEquals(0.02f, Defringe.EDGE_LOW, 0f)
        assertEquals(0.08f, Defringe.EDGE_HIGH, 0f)
        assertEquals(285f, Defringe.PURPLE_HUE, 0f)
        assertEquals(115f, Defringe.GREEN_HUE, 0f)
        assertTrue(abs(Defringe.halfWidth(0f) - 10f) < 1e-6f && abs(Defringe.halfWidth(100f) - 60f) < 1e-6f)
    }
}
