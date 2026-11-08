package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.composition.StrokePoint
import eu.studio742.imago.core.designsystem.ImagoTheme
import org.jetbrains.skia.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The page miniature in the page strip and the project list.
 *
 * It painted every freehand stroke, and later every text, as a solid rectangle of its colour, so a
 * diagonal line or a word showed up as a square covering its whole box.
 */
@OptIn(ExperimentalComposeUiApi::class)
class CompositionPagePreviewTest {
    private val blue = 0xFF204080L
    private val red = 0xFFE04020L
    private val box = ElementTransform(NormalizedRect(.25f, .25f, .5f, .5f))
    private val diagonal = listOf(StrokePoint(0f, 0f, startsStroke = true), StrokePoint(1f, 1f))

    private fun preview(vararg elements: CompositionElement): Bitmap {
        val project = CompositionProject(
            id = "preview", name = "Preview", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = elements.toList(),
            background = CompositionBackground.Solid(blue), createdAt = "", updatedAt = "",
        )
        val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
            ImagoTheme { CompositionPagePreview(project, project.pages.single(), { "" }, { "" }, Modifier.fillMaxSize()) }
        }
        // The second frame has the typeface, which the first one may still be reading.
        return try {
            scene.render(0L)
            Thread.sleep(200)
            Bitmap().also { it.allocN32Pixels(300, 300); scene.render(100_000_000L).readPixels(it) }
        } finally { scene.close() }
    }

    private fun assertColor(expected: Long, actual: Int, tolerance: Int = 2) {
        for (shift in listOf(0, 8, 16)) assertTrue("Channel $shift: expected ${expected.toString(16)}, actual ${actual.toUInt().toString(16)}",
            abs(((expected ushr shift) and 255).toInt() - (actual ushr shift and 255)) <= tolerance)
    }

    @Test fun `a freehand stroke is drawn as a line and not as a filled box`() {
        val image = preview(CompositionElement.Drawing("pen", box, 1, StrokeKind.PEN, diagonal, red, strokeWidth = 36f))
        assertColor(red, image.getColor(150, 150))
        // Inside the stroke's box, far from the line: the background shows through.
        assertColor(blue, image.getColor(90, 210))
        assertColor(blue, image.getColor(210, 90))
    }

    @Test fun `a text is drawn as letters and not as a filled box`() {
        val text = CompositionElement.Text("title", box, 1, "Hi", fontSize = 260f, colorArgb = red)
        val image = preview(text)
        // The box's top corners are far from the letters, which sit in the middle of the block.
        assertColor(blue, image.getColor(80, 80))
        assertColor(blue, image.getColor(220, 80))
        var letter = 0
        for (x in 75 until 225) for (y in 75 until 225) {
            val c = image.getColor(x, y)
            if (abs((c ushr 16 and 255) - 0xE0) <= 24 && abs((c and 255) - 0x20) <= 24) letter++
        }
        assertTrue("The letters are painted: $letter pixels", letter > 300)
        assertTrue("Not the whole box: $letter pixels", letter < 150 * 150 / 2)
    }

    @Test fun `an eraser stroke leaves nothing of its own in the miniature`() {
        val image = preview(CompositionElement.Drawing("eraser", box, 1, StrokeKind.ERASER, diagonal, red, strokeWidth = 36f))
        assertColor(blue, image.getColor(150, 150))
    }
}
