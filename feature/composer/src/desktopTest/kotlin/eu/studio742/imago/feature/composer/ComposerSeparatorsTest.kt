package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.*

class ComposerSeparatorsTest {
    private fun project(kind: CompositionLayoutKind = CompositionLayoutKind.GRID, count: Int = 4,
        width: Float = .00625f, color: Long = 0xFFFFFFFF, opacity: Float = 1f, rotation: Float = 0f): CompositionProject {
        val ids = List(count) { "cell-$it" }
        val layout = CompositionLayout("grid", ElementTransform(NormalizedRect(.1f, .1f, .8f, .8f), rotationDegrees = rotation),
            layoutTree(ids, kind), kind = kind, margin = .1f, gap = .12f, separators = LayoutStroke(color, opacity, width))
        val elements = ids.mapIndexed { i, id -> CompositionElement.Shape(id, ElementTransform(NormalizedRect(.1f, .1f, .2f, .2f)),
            i, ShapeKind.RECTANGLE, fillArgb = 0x00000000, strokeWidth = 0f) }
        return CompositionProject(id = "sample", name = "Grid", format = PageFormatPreset.SQUARE_1_1.format, pages = listOf(CompositionPage("page", 0)),
            elements = layout.applyTo(elements, 1f), layouts = listOf(layout),
            background = CompositionBackground.Solid(0xFF000000), createdAt = "", updatedAt = "")
    }
    private fun image(project: CompositionProject) = ImageIO.read(ByteArrayInputStream(CompositionRenderer().render(project, 0, emptyMap(), 400)))

    @Test fun `thin lines remain continuous across gaps wider than the stroke`() {
        val image = image(project())
        for (position in 74..325) {
            assertTrue("Vertical gap at $position", (image.getRGB(200, position) and 255) > 245)
            assertTrue("Horizontal gap at $position", (image.getRGB(position, 200) and 255) > 245)
        }
    }

    @Test fun `asymmetric layouts connect each T junction through the media gutter`() {
        val source = project(CompositionLayoutKind.FEATURE_LEFT, count = 3)
        val geometry = source.layouts.single().geometry(1f)
        val branch = geometry.separators.single { it.splitId == "small" }
        val x = ((.1f + branch.x1 * .8f) * 400).roundToInt() + 2
        val y = ((.1f + branch.y1 * .8f) * 400).toInt()
        val image = image(source)
        assertTrue((image.getRGB(x, y) and 255) > 230)
        assertTrue((image.getRGB(x + 5, y) and 255) > 230)
    }

    @Test fun `thick separators have flat ends and stop exactly at the inner margin`() {
        val image = image(project(CompositionLayoutKind.ROW, count = 2, width = .08f))
        assertEquals(0, image.getRGB(200, 70) and 255)
        assertEquals(0, image.getRGB(200, 329) and 255)
        assertTrue((image.getRGB(190, 74) and 255) > 245)
        assertTrue((image.getRGB(190, 325) and 255) > 245)
    }

    @Test fun `even oversized strokes stay clipped inside the grid margin`() {
        val image = image(project(CompositionLayoutKind.ROW, count = 12, width = .25f))
        for (y in 0 until 400) for (x in 0 until 400) {
            if (x < 71 || x > 329 || y < 71 || y > 329) assertEquals("Overflow at ($x, $y)", 0, image.getRGB(x, y) and 255)
        }
        assertTrue((image.getRGB(100, 200) and 255) > 245)
    }

    @Test fun `junction opacity is applied once including the color and group alpha`() {
        val source = project(color = 0x80FFFFFF, opacity = .5f, width = .03f).let {
            it.copy(layouts = listOf(it.layouts.single().copy(frame = it.layouts.single().frame.copy(opacity = .6f))))
        }
        val image = image(source)
        val crossing = image.getRGB(200, 200) and 255
        val line = image.getRGB(200, 120) and 255
        assertEquals(line.toDouble(), crossing.toDouble(), 1.0)
        assertTrue(crossing in 37..40)
    }

    @Test fun `rotated separators remain inside the rotated inner grid boundary`() {
        val image = image(project(width = .08f, rotation = 37f))
        val angle = Math.toRadians(-37.0)
        var painted = 0
        for (y in 0 until 400) for (x in 0 until 400) {
            if ((image.getRGB(x, y) and 255) < 5) continue
            painted++
            val dx = x + .5 - 200; val dy = y + .5 - 200
            val localX = dx * cos(angle) - dy * sin(angle)
            val localY = dx * sin(angle) + dy * cos(angle)
            assertTrue("Rotated overflow at ($x, $y)", abs(localX) <= 129.5 && abs(localY) <= 129.5)
        }
        assertTrue(painted > 500)
    }
}
