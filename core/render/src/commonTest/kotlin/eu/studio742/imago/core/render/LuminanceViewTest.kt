package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LuminanceRange
import eu.studio742.imago.core.model.MaskShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LuminanceViewTest {
    private fun flat(argb: Int, width: Int = 64, height: Int = 48) =
        PixelBuffer(width, height).also { it.pixels.fill(argb) }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun PixelBuffer.channels(index: Int = 0) =
        Triple(pixels[index] shr 16 and 255, pixels[index] shr 8 and 255, pixels[index] and 255)

    @Test
    fun `black and white stay at the ends of the scale`() {
        assertEquals(Triple(0, 0, 0), LuminanceView().render(flat(rgb(0, 0, 0))).channels())
        assertEquals(Triple(255, 255, 255), LuminanceView().render(flat(rgb(255, 255, 255))).channels())
    }

    /** Every grey is its own lightness, so a pixel with no colour has nothing to lose in the map. */
    @Test
    fun `a pixel that is already grey keeps its value`() {
        for (grey in listOf(3, 20, 64, 128, 200, 250)) {
            val (r, _, _) = LuminanceView().render(flat(rgb(grey, grey, grey))).channels()
            assertTrue("$grey became $r", abs(r - grey) <= 1)
        }
    }

    @Test
    fun `colour goes and its lightness stays`() {
        val (r, g, b) = LuminanceView().render(flat(rgb(40, 90, 220))).channels()
        assertEquals(r, g)
        assertEquals(g, b)
        assertTrue(r in 41..219)
    }

    /** What the eye calls brighter: a yellow is lighter than a blue of the same strength. */
    @Test
    fun `the map orders colours by the lightness the range reads`() {
        val yellow = LuminanceView().render(flat(rgb(230, 230, 40))).channels().first
        val blue = LuminanceView().render(flat(rgb(40, 40, 230))).channels().first
        assertTrue("$yellow against $blue", yellow > blue)
    }

    /** The red and the grey under it have to agree: where the range takes a pixel, the map shows why. */
    @Test
    fun `a grey inside the range on the map is a pixel the range selects`() {
        val source = flat(rgb(120, 160, 90))
        val reference = BrushReference.of(source)
        val lightness = RangeMasks.lightnessMap(reference)[0]
        val range = LuminanceRange(low = lightness - 1f, high = lightness + 1f, smoothness = 0f)
        val raster = BrushRaster(reference.width, reference.height)

        RangeMasks.paint(raster, MaskRenderComponent(shape = MaskShape.LUMINANCE, luminance = range), reference)

        assertEquals(1f, raster.valueAt(0), 1e-3f)
        val (grey, _, _) = LuminanceView().render(source).channels()
        val mapLightness = RangeMasks.lightnessMap(BrushReference.of(flat(rgb(grey, grey, grey))))[0]
        assertEquals(lightness, mapLightness, 1f)
    }

    @Test
    fun `the map has the size of the rasters it lies under`() {
        val source = flat(rgb(90, 90, 90), width = 300, height = 200)
        val map = LuminanceView().render(source)
        val reference = BrushReference.of(source)
        assertEquals(reference.width, map.width)
        assertEquals(reference.height, map.height)
    }

    @Test
    fun `the map is worked out once per photo`() {
        val view = LuminanceView()
        val photo = flat(rgb(90, 120, 150))
        val map = view.render(photo)

        assertSame(map, view.render(photo))
        assertNotSame(map, view.render(flat(rgb(90, 120, 150))))
    }

    /** The red needs the mask's shapes; the map must not take a tone from its adjustments. */
    @Test
    fun `what the map is drawn with keeps the shapes of the masks and none of their adjustments`() {
        val component = MaskRenderComponent(shape = MaskShape.LUMINANCE, luminance = LuminanceRange())
        val parameters = RenderParameters(
            contrast = 40f,
            cropWidth = 0.5f,
            masks = listOf(
                MaskRenderSpec(
                    components = listOf(component),
                    inverted = true,
                    adjustments = LocalAdjustments(exposure = 1f),
                    id = "m",
                ),
            ),
        )

        val drawn = parameters.maskFraming

        assertEquals(0f, drawn.contrast)
        assertEquals(0.5f, drawn.cropWidth)
        assertEquals(listOf(component), drawn.masks.single().components)
        assertEquals("m", drawn.masks.single().id)
        assertTrue(drawn.masks.single().inverted)
        assertEquals(LocalAdjustments(), drawn.masks.single().adjustments)
    }
}
