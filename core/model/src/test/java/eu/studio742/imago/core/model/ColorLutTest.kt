package eu.studio742.imago.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorLutTest {
    private fun identity(size: Int) = FloatArray(size * size * size * 3).also { out ->
        var at = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            out[at++] = r / (size - 1f)
            out[at++] = g / (size - 1f)
            out[at++] = b / (size - 1f)
        }
    }

    @Test
    fun theGridComesBackAsItWentInToSixteenBits() {
        val grid = identity(5)
        val lut = ColorLut.fromSamples("identity", 5, grid)
        val back = lut.samples()
        assertNotNull(back)
        grid.indices.forEach { assertEquals(grid[it], back!![it], 1f / 65535f) }
    }

    @Test
    fun theLargestGridStaysWellInsideWhatASyncedRecipeMayWeigh() {
        val lut = ColorLut.fromSamples("big", MAX_LUT_SIZE, identity(MAX_LUT_SIZE))
        // 1 MB is the server's limit for a recipe: the LUT alone has to leave room for the rest.
        assertTrue("the table weighs ${lut.table.length}", lut.table.length < 300_000)
    }

    @Test
    fun aTableThatIsNotTheRightSizeIsNotReadAndDrawsAsNone() {
        val good = ColorLut.fromSamples("x", 3, identity(3))
        assertNull(good.copy(size = 4).samples())
        assertNull(good.copy(table = "not base64 !").samples())
        assertNull(good.copy(table = good.table.dropLast(8)).samples())
        assertNull(good.copy(size = 1).samples())
        assertNull(good.copy(size = MAX_LUT_SIZE + 1).samples())
        assertTrue(good.copy(size = 4).isInert)
    }

    @Test
    fun noAmountIsInert() {
        assertTrue(ColorLut.fromSamples("x", 3, identity(3), amount = 0f).isInert)
        assertFalse(ColorLut.fromSamples("x", 3, identity(3)).isInert)
    }

    @Test
    fun aValueOutsideTheRangeIsClamped() {
        val lut = ColorLut.fromSamples("x", 2, FloatArray(24) { if (it % 2 == 0) -3f else 7f })
        lut.samples()!!.forEachIndexed { index, value -> assertEquals(if (index % 2 == 0) 0f else 1f, value, 0f) }
    }

    @Test
    fun theNameIsTrimmedAndCut() {
        assertEquals(MAX_LUT_NAME, ColorLut.fromSamples("  " + "a".repeat(200), 2, identity(2)).name.length)
    }

    @Test
    fun aRecipeKeepsItsLookThroughItsJson() {
        val lut = ColorLut.fromSamples("Teal", 4, identity(4), amount = 60f)
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "", lut = lut)
        val json = Json { encodeDefaults = true }
        val back = json.decodeFromString<EditRecipe>(json.encodeToString(recipe))
        assertEquals(lut, back.lut)
    }

    @Test
    fun aRecipeWrittenBeforeHasNone() {
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "")
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        assertNull(json.decodeFromString<EditRecipe>(json.encodeToString(recipe)).lut)
        assertEquals(LUT_PROCESS_VERSION, CURRENT_PROCESS_VERSION)
    }

    @Test
    fun theLutIsAPartOfTheLookThatFiltersAndOldRecipesLeaveAlone() {
        assertTrue(RecipePart.LUT in RecipePart.LOOK)
        assertFalse(RecipePart.LUT in RecipePart.LEGACY)
        assertFalse(RecipePart.LUT in RecipePart.BUILT_IN)
    }

    @Test
    fun applyingTheLutPartWritesItOverThePhotosAndNothingElseDoes() {
        val blank = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "")
        val lut = ColorLut.fromSamples("x", 2, identity(2))
        val source = blank.copy(lut = lut)
        assertEquals(lut, blank.withPartsFrom(source, setOf(RecipePart.LUT)).lut)
        assertNull(blank.withPartsFrom(source, setOf(RecipePart.EXPOSURE)).lut)
        assertNull(source.withPartsFrom(blank, setOf(RecipePart.LUT)).lut)
        assertEquals(lut, source.withPartsFrom(blank, setOf(RecipePart.EXPOSURE)).lut)
    }
}
