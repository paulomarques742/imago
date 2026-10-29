package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotsViewTest {
    /** A flat sky with one faint dark dot, the size dust leaves, at ([dotX], [dotY]). */
    private fun sky(width: Int, height: Int, dotX: Int, dotY: Int, radius: Int, depth: Int): PixelBuffer {
        val image = PixelBuffer(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            val inDot = (x - dotX) * (x - dotX) + (y - dotY) * (y - dotY) <= radius * radius
            val g = if (inDot) 200 - depth else 200
            image.pixels[y * width + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return image
    }

    private fun PixelBuffer.grey(x: Int, y: Int) = pixels[y * width + x] and 255

    @Test
    fun `a dot of dust in a flat sky turns white and the sky stays black`() {
        val map = SpotsView().render(sky(400, 300, 200, 150, 3, depth = 16), SpotsView.DEFAULT_SENSITIVITY)
        assertEquals(255, map.grey(200, 150))
        assertEquals(0, map.grey(50, 50))
        assertEquals(0, map.grey(350, 250))
    }

    @Test
    fun `more sensitivity shows a fainter dot, less hides it`() {
        val photo = sky(400, 300, 200, 150, 3, depth = 6)
        val view = SpotsView()
        assertEquals(255, view.render(photo, 90f).grey(200, 150))
        assertEquals(0, view.render(photo, 10f).grey(200, 150))
        assertTrue(SpotsView.threshold(0f) > SpotsView.threshold(50f))
        assertTrue(SpotsView.threshold(50f) > SpotsView.threshold(100f))
    }

    @Test
    fun `a large photo is mapped on a raster of the same side, and its dust still shows`() {
        val map = SpotsView().render(sky(4096, 2048, 2048, 1024, 6, depth = 20), SpotsView.DEFAULT_SENSITIVITY)
        assertEquals(SpotsView.SIDE, map.width)
        assertEquals(SpotsView.SIDE / 2, map.height)
        assertEquals(255, map.grey(1024, 512))
        assertEquals(0, map.grey(200, 200))
    }

    @Test
    fun `the contrast is kept to within a step of the ramp, across every threshold the slider reaches`() {
        for (sensitivity in 0..100 step 5) {
            val threshold = SpotsView.threshold(sensitivity.toFloat())
            val kept = SpotsView.contrastAt(SpotsView.levelOf(threshold))
            assertTrue("$sensitivity: $threshold kept as $kept", kotlin.math.abs(kept - threshold) / threshold < 0.01f)
        }
        assertEquals(0, SpotsView.levelOf(0f))
        assertEquals(255, SpotsView.levelOf(1f))
    }

    @Test
    fun `the map is framed as the photo is, and nothing else of the recipe reaches it`() {
        val parameters = RenderParameters(exposure = 1.5f, contrast = 40f, cropX = .1f, cropWidth = .5f, rotation = 90, straighten = 3f)
        assertEquals(RenderParameters(cropX = .1f, cropWidth = .5f, rotation = 90, straighten = 3f), parameters.framing)
        assertTrue(parameters.framing.isColorNeutral)
    }
}
