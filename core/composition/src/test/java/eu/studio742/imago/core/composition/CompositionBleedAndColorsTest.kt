package eu.studio742.imago.core.composition

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionBleedAndColorsTest {
    private fun shape(fill: Long = 0xFFFFFFFF, stroke: Long = 0xFFFFFFFF) = CompositionElement.Shape(
        "shape", ElementTransform(NormalizedRect(.2f, .2f, .4f, .4f)), 0, ShapeKind.RECTANGLE,
        fillArgb = fill, strokeArgb = stroke,
    )

    private fun project(vararg elements: CompositionElement) = CompositionProject(
        id = "p", name = "P", format = PageFormatPreset.SQUARE_1_1.format,
        pages = listOf(CompositionPage("0", 0)), elements = elements.toList(),
        background = CompositionBackground.Solid(0xFF111111), createdAt = "t", updatedAt = "t",
    )

    @Test
    fun `a shape may hang off the page but keeps a quarter of itself on it`() {
        val shape = shape()
        val left = shape.clampedBounds(NormalizedRect(-5f, .2f, .4f, .4f), 1)
        assertEquals(-.3f, left.x, .0001f)
        val bottom = shape.clampedBounds(NormalizedRect(.2f, 5f, .4f, .4f), 1)
        assertEquals(.9f, bottom.y, .0001f)
        val inside = shape.clampedBounds(NormalizedRect(-.1f, .2f, .4f, .4f), 1)
        assertEquals(-.1f, inside.x, .0001f)
    }

    @Test
    fun `a text still stays inside the page`() {
        val text = CompositionElement.Text("t", ElementTransform(NormalizedRect(.2f, .2f, .4f, .4f)), 0, "x")
        assertEquals(0f, text.clampedBounds(NormalizedRect(-5f, .2f, .4f, .4f), 1).x, 0f)
    }

    @Test
    fun `used colours come once, in order, without the transparent ones`() {
        val colours = project(
            shape(fill = 0xFFFF0000, stroke = 0x00000000),
            shape(fill = 0xFFFF0000, stroke = 0xFF00FF00),
        ).usedColors()
        assertEquals(listOf(0xFF111111, 0xFFFF0000, 0xFF00FF00), colours)
    }
}
