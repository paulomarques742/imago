package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.Retouch
import eu.studio742.imago.core.model.RetouchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class RetouchingTest {
    private fun image(width: Int, height: Int, rgb: (u: Float, v: Float, x: Int, y: Int) -> Int) = PixelBuffer(width, height).also { image ->
        for (y in 0 until height) for (x in 0 until width) {
            image.pixels[y * width + x] = rgb((x + 0.5f) / width, (y + 0.5f) / height, x, y)
        }
    }

    private fun grey(level: Float): Int {
        val g = level.roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun red(argb: Int) = (argb ushr 16) and 0xFF

    private fun spot(x: Float, y: Float, radius: Float, feather: Float = 0f) = BrushStroke(listOf(x, y, x, y), radius, feather)

    private fun retouch(mode: RetouchMode, stroke: BrushStroke, dx: Float, dy: Float, opacity: Float = 100f) =
        Retouch(id = "r", mode = mode, strokes = listOf(stroke), sourceX = dx, sourceY = dy, opacity = opacity)

    /** The mean red level over a disc of the image, in normalised coordinates. */
    private fun discMean(image: PixelBuffer, cx: Float, cy: Float, radius: Float): Float {
        var sum = 0f
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val du = ((x + 0.5f) / image.width - cx) * image.width / image.height
            val dv = (y + 0.5f) / image.height - cy
            if (du * du + dv * dv > radius * radius) continue
            sum += red(image.pixels[y * image.width + x])
            count++
        }
        return sum / count
    }

    @Test
    fun `nothing to apply gives the same pixels back`() {
        val source = image(64, 48) { _, _, _, _ -> grey(100f) }
        assertSame(source, Retouching.apply(source, emptyList()))
        assertSame(source, Retouching.apply(source, listOf(retouch(RetouchMode.CLONE, spot(.5f, .5f, .1f), .2f, 0f, opacity = 0f))))
    }

    @Test
    fun `a clone copies the source exactly inside its hard core`() {
        // A different value in every column, so a pixel copied from anywhere else would show.
        val source = image(300, 200) { _, _, x, _ -> grey((x * 7 % 251).toFloat()) }
        val result = Retouching.apply(source, listOf(retouch(RetouchMode.CLONE, spot(.7f, .5f, .1f), -.3f, 0f)))
        val shift = (.3f * 300).roundToInt()
        for (x in 205..215) {
            assertEquals("column $x", red(source.pixels[100 * 300 + x - shift]), red(result.pixels[100 * 300 + x]))
        }
        // Far from the area nothing moves.
        assertEquals(source.pixels[10 * 300 + 10], result.pixels[10 * 300 + 10])
        assertEquals(source.pixels[100 * 300 + 299], result.pixels[100 * 300 + 299])
    }

    @Test
    fun `opacity mixes the clone with what was there`() {
        val source = image(300, 200) { u, _, _, _ -> grey(if (u < .5f) 40f else 200f) }
        val result = Retouching.apply(source, listOf(retouch(RetouchMode.CLONE, spot(.75f, .5f, .1f), -.5f, 0f, opacity = 50f)))
        assertEquals(120, red(result.pixels[100 * 300 + 225]))
    }

    @Test
    fun `a heal takes the texture of the source and the light of the destination`() {
        // A slow gradient from dark to bright, with a fine checker on top: the texture is the same
        // everywhere, the light is not.
        val source = image(400, 200) { u, _, x, y -> grey(40f + 160f * u + if ((x + y) % 2 == 0) 12f else -12f) }
        val area = spot(.75f, .5f, .12f)
        val healed = Retouching.apply(source, listOf(retouch(RetouchMode.HEAL, area, -.5f, 0f)))
        val cloned = Retouching.apply(source, listOf(retouch(RetouchMode.CLONE, area, -.5f, 0f)))
        val expected = discMean(source, .75f, .5f, .1f)
        // The clone brings the dark side's light with it; the heal matches the surroundings.
        assertTrue("the clone should be darker: ${discMean(cloned, .75f, .5f, .1f)} vs $expected", expected - discMean(cloned, .75f, .5f, .1f) > 60f)
        assertEquals(expected, discMean(healed, .75f, .5f, .1f), 3f)
        // The checker is still there: neighbouring pixels differ by about its full swing.
        val centre = 100 * 400 + 300
        assertTrue(abs(red(healed.pixels[centre]) - red(healed.pixels[centre + 1])) > 16)
    }

    @Test
    fun `a heal does not leave a seam at the edge of the area`() {
        val source = image(400, 200) { u, v, _, _ -> grey(30f + 150f * u + 40f * v) }
        val healed = Retouching.apply(source, listOf(retouch(RetouchMode.HEAL, spot(.7f, .5f, .1f), -.4f, .1f)))
        // On a smooth photo the heal has nothing to add: the result is the photo, inside and across the edge.
        var worst = 0
        for (i in source.pixels.indices) worst = maxOf(worst, abs(red(source.pixels[i]) - red(healed.pixels[i])))
        assertTrue("largest change $worst", worst <= 2)
    }

    @Test
    fun `a heal on a photo bigger than the raster reads its ring from the whole source`() {
        // Each texel of the raster covers several pixels here, and the ring around the area reaches
        // past the pixels the area itself covers.
        val source = image(4096, 400) { u, _, x, y -> grey(40f + 160f * u + if ((x + y) % 2 == 0) 12f else -12f) }
        val healed = Retouching.apply(source, listOf(retouch(RetouchMode.HEAL, spot(.75f, .5f, .1f), -.5f, 0f)))
        assertEquals(discMean(source, .75f, .5f, .08f), discMean(healed, .75f, .5f, .08f), 3f)
    }

    @Test
    fun `the same heal comes out the same on a preview and on the full photo`() {
        fun photo(width: Int, height: Int) = image(width, height) { u, v, _, _ ->
            grey(30f + 150f * u + 60f * kotlin.math.sin(v * 9f) + 20f * kotlin.math.cos(u * 13f))
        }
        val retouches = listOf(retouch(RetouchMode.HEAL, spot(.65f, .45f, .09f, feather = 50f), -.35f, .12f))
        val small = Retouching.apply(photo(300, 200), retouches)
        val large = Retouching.apply(photo(600, 400), retouches)
        var total = 0f
        var worst = 0f
        for (y in 0 until 200) for (x in 0 until 300) {
            val mean = (red(large.pixels[(2 * y) * 600 + 2 * x]) + red(large.pixels[(2 * y) * 600 + 2 * x + 1]) +
                red(large.pixels[(2 * y + 1) * 600 + 2 * x]) + red(large.pixels[(2 * y + 1) * 600 + 2 * x + 1])) / 4f
            val difference = abs(mean - red(small.pixels[y * 300 + x]))
            total += difference
            worst = maxOf(worst, difference)
        }
        assertTrue("mean difference ${total / (300 * 200)}", total / (300 * 200) < 0.5f)
        assertTrue("largest difference $worst", worst < 6f)
    }

    @Test
    fun `the proposed source sits where the surroundings look the same`() {
        // Horizontal bands: every row has its own colour, so only a sideways move keeps the surroundings.
        val source = image(600, 400) { _, v, _, _ -> grey(20f + 200f * v) }
        val proposal = Retouching.proposeSource(BrushReference.of(source), listOf(spot(.5f, .5f, .06f)))
        assertNotNull(proposal)
        val (dx, dy) = proposal!!
        assertTrue("dy should be about zero: $dy", abs(dy) < .01f)
        assertTrue("dx should clear the area: $dx", abs(dx) > .06f)
    }

    @Test
    fun `laplace joins two known edges with a straight line`() {
        val width = 33
        val height = 3
        val known = BooleanArray(width * height) { it % width == 0 || it % width == width - 1 }
        val values = FloatArray(width * height * 3)
        for (y in 0 until height) for (channel in 0 until 3) values[(y * width + width - 1) * 3 + channel] = 100f
        Retouching.Laplace.fill(width, height, known, values)
        assertEquals(50f, values[(width + 16) * 3], 1f)
        assertEquals(25f, values[(width + 8) * 3], 1f)
    }
}
