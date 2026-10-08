package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Perspective
import eu.studio742.imago.core.model.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The perspective through the CPU engine: the desktop's preview and export, and the Android thumbnails. */
class PerspectivePixelsTest {
    private val grey = PixelBuffer(60, 40).apply { pixels.fill(0xFF808080.toInt()) }

    private fun recipe(perspective: Perspective, tone: Tone = Tone()) = EditRecipe(
        assetId = "a1",
        originalChecksum = "",
        createdAt = "2026-10-07T00:00:00Z",
        updatedAt = "2026-10-07T00:00:00Z",
        tone = tone,
        geometry = Geometry(perspective = perspective),
    )

    /**
     * What the photo does not cover is white, and stays white: a darker exposure, which turns the
     * photo darker, does not turn the uncovered frame grey.
     */
    @Test
    fun theUncoveredFrameIsWhiteWhateverTheAdjustments() {
        val rendered = RecipePixels.render(
            grey,
            recipe(Perspective(scale = -40f, constrainCrop = false), Tone(exposure = -2f)),
        )

        assertEquals(WHITE, rendered.pixels[0])
        assertEquals(WHITE, rendered.pixels[rendered.pixels.size - 1])
        assertNotEquals(WHITE, rendered.pixels[rendered.height / 2 * rendered.width + rendered.width / 2])
    }

    /** With the crop constrained nothing is white, however strong the correction. */
    @Test
    fun aConstrainedFrameShowsOnlyThePhoto() {
        val rendered = RecipePixels.render(grey, recipe(Perspective(vertical = -100f, horizontal = 60f, aspect = 40f)))

        assertEquals(0, rendered.pixels.count { it == WHITE })
    }

    private companion object {
        const val WHITE = -0x1
    }
}
