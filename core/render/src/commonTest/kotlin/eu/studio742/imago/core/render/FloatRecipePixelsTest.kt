package eu.studio742.imago.core.render

import org.junit.Assert.*
import org.junit.Test

class FloatRecipePixelsTest {
    @Test fun `negative exposure recovers camera values above display white`() {
        val source = FloatPixelBuffer(1, 1, floatArrayOf(4f, 2f, 1f))
        val output = RecipePixels.renderFloat(source, RenderParameters(exposure = -2f)).toPixelBuffer().pixels.single()
        assertEquals(255, output ushr 16 and 255)
        assertEquals(188, output ushr 8 and 255)
        assertEquals(137, output and 255)
        assertArrayEquals(floatArrayOf(4f, 2f, 1f), source.rgb.toFloatArray(), 0f)
    }

    @Test fun `tone and finish stages retain differences smaller than an eight bit step`() {
        val source = FloatPixelBuffer(2, 1, floatArrayOf(.10f, .10f, .10f, .1001f, .1001f, .1001f))
        assertEquals(source.toPixelBuffer().pixels[0], source.toPixelBuffer().pixels[1])
        val frame = RecipePixels.renderFloat(source, RenderParameters(exposure = .25f, vignetteAmount = -1f))
        assertFalse(frame.linear)
        assertTrue(frame.rgb[3] > frame.rgb[0])
        assertTrue(frame.rgb[3] - frame.rgb[0] < 1f / 255f)
    }

    @Test fun `linear geometry and reduced previews retain highlight headroom`() {
        val source = FloatPixelBuffer(4, 2, FloatArray(24) { 4f })
        val reduced = RecipePixels.reduce(source, 2)
        assertTrue(reduced.linear)
        assertTrue(reduced.rgb.all { it == 4f })
        val frame = RecipePixels.renderFloat(reduced, RenderParameters(rotation = 90, exposure = -3f))
        assertEquals(1, frame.width)
        assertEquals(2, frame.height)
        assertTrue(frame.rgb.all { kotlin.math.abs(it - encodeSrgb(.5f)) < .00001f })
    }

    @Test fun `consuming an unframed source avoids a second full resolution float allocation`() {
        val source = FloatPixelBuffer(2, 2, FloatArray(12) { 2f })
        val frame = RecipePixels.renderFloat(source, RenderParameters(exposure = -2f), consumeSource = true)
        assertSame(source, frame)
        assertFalse(frame.linear)
    }

    @Test fun `a consumed source lets go of its pixels once it is framed`() {
        fun development() = FloatPixelBuffer(8, 6, FloatArray(8 * 6 * 3) { (it % 7) / 7f })
        val cropped = RenderParameters(rotation = 90, cropWidth = .5f, cropHeight = .75f, exposure = .3f)
        val expected = RecipePixels.renderFloat(development(), cropped).rgb.toFloatArray()
        val source = development()
        val frame = RecipePixels.renderFloat(source, cropped, consumeSource = true)
        assertEquals(0, source.rgb.size)
        assertArrayEquals(expected, frame.rgb.toFloatArray(), 0f)
    }

    @Test fun `a source that is not consumed keeps its pixels`() {
        val source = FloatPixelBuffer(8, 6, FloatArray(8 * 6 * 3) { .25f })
        RecipePixels.renderFloat(source, RenderParameters(rotation = 90, cropWidth = .5f))
        assertEquals(8 * 6 * 3, source.rgb.size)
    }

    @Test fun `float input follows the shared detail and color pipeline`() {
        val rgb = FloatArray(16 * 16 * 3) { ((it * 17) % 256) / 255f }
        val source = FloatPixelBuffer(16, 16, rgb.copyOf(), linear = false)
        val params = RenderParameters(exposure = .2f, texture = 20f, clarity = 15f, dehaze = 5f, saturation = 10f, grainAmount = 5f)
        val floatOutput = RecipePixels.renderFloat(source, params).toPixelBuffer()
        val byteOutput = RecipePixels.render(source.toPixelBuffer(), params)
        for (i in byteOutput.pixels.indices) for (shift in intArrayOf(0, 8, 16)) {
            val difference = kotlin.math.abs((floatOutput.pixels[i] ushr shift and 255) - (byteOutput.pixels[i] ushr shift and 255))
            assertTrue("The float path must use the existing effects ($i, $shift, $difference).", difference <= 2)
        }
    }
}
