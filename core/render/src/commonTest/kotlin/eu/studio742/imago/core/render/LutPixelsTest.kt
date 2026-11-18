package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.ColorLut
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LUT_PROCESS_VERSION
import eu.studio742.imago.core.model.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Step 12b from the recipe to the pixels, and the shader that has to say the same. */
class LutPixelsTest {
    private fun grid(size: Int, map: (Float, Float, Float) -> FloatArray) = FloatArray(size * size * size * 3).also { out ->
        var at = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            map(r / (size - 1f), g / (size - 1f), b / (size - 1f)).forEach { out[at++] = it }
        }
    }

    private val identity = ColorLut.fromSamples("identity", 17, grid(17) { r, g, b -> floatArrayOf(r, g, b) })
    private val invert = ColorLut.fromSamples("invert", 9, grid(9) { r, g, b -> floatArrayOf(1 - r, 1 - g, 1 - b) })

    private fun recipe(lut: ColorLut?, process: Int = LUT_PROCESS_VERSION) =
        EditRecipe(assetId = "lut", originalChecksum = "", createdAt = "", updatedAt = "", processVersion = process, lut = lut)

    /**
     * Colours the pipeline leaves alone when nothing is set: it does move a dark, saturated colour on its
     * own, which is not what these tests are about, so they stay on the ones it does not touch.
     */
    private fun colours(): PixelBuffer = PixelBuffer(4, 2).apply {
        val rgb = listOf(
            intArrayOf(0x80, 0x60, 0x20), intArrayOf(0xF0, 0x60, 0x20), intArrayOf(0x30, 0x90, 0x10), intArrayOf(0x30, 0x90, 0xF0),
            intArrayOf(0x30, 0x90, 0x40), intArrayOf(0x30, 0x90, 0x80), intArrayOf(0x80, 0x80, 0x80), intArrayOf(0xE0, 0xE0, 0xE0),
        )
        rgb.forEachIndexed { index, c -> pixels[index] = (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2] }
    }

    private fun PixelBuffer.channels(index: Int) =
        intArrayOf(pixels[index] ushr 16 and 0xFF, pixels[index] ushr 8 and 0xFF, pixels[index] and 0xFF)

    /**
     * The same pipeline with nothing in the LUT's place: a negligible vibrance makes the processor run, as the
     * LUT does, so that what is compared is the LUT and not whatever the rest of the pipeline does by itself.
     */
    private fun withoutLut(): PixelBuffer =
        RecipePixels.render(colours(), recipe(null).copy(presence = Presence(vibrance = 0.0001f)))

    private fun assertEachChannel(expected: (Int) -> Float, drawn: PixelBuffer, tolerance: Float) {
        val baseline = withoutLut()
        for (index in 0 until 8) {
            baseline.channels(index).zip(drawn.channels(index)).forEach { (was, now) ->
                assertTrue("${expected(was)} expected, $now drawn", abs(expected(was) - now) <= tolerance)
            }
        }
    }

    @Test
    fun anIdentityTableLeavesTheColoursWhereTheyWere() {
        assertEachChannel({ it.toFloat() }, RecipePixels.render(colours(), recipe(identity)), 1f)
    }

    @Test
    fun anInvertingTableInvertsThem() {
        assertEachChannel({ 255f - it }, RecipePixels.render(colours(), recipe(invert)), 2f)
    }

    @Test
    fun theAmountMixesTheLookWithTheColour() {
        assertEachChannel({ it + ((255f - it) - it) * 0.5f }, RecipePixels.render(colours(), recipe(invert.copy(amount = 50f))), 2f)
    }

    @Test
    fun aRecipeFromBeforeTheProcessDrawsWithoutIt() {
        val before = colours()
        val after = RecipePixels.render(colours(), recipe(invert, process = LUT_PROCESS_VERSION - 1))
        assertTrue((0 until 8).all { before.channels(it).contentEquals(after.channels(it)) })
    }

    @Test
    fun aPhotoWhoseOnlyEditIsALutIsNotNeutral() {
        val parameters = recipe(invert).toRenderParameters()
        assertFalse(parameters.isNeutral)
        assertFalse(parameters.isColorNeutral)
        assertTrue(parameters.needsLutStage)
        assertTrue(recipe(null).toRenderParameters().isNeutral)
    }

    @Test
    fun aTableWithNoAmountOrThatCannotBeReadHasNothingToDraw() {
        assertNull(recipe(invert.copy(amount = 0f)).toRenderParameters().lutTable)
        assertNull(recipe(invert.copy(table = "broken")).toRenderParameters().lutTable)
        assertFalse(recipe(invert.copy(table = "broken")).toRenderParameters().needsLutStage)
    }

    /** The one definition of trilinear that the GPU and this class share: its corners and its middle. */
    @Test
    fun theReadIsTrilinearBetweenTheGridPoints() {
        val corners = ColorLut.fromSamples("2", 2, grid(2) { r, g, b -> floatArrayOf(r * 0.5f + 0.25f, g, b * b) })
        val table = Lut3D.of(corners)!!
        val centre = floatArrayOf(0.5f, 0.5f, 0.5f)
        table.apply(centre)
        assertEquals(0.5f, centre[0], 1e-3f)
        assertEquals(0.5f, centre[1], 1e-3f)
        assertEquals(0.5f, centre[2], 1e-3f)
        val corner = floatArrayOf(1f, 1f, 1f)
        table.apply(corner)
        assertEquals(0.75f, corner[0], 1e-3f)
    }

    @Test
    fun redChangesFastestInTheGrid() {
        // Output = the input's red in the blue channel: only a grid read in the right order gets that.
        val swap = ColorLut.fromSamples("swap", 2, grid(2) { r, _, _ -> floatArrayOf(0f, 0f, r) })
        val pixel = floatArrayOf(1f, 0f, 0f)
        Lut3D.of(swap)!!.apply(pixel)
        assertEquals(0f, pixel[0], 1e-3f)
        assertEquals(0f, pixel[1], 1e-3f)
        assertEquals(1f, pixel[2], 1e-3f)
    }

    private val composite = PhotoShaders.COMPOSITE

    @Test
    fun theShaderReadsTheTableLikeTheCpuDoes() {
        assertTrue("(clamped * (uLutSize - 1.0) + 0.5) / uLutSize" in composite)
        assertTrue("mix(clamped, looked, uLutStrength)" in composite)
        assertTrue("uniform highp sampler3D uLut;" in composite)
    }

    @Test
    fun theLutRunsAfterTheGradingAndBeforeTheEffects() {
        val grading = composite.indexOf("color = applyColorGrading(color)")
        val lut = composite.indexOf("color = applyLut(color)")
        val vignette = composite.indexOf("color = applyVignette(color, screen)")
        assertTrue(lut > 0)
        assertTrue(grading in 0 until lut)
        assertTrue(lut < vignette)
    }
}
