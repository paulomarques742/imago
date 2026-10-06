package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.EditRecipe

/**
 * Step 12 from start to finish, along the path the export and desktop use.
 *
 * `PhotoEffectsTest` checks the arithmetic; this checks that it reaches the pixels. They are
 * different things: the pass that applies step 12 only runs when one of the `needs…` asks for it, and
 * a forgotten gate gives a colour grade that does absolutely nothing, without an error along the way
 * or a red maths test.
 */
class ColorGradingPixelsTest {
    /** Three horizontal strips: shadow, midtone and highlight, all grey. */
    private fun greyBands(): PixelBuffer = PixelBuffer(4, 3).apply {
        listOf(0x22, 0x80, 0xE8).forEachIndexed { row, level ->
            for (x in 0 until 4) {
                pixels[row * 4 + x] = (0xFF shl 24) or (level shl 16) or (level shl 8) or level
            }
        }
    }

    private fun recipe(grading: ColorGrading) = EditRecipe(
        assetId = "grade",
        originalChecksum = "",
        createdAt = "",
        updatedAt = "",
        colorGrading = grading,
    )

    private fun PixelBuffer.at(row: Int): Triple<Int, Int, Int> {
        val pixel = pixels[row * width]
        return Triple(pixel ushr 16 and 0xFF, pixel ushr 8 and 0xFF, pixel and 0xFF)
    }

    /** The classic: cool shadows, warm highlights, and each colour in its strip. */
    @Test
    fun theWheelsPaintTheirOwnBandsOfTheImage() {
        val rendered = RecipePixels.render(
            greyBands(),
            recipe(
                ColorGrading(
                    shadows = ColorWheel(hue = 215f, saturation = 70f),
                    highlights = ColorWheel(hue = 35f, saturation = 70f),
                    blending = 20f,
                ),
            ),
        )

        val (shadowRed, _, shadowBlue) = rendered.at(0)
        val (highlightRed, _, highlightBlue) = rendered.at(2)
        assertTrue("the shadow did not turn cool: $shadowRed/$shadowBlue", shadowBlue > shadowRed + 8)
        assertTrue("the highlight did not turn warm: $highlightRed/$highlightBlue", highlightRed > highlightBlue + 8)
    }

    /** The global wheel ignores tone: the three strips turn in the same direction. */
    @Test
    fun theGlobalWheelPaintsEveryBand() {
        val rendered = RecipePixels.render(
            greyBands(),
            recipe(ColorGrading(global = ColorWheel(hue = 120f, saturation = 60f))),
        )

        listOf(0, 1, 2).forEach { row ->
            val (red, green, blue) = rendered.at(row)
            assertTrue("strip $row did not turn green: $red/$green/$blue", green > red && green > blue)
        }
    }

    /** And a neutral grade cannot move a pixel, nor even ask for the pass. */
    @Test
    fun aNeutralGradingLeavesTheImageAlone() {
        val source = greyBands()
        val rendered = RecipePixels.render(source, recipe(ColorGrading(blending = 90f, balance = -50f)))
        assertEquals(source.pixels.toList(), rendered.pixels.toList())
    }

    /** A wheel's luminance changes the lightness of its strip and leaves the others still. */
    @Test
    fun theLuminanceOfAWheelOnlyMovesItsOwnBand() {
        val source = greyBands()
        val rendered = RecipePixels.render(
            source,
            recipe(ColorGrading(highlights = ColorWheel(luminance = -80f), blending = 0f)),
        )

        assertTrue(rendered.at(2).first < source.at(2).first - 8)
        assertEquals(source.at(0).first, rendered.at(0).first)
    }
}
