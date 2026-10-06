package eu.studio742.imago.feature.composer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.PageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The text as the desktop renderer exports it, which is the same painter as the stage.
 *
 * The pages are black and each effect has a pure colour, so it is known where each pixel comes from.
 */
class CompositionTextPainterDesktopTest {
    private val renderer = CompositionRenderer()

    private fun render(text: CompositionElement.Text, width: Int = 1080, height: Int = 1080): BufferedImage = runBlocking {
        SkiaCompositionFonts.typeface(text.fontFamily, text.fontWeight)
        val project = CompositionProject(
            id = "p", name = "texto", format = PageFormat.custom(width, height),
            pages = listOf(CompositionPage("pagina", 0)), createdAt = "", updatedAt = "",
            background = CompositionBackground.Solid(0xFF000000),
            elements = listOf(text),
        )
        ImageIO.read(ByteArrayInputStream(renderer.render(project, 0, emptyMap())))
    }

    private fun text(block: CompositionElement.Text.() -> CompositionElement.Text = { this }) = CompositionElement.Text(
        "t", ElementTransform(NormalizedRect(.1f, .3f, .8f, .4f)), 0, "Imago",
        fontFamily = CompositionFonts.Inter.id, fontSize = 160f, fontWeight = 700, colorArgb = 0xFFFFFFFF,
    ).block()

    /** How many pixels are close to [rgb]. */
    private fun BufferedImage.count(rgb: Int): Int {
        var count = 0
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = getRGB(x, y)
            val close = (0..2).all { shift ->
                kotlin.math.abs(((pixel shr (shift * 8)) and 0xFF) - ((rgb shr (shift * 8)) and 0xFF)) < 24
            }
            if (close) count++
        }
        return count
    }

    /** The width of what was painted with [rgb], from one end to the other. */
    private fun BufferedImage.inkWidth(rgb: Int): Int {
        var left = width; var right = -1
        for (y in 0 until height) for (x in 0 until width) {
            if ((getRGB(x, y) and 0xFFFFFF) == rgb) { left = minOf(left, x); right = maxOf(right, x) }
        }
        return right - left + 1
    }

    @Test
    fun `the letter grows with the page, as on the stage and on Android`() {
        // The box is a fraction of the page, and so is the letter: on the page twice as wide, the same
        // text takes twice as much. Before, desktop used the raw size and came out the same width.
        val narrow = render(text(), 1080, 1080).inkWidth(0xFFFFFF)
        val wide = render(text(), 2160, 1080).inkWidth(0xFFFFFF)
        assertEquals(2.0, wide.toDouble() / narrow, 0.05)
    }

    @Test
    fun `the outline appears around the letter and the fill stays on top`() {
        val plain = render(text())
        assertEquals(0, plain.count(0xFF0000))
        val outlined = render(text { copy(strokeArgb = 0xFFFF0000, strokeWidth = 0.08f) })
        assertTrue(outlined.count(0xFF0000) > 1000)
        // The fill is still there, on top of the stroke.
        assertTrue(outlined.count(0xFFFFFF) > 1000)
    }

    @Test
    fun `the shadow comes out offset and only when it is on`() {
        val off = render(text { copy(shadowRadius = 0f, shadowOffsetY = 40f) })
        assertEquals(0, off.count(0x00FF00))
        // A hard shadow, with radius zero: it is a shadow too, and on Android setShadowLayer would not draw it.
        val hard = render(text { copy(shadowArgb = 0xFF00FF00, shadowRadius = 0f, shadowOffsetY = 40f) })
        assertTrue(hard.count(0x00FF00) > 1000)
    }

    @Test
    fun `the background fills the box with rounded corners`() {
        val image = render(text { copy(backgroundArgb = 0xFF0000FF, backgroundCornerRadius = 100f) })
        // The box goes from (108, 324) to (972, 756) on a 1080 page.
        assertEquals(0x0000FF, image.getRGB(540, 330) and 0xFFFFFF)
        assertFalse("the corner should be rounded", (image.getRGB(110, 326) and 0xFFFFFF) == 0x0000FF)
    }

    @Test
    fun `all caps and letter spacing widen the text`() {
        val base = render(text { copy(text = "imago") }).inkWidth(0xFFFFFF)
        val caps = render(text { copy(text = "imago", allCaps = true) }).inkWidth(0xFFFFFF)
        val spaced = render(text { copy(text = "imago", letterSpacing = 0.2f) }).inkWidth(0xFFFFFF)
        assertTrue("all caps: $caps against $base", caps > base)
        assertTrue("spaced: $spaced against $base", spaced > base * 1.2)
    }
}
