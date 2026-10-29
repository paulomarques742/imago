package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.RETOUCH_PROCESS_VERSION
import eu.studio742.imago.core.model.Retouch
import eu.studio742.imago.core.model.RetouchMode
import eu.studio742.imago.core.model.Tone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class RetouchRenderingTest {
    /** A different value in every column, so a pixel copied from anywhere else shows. */
    private val photo = PixelBuffer(300, 200).also { image ->
        for (y in 0 until 200) for (x in 0 until 300) {
            val g = x * 7 % 251
            image.pixels[y * 300 + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    private val clone = Retouch(
        id = "c",
        mode = RetouchMode.CLONE,
        strokes = listOf(BrushStroke(listOf(.7f, .5f, .7f, .5f), radius = .1f, feather = 0f)),
        sourceX = -.3f,
    )

    private fun recipe(processVersion: Int = RETOUCH_PROCESS_VERSION, retouches: List<Retouch> = listOf(clone)) = EditRecipe(
        assetId = "a",
        originalChecksum = "",
        createdAt = "",
        updatedAt = "",
        processVersion = processVersion,
        retouches = retouches,
    )

    private fun red(argb: Int) = (argb ushr 16) and 0xFF

    @Test
    fun `a recipe before the corrections' process draws none of them`() {
        assertTrue(recipe(processVersion = RETOUCH_PROCESS_VERSION - 1).toRenderParameters().retouches.isEmpty())
        assertEquals(listOf(clone), recipe().toRenderParameters().retouches)
        assertArrayEquals(photo.pixels, RecipePixels.render(photo, recipe(processVersion = RETOUCH_PROCESS_VERSION - 1)).pixels)
    }

    @Test
    fun `a correction alone changes the image but not its colour`() {
        val parameters = recipe().toRenderParameters()
        assertFalse(parameters.isNeutral)
        assertTrue(parameters.isColorNeutral)
        assertTrue(recipe().changesTheImage())
    }

    @Test
    fun `the render starts from the corrected photo`() {
        val rendered = RecipePixels.render(photo, recipe())
        assertArrayEquals(Retouching.apply(photo, listOf(clone)).pixels, rendered.pixels)
        val shift = (.3f * 300).roundToInt()
        assertEquals(red(photo.pixels[100 * 300 + 210 - shift]), red(rendered.pixels[100 * 300 + 210]))
        assertNotEquals(red(photo.pixels[100 * 300 + 210]), red(rendered.pixels[100 * 300 + 210]))
    }

    @Test
    fun `a correction stays on its spot when the photo is turned`() {
        val turned = recipe().copy(geometry = Geometry(rotation = 90))
        val rendered = RecipePixels.render(photo, turned)
        val corrected = Retouching.apply(photo, listOf(clone))
        // Turned a quarter clockwise, the image's (x, y) lands at column height - 1 - y, row x.
        for (x in listOf(10, 205, 210, 215, 290)) {
            assertEquals("column $x", red(corrected.pixels[100 * 300 + x]), red(rendered.pixels[x * 200 + 200 - 1 - 100]))
        }
    }

    @Test
    fun `the corrections come before the adjustments`() {
        val brighter = recipe().copy(tone = Tone(exposure = 1f))
        val expected = RecipePixels.render(Retouching.apply(photo, listOf(clone)), brighter.copy(retouches = emptyList()))
        assertArrayEquals(expected.pixels, RecipePixels.render(photo, brighter).pixels)
    }

    @Test
    fun `an encoded float photo is corrected like its eight bits`() {
        val float = FloatPixelBuffer(300, 200, FloatArray(300 * 200 * 3) { (it / 3 % 300 * 7 % 251) / 255f }, linear = false)
        val fromFloat = RecipePixels.render(float, recipe())
        val fromBytes = RecipePixels.render(photo, recipe())
        for (i in fromBytes.pixels.indices) {
            assertTrue("pixel $i", abs(red(fromFloat.pixels[i]) - red(fromBytes.pixels[i])) <= 1)
        }
    }

    @Test
    fun `a linear development keeps what is above white through a clone`() {
        // Column 120 holds twice the white; the clone takes it to column 210, the area's centre.
        val float = FloatPixelBuffer(300, 200, FloatArray(300 * 200 * 3) { if (it / 3 % 300 == 120) 2f else .1f }, linear = true)
        val corrected = Retouching.apply(float, listOf(clone))
        assertEquals(2f, corrected.rgb[(100 * 300 + 210) * 3], .02f)
        assertEquals(.1f, corrected.rgb[(100 * 300 + 212) * 3], .002f)
        // Untouched far away, in its own precision.
        assertEquals(float.rgb[(10 * 300 + 10) * 3], corrected.rgb[(10 * 300 + 10) * 3], 0f)
    }

    @Test
    fun `the editor's cache works the corrections out once per list`() {
        val retouched = RetouchedSources()
        val first = retouched.of(photo, listOf(clone))
        assertNotSame(photo, first)
        assertSame(first, retouched.of(photo, listOf(clone.copy())))
        val moved = retouched.of(photo, listOf(clone.copy(sourceX = -.4f)))
        assertNotSame(first, moved)
        assertSame(photo, retouched.of(photo, emptyList()))
    }

    @Test
    fun `an export's development takes the corrections where it is`() {
        fun development() = FloatPixelBuffer(300, 200, FloatArray(300 * 200 * 3) { (it / 3 % 300 * 7 % 251) / 255f }, linear = false)
        val parameters = recipe().toRenderParameters()
        val expected = RecipePixels.renderFloat(development(), parameters).rgb.toFloatArray()
        val source = development()
        val rendered = RecipePixels.renderFloat(source, parameters, consumeSource = true)
        assertSame("No copy of the development for the corrections.", source, rendered)
        assertArrayEquals(expected, rendered.rgb.toFloatArray(), 0f)
    }

    @Test
    fun `a render through the cache leaves the cached photo as it was`() {
        val retouched = RetouchedSources()
        val float = FloatPixelBuffer(300, 200, FloatArray(300 * 200 * 3) { .5f }, linear = false)
        val parameters = recipe().toRenderParameters()
        val corrected = retouched.of(float, parameters.retouches)
        val before = corrected.rgb.toFloatArray()
        RecipePixels.renderFloat(float, parameters.copy(exposure = 1f), consumeSource = true, retouched = retouched)
        assertArrayEquals(before, corrected.rgb.toFloatArray(), 0f)
    }
}
