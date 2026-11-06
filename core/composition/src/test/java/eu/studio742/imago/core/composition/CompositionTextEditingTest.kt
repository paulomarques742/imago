package eu.studio742.imago.core.composition

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompositionTextEditingTest {
    private val text = CompositionElement.Text("text", ElementTransform(NormalizedRect(.2f, .3f, .4f, .2f)), 0, "hello world")
    private fun layout(text: CompositionElement.Text, width: Float = 50f) = CompositionTextLayout.measure(text,
        width, 100f, 1f, CompositionTextLayout.Metrics(-8f, 2f)) { it.length * 10f }

    @Test fun `old text and brand kits retain their layout defaults`() {
        val encoded = Json.encodeToString(text)
        assertFalse(encoded.contains("autoHeight"))
        val decoded = Json.decodeFromString<CompositionElement.Text>(encoded)
        assertFalse(decoded.autoHeight)
        assertEquals(TextVerticalAlignment.CENTER, decoded.verticalAlignment)
        assertTrue(Json.decodeFromString<BrandKit>("""{"updatedAt":""}""").textStyles.isEmpty())
    }

    @Test fun `vertical alignment uses background insets and keeps natural block height`() {
        val t = text.copy(text = "ab\ncd", backgroundArgb = 1, backgroundPadding = 5f)
        val top = layout(t.copy(verticalAlignment = TextVerticalAlignment.TOP))
        val center = layout(t)
        val bottom = layout(t.copy(verticalAlignment = TextVerticalAlignment.BOTTOM))
        assertEquals(30f, top.contentHeight)
        assertEquals(listOf(13f, 23f), top.lines.map { it.baseline })
        assertEquals(listOf(48f, 58f), center.lines.map { it.baseline })
        assertEquals(listOf(83f, 93f), bottom.lines.map { it.baseline })
    }

    @Test fun `soft line breaks map selection back to original words`() {
        val t = text.copy(text = "hello world\n\nagain")
        val l = layout(t).lines
        assertEquals(listOf(0, 6, 12, 13), l.map { it.startOffset })
        val m = t.visualMapping(l)
        assertEquals("hello\nworld\n\nagain", m.text)
        assertEquals(6, m.visualToOriginal[6])
        assertEquals(t.text.length, m.visualToOriginal.last())
        assertEquals(m.text.length, m.originalToVisual.last())
        assertEquals(m.originalToVisual.sorted(), m.originalToVisual)
    }

    @Test fun `capitals with expanding letters and emoji keep valid IME offsets`() {
        val t = text.copy(text = "ß 😀\nção", allCaps = true)
        val m = t.visualMapping(layout(t, 200f).lines)
        assertEquals("SS 😀\nÇÃO", m.text)
        assertEquals(t.text.length + 1, m.originalToVisual.size)
        assertEquals(m.text.length + 1, m.visualToOriginal.size)
        assertTrue(m.originalToVisual.all { it in 0..m.text.length })
        assertTrue(m.visualToOriginal.all { it in 0..t.text.length })
    }

    @Test fun `empty and trailing newlines have editable caret positions`() {
        listOf("", "\n", "a\n", "a  b", "😀😀", "    ").forEach { value ->
            val t = text.copy(text = value)
            val m = t.visualMapping(layout(t, 20f).lines)
            assertEquals(value.length, m.visualToOriginal.last())
            assertEquals(m.text.length, m.originalToVisual.last())
        }
    }

    @Test fun `a narrow word keeps combining marks and joined emoji together`() {
        assertEquals(listOf("a\u0301", "b"), CompositionTextLayout.wrap("a\u0301b", 10f) { it.length * 10f })
        assertEquals(listOf("👨‍👩‍👧‍👦", "x"), CompositionTextLayout.wrap("👨‍👩‍👧‍👦x", 10f) { it.length * 10f })
    }

    private fun anchor(transform: ElementTransform, x: Float, y: Float, aspect: Float): Pair<Float, Float> {
        val b = transform.bounds
        val (dx, dy) = rotateVector((x - .5f) * b.width * aspect, (y - .5f) * b.height, transform.rotationDegrees)
        return (b.x + b.width / 2f) * aspect + dx to b.y + b.height / 2f + dy
    }

    @Test fun `corner resize scales typography and keeps the rotated opposite corner`() {
        for (aspect in listOf(.5625f, 1f, 1.8f)) for (corner in ResizeCorner.entries) {
            val before = text.copy(transform = text.transform.copy(rotationDegrees = 37f), shadowRadius = 10f)
            val resized = listOf(before).resizedSelectionFromCorner(corner, .04f, .03f, 1, aspect).single() as CompositionElement.Text
            val ax = if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT) 1f else 0f
            val ay = if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT) 1f else 0f
            val a = anchor(before.transform, ax, ay, aspect)
            val b = anchor(resized.transform, ax, ay, aspect)
            assertEquals(a.first, b.first, .00001f); assertEquals(a.second, b.second, .00001f)
            val ratio = resized.fontSize / before.fontSize
            assertEquals(before.transform.bounds.width * ratio, resized.transform.bounds.width, .00001f)
            assertEquals(before.transform.bounds.height * ratio, resized.transform.bounds.height, .00001f)
            assertEquals(10f * ratio, resized.shadowRadius, .00001f)
            assertEquals(before.strokeWidth, resized.strokeWidth)
        }
    }

    @Test fun `side resize changes width without changing font and anchors opposite edge`() {
        val before = text.copy(transform = text.transform.copy(rotationDegrees = 75f))
        val after = before.withTextWidth(.65f, fromLeft = true, aspect = .5625f)
        val a = anchor(before.transform, 1f, 0f, .5625f)
        val b = anchor(after.transform, 1f, 0f, .5625f)
        assertEquals(a.first, b.first, .00001f); assertEquals(a.second, b.second, .00001f)
        assertEquals(before.fontSize, after.fontSize)
    }

    @Test fun `named styles survive serialization without copying text identity or placement`() {
        val source = text.copy(text = "private words", fontSize = 90f, shadowArgb = 123, autoHeight = true)
        val kit = BrandKit(updatedAt = "", textStyles = listOf(NamedCompositionTextStyle("style", "Title", source.textStyle())))
        val encoded = Json.encodeToString(kit)
        assertFalse(encoded.contains("private words")); assertFalse(encoded.contains("transform"))
        val restored = Json.decodeFromString<BrandKit>(encoded)
        val target = text.copy(id = "target", text = "different words", groupId = "group", repeatOnPages = setOf(0))
        val result = target.withTextStyle(restored.textStyles.single().style)
        assertEquals(target.text, result.text); assertEquals(target.id, result.id)
        assertEquals(target.transform, result.transform); assertEquals(target.groupId, result.groupId)
        assertEquals(target.repeatOnPages, result.repeatOnPages); assertEquals(90f, result.fontSize)
    }

    @Test fun `baseline snapping aligns different font sizes and can be pushed through`() {
        val other = text.copy(id = "other", transform = ElementTransform(NormalizedRect(.65f, .45f, .25f, .3f)))
        val project = CompositionProject(id = "p", name = "p", format = PageFormatPreset.SQUARE_1_1.format,
            pages = listOf(CompositionPage("page", 0)), elements = listOf(text, other), createdAt = "", updatedAt = "")
        val snap = CompositionMoveSnapping(project, setOf(text.id), textBaselines = mapOf(
            text.id to listOf(CompositionTextBaseline(.36f, .2f, .6f)),
            other.id to listOf(CompositionTextBaseline(.52f, .65f, .9f))))
        val result = snap.moveBy(0f, .155f, .001f, .01f)
        assertEquals(.16f, result.dy, .00001f)
        assertTrue(result.guides.any { it is CompositionGuide.Alignment && it.axis == GuideAxis.Y && it.coordinate == .52f })
        assertTrue(snap.moveBy(0f, .03f, .001f, .01f).guides.none { it is CompositionGuide.Alignment && it.coordinate == .52f })
    }
}
