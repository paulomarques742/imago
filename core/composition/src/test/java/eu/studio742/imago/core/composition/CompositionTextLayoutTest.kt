package eu.studio742.imago.core.composition

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The layout shared by the text painters and the inspector's mutations.
 *
 * It is measured with a fake font in which every character is 10 wide: the arithmetic is in plain
 * sight and what is tested are the rules, not a font.
 */
class CompositionTextLayoutTest {
    private val measure: (String) -> Float = { it.length * 10f }
    private val metrics = CompositionTextLayout.Metrics(ascent = -8f, descent = 2f)

    private fun text(value: String, block: CompositionElement.Text.() -> CompositionElement.Text = { this }) =
        CompositionElement.Text("t", ElementTransform(NormalizedRect(0f, 0f, .5f, .5f)), 0, value).block()

    @Test
    fun `a paragraph that fits comes out exactly as written`() {
        assertEquals(listOf("ab  cd"), CompositionTextLayout.wrap("ab  cd", 100f, measure))
    }

    @Test
    fun `words move to the next line when they do not fit`() {
        assertEquals(listOf("big tree", "by sea"), CompositionTextLayout.wrap("big tree by sea", 80f, measure))
    }

    @Test
    fun `a word wider than the box breaks where it stops fitting`() {
        assertEquals(listOf("abcd", "efgh", "ij"), CompositionTextLayout.wrap("abcdefghij", 40f, measure))
        assertEquals(listOf("ok", "abcd", "ef"), CompositionTextLayout.wrap("ok abcdef", 40f, measure))
    }

    @Test
    fun `the two halves of an emoji are never split`() {
        val lines = CompositionTextLayout.wrap("a😀b", 10f, measure)
        assertEquals(listOf("a", "😀", "b"), lines)
    }

    @Test
    fun `a very narrow box advances one character per line instead of getting stuck`() {
        assertEquals(listOf("a", "b", "c"), CompositionTextLayout.wrap("abc", 1f, measure))
    }

    @Test
    fun `written breaks are kept and the block is centred vertically`() {
        val lines = CompositionTextLayout.layout(text("ab\ncd"), 100f, 100f, 1f, metrics, measure)
        // Two lines of natural height 10: the block is 20 tall and starts at 40.
        assertEquals(listOf("ab", "cd"), lines.map { it.text })
        assertEquals(listOf(48f, 58f), lines.map { it.baseline })
    }

    @Test
    fun `line spacing multiplies the natural height`() {
        val lines = CompositionTextLayout.layout(text("a\nb") { copy(lineSpacing = 2f) }, 100f, 100f, 1f, metrics, measure)
        assertEquals(20f, lines[1].baseline - lines[0].baseline)
    }

    @Test
    fun `alignment puts the line on the left, in the centre or on the right`() {
        fun x(alignment: TextAlignment) =
            CompositionTextLayout.layout(text("abcd") { copy(alignment = alignment) }, 100f, 50f, 1f, metrics, measure).single().x
        assertEquals(0f, x(TextAlignment.START))
        assertEquals(30f, x(TextAlignment.CENTER))
        assertEquals(60f, x(TextAlignment.END))
    }

    @Test
    fun `with a background the text is inset by the scaled padding, and breaks lines at that width`() {
        val element = text("abcd efgh") { copy(backgroundArgb = 0xFF000000, backgroundPadding = 10f) }
        // Scale 2: the padding of 10 is worth 20 on each side, leaving 60 of width.
        val lines = CompositionTextLayout.layout(element, 100f, 100f, 2f, metrics, measure)
        assertEquals(listOf("abcd", "efgh"), lines.map { it.text })
        assertEquals(20f, lines.first().x)
        // With the background off, the padding stops counting, even if saved.
        val plain = CompositionTextLayout.layout(element.copy(backgroundArgb = null), 100f, 100f, 2f, metrics, measure)
        assertEquals(listOf("abcd efgh"), plain.map { it.text })
    }

    @Test
    fun `all caps change what is drawn and not the saved text`() {
        val element = text("ação") { copy(allCaps = true) }
        assertEquals("AÇÃO", CompositionTextLayout.displayText(element))
        assertEquals("ação", element.text)
    }

    @Test
    fun `the weights offered are the hundreds the family has`() {
        assertEquals((100..900 step 100).toList(), CompositionFonts.Inter.offeredWeights)
        assertEquals(listOf(200, 300, 400, 500, 600, 700), CompositionFonts.Oswald.offeredWeights)
        assertEquals(listOf(400), CompositionFonts.BebasNeue.offeredWeights)
    }

    @Test
    fun `the weight snaps to the closest the family offers, and switching family keeps it`() {
        val lora = text("x") { copy(fontFamily = CompositionFonts.Lora.id) }
        assertEquals(700, lora.withWeight(900).fontWeight)
        assertEquals(500, lora.withWeight(530).fontWeight)
        val black = text("x").withWeight(900)
        assertEquals(900, black.withFont(CompositionFonts.Lora.id).fontWeight)
        assertEquals(CompositionFonts.Lora.id, black.withFont("Lora").fontFamily)
    }

    @Test
    fun `the measurements stay within the limits`() {
        val element = text("x")
        assertEquals(MAX_TEXT_LETTER_SPACING, element.withLetterSpacing(9f).letterSpacing)
        assertEquals(MIN_TEXT_LINE_SPACING, element.withLineSpacing(0f).lineSpacing)
        assertEquals(MIN_TEXT_STROKE_WIDTH, element.withStrokeWidth(0f).strokeWidth)
        assertEquals(-MAX_TEXT_SHADOW_OFFSET, element.withShadowOffset(x = -999f).shadowOffsetX)
        assertEquals(0f, element.withBackgroundCorner(-3f).backgroundCornerRadius)
    }

    @Test
    fun `a text saved before the effects opens with none of them on`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            CompositionElement.serializer(),
            """{"type":"text","id":"t","transform":{"bounds":{"x":0,"y":0,"width":0.5,"height":0.5}},"zIndex":0,"text":"Hello"}""",
        ) as CompositionElement.Text
        assertNull(old.strokeArgb)
        assertNull(old.shadowArgb)
        assertNull(old.backgroundArgb)
        assertEquals(false, old.allCaps)
        assertEquals(0f, old.letterSpacing)
        assertEquals(1f, old.lineSpacing)
    }
}
