package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.ColorLut
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CubeExportTest {
    private fun saved(recipe: EditRecipe, parts: Set<RecipePart>, name: String = "My look") =
        SavedRecipe("id", name, "", recipe, "", "", parts = parts)

    private fun blank() = EditRecipe(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "")

    private fun linesOf(export: CubeExport) = export.bytes.decodeToString().lines()

    private fun tableOf(export: CubeExport): ColorLut {
        val numbers = linesOf(export).filter { it.isNotEmpty() && (it[0].isDigit() || it[0] == '-') && !it.startsWith("DOMAIN") }
        val values = FloatArray(numbers.size * 3)
        numbers.forEachIndexed { index, line -> line.split(' ').forEachIndexed { channel, text -> values[index * 3 + channel] = text.toFloat() } }
        return ColorLut.fromSamples("exported", CUBE_EXPORT_SIZE, values)
    }

    @Test
    fun theFileSaysItsSizeItsTitleAndHasEveryPoint() {
        val export = encodeCubeFile(saved(blank(), RecipePart.LOOK, name = "Warm \"Sun\""), "fallback")
        val lines = linesOf(export)
        assertTrue(lines.any { it == "TITLE \"Warm 'Sun'\"" })
        assertTrue(lines.any { it == "LUT_3D_SIZE $CUBE_EXPORT_SIZE" })
        assertEquals(CUBE_EXPORT_SIZE * CUBE_EXPORT_SIZE * CUBE_EXPORT_SIZE, lines.count { it.isNotEmpty() && it[0].isDigit() })
        assertEquals("Warm _Sun_.cube", export.fileName)
    }

    @Test
    fun aRecipeWithNothingExportsTheIdentity() {
        val table = Lut3D.of(tableOf(encodeCubeFile(saved(blank(), RecipePart.LOOK), "x")))!!
        listOf(floatArrayOf(0.2f, 0.5f, 0.8f), floatArrayOf(0.9f, 0.1f, 0.4f)).forEach { input ->
            val out = input.copyOf()
            table.apply(out)
            input.indices.forEach { assertEquals(input[it], out[it], 0.01f) }
        }
    }

    /** The point of the file: a program reading it draws what the recipe draws here. */
    @Test
    fun theTableDrawsWhatTheRecipeDraws() {
        val recipe = blank().copy(
            tone = Tone(exposure = 0.4f, contrast = 25f),
            presence = Presence(saturation = 30f, vibrance = 20f),
            colorGrading = ColorGrading(shadows = ColorWheel(hue = 210f, saturation = 40f), highlights = ColorWheel(hue = 40f, saturation = 40f)),
        )
        val export = encodeCubeFile(saved(recipe, RecipePart.LOOK), "x")
        val table = Lut3D.of(tableOf(export))!!
        val source = PixelBuffer(4, 2).apply {
            listOf(0x808080, 0x30A060, 0xE0C080, 0x4060C0, 0x202020, 0xF0F0F0, 0xA05030, 0x70B0D0).forEachIndexed { index, rgb ->
                pixels[index] = (0xFF shl 24) or rgb
            }
        }
        val drawn = RecipePixels.render(PixelBuffer(4, 2).also { source.pixels.copyInto(it.pixels) }, recipe)
        var total = 0f
        for (index in 0 until 8) {
            val input = floatArrayOf(
                (source.pixels[index] ushr 16 and 0xFF) / 255f, (source.pixels[index] ushr 8 and 0xFF) / 255f, (source.pixels[index] and 0xFF) / 255f,
            )
            table.apply(input)
            val expected = floatArrayOf(
                (drawn.pixels[index] ushr 16 and 0xFF) / 255f, (drawn.pixels[index] ushr 8 and 0xFF) / 255f, (drawn.pixels[index] and 0xFF) / 255f,
            )
            input.indices.forEach { channel ->
                val error = abs(expected[channel] - input[channel]) * 255f
                // The CPU path moves a near-neutral pixel by a few levels on its own, with or without a LUT.
                assertTrue("pixel $index channel $channel: ${expected[channel] * 255} drawn, ${input[channel] * 255} from the table", error <= 5f)
                total += error
            }
        }
        assertTrue("the table is off by ${total / 24} levels on average", total / 24 <= 1.5f)
    }

    @Test
    fun whatATableCannotHoldIsNamed() {
        val recipe = blank().copy(
            tone = Tone(highlights = -40f, exposure = 0.5f),
            presence = Presence(clarity = 30f),
        )
        val export = encodeCubeFile(saved(recipe, RecipePart.LOOK), "x")
        assertEquals(setOf(CubeLeftOut.DETAIL_AND_EFFECTS, CubeLeftOut.HIGHLIGHTS_AND_SHADOWS), export.leftOut)
        assertEquals(emptySet<CubeLeftOut>(), encodeCubeFile(saved(blank().copy(tone = Tone(exposure = 0.5f)), RecipePart.LOOK), "x").leftOut)
    }

    @Test
    fun aPartTheRecipeDoesNotApplyIsNotWritten() {
        val recipe = blank().copy(tone = Tone(exposure = 1f), presence = Presence(saturation = 60f))
        val onlySaturation = Lut3D.of(tableOf(encodeCubeFile(saved(recipe, setOf(RecipePart.SATURATION)), "x")))!!
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f)
        onlySaturation.apply(grey)
        // Exposure would have lifted the grey; saturation does nothing to it.
        assertEquals(0.5f, grey[1], 0.02f)
    }
}
