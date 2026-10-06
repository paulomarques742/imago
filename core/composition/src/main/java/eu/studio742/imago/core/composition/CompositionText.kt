package eu.studio742.imago.core.composition

import kotlin.math.max

const val MIN_TEXT_LETTER_SPACING = -0.1f
const val MAX_TEXT_LETTER_SPACING = 0.5f
const val MIN_TEXT_LINE_SPACING = 0.6f
const val MAX_TEXT_LINE_SPACING = 2.5f
const val MIN_TEXT_STROKE_WIDTH = 0.01f
const val MAX_TEXT_STROKE_WIDTH = 0.25f
const val MAX_TEXT_SHADOW_RADIUS = 60f
const val MAX_TEXT_SHADOW_OFFSET = 60f
const val MAX_TEXT_BACKGROUND_PADDING = 120f
const val MAX_TEXT_BACKGROUND_CORNER = 120f

/**
 * How a [CompositionElement.Text] is split into lines and where each line sits.
 *
 * There used to be three drawings of the same text: the stage, the Android exporter and the desktop
 * renderer. Each one broke lines its own way, and only the stage centred them vertically. The file
 * could carry a text somewhere else, or with other breaks, than the one that had been seen. Layout
 * now lives here, once. Each platform only measures and draws, always with the same bundled font.
 *
 * The rules:
 * - written line breaks are kept; within a paragraph, words go to the next line when they do not
 *   fit, and a word wider than the box breaks where it stops fitting;
 * - the distance between lines is the font's natural height times
 *   [CompositionElement.Text.lineSpacing];
 * - the block is centred vertically in the box, and is not clipped if it overflows;
 * - with the background on, the text is inset by [CompositionElement.Text.backgroundPadding] on each
 *   side.
 */
object CompositionTextLayout {

    /** A line ready to draw: [x] is the left and [baseline] the baseline, in the box. */
    data class Line(val text: String, val x: Float, val baseline: Float, val width: Float)

    /** What the platform knows about the font at the drawing size. [ascent] is negative, as on Android and Skia. */
    data class Metrics(val ascent: Float, val descent: Float)

    /** The text as it is drawn. */
    fun displayText(element: CompositionElement.Text): String =
        if (element.allCaps) element.text.uppercase() else element.text

    /**
     * The lines of [element] in a box of [boxWidth] by [boxHeight] drawing pixels, with [scale]
     * drawing pixels per model pixel.
     *
     * [measure] returns the width of a piece of text with the letter spacing already applied, which
     * is what the drawing will take up.
     */
    fun layout(
        element: CompositionElement.Text,
        boxWidth: Float,
        boxHeight: Float,
        scale: Float,
        metrics: Metrics,
        measure: (String) -> Float,
    ): List<Line> {
        val inset = if (element.backgroundArgb != null) element.backgroundPadding * scale else 0f
        val available = max(boxWidth - 2 * inset, 1f)
        val texts = displayText(element).split('\n').flatMap { wrap(it, available, measure) }
        val natural = metrics.descent - metrics.ascent
        val pitch = natural * element.lineSpacing
        val blockHeight = pitch * (texts.size - 1) + natural
        val firstBaseline = (boxHeight - blockHeight) / 2 - metrics.ascent
        return texts.mapIndexed { index, text ->
            val width = measure(text)
            val x = when (element.alignment) {
                TextAlignment.START -> inset
                TextAlignment.CENTER -> inset + (available - width) / 2
                TextAlignment.END -> inset + available - width
            }
            Line(text, x, firstBaseline + index * pitch, width)
        }
    }

    /**
     * A paragraph broken into lines that fit in [available].
     *
     * The spaces between words are kept as written, and the space where the line breaks disappears.
     * That way, a paragraph that fits whole comes out exactly as written.
     */
    fun wrap(paragraph: String, available: Float, measure: (String) -> Float): List<String> {
        if (paragraph.isEmpty() || measure(paragraph) <= available) return listOf(paragraph)
        val lines = mutableListOf<String>()
        var line = ""
        for (word in paragraph.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            when {
                measure(candidate) <= available -> line = candidate
                line.isEmpty() -> line = breakWord(word, available, measure, lines)
                else -> {
                    lines += line
                    line = if (measure(word) <= available) word else breakWord(word, available, measure, lines)
                }
            }
        }
        lines += line
        return lines
    }

    /** Breaks a word wider than the box; the full parts go to [into] and it returns the rest. */
    private fun breakWord(word: String, available: Float, measure: (String) -> Float, into: MutableList<String>): String {
        var rest = word
        while (measure(rest) > available) {
            // At least one character per line, so a very narrow box does not trap the loop.
            // It advances by code point so the two halves of an emoji are never split.
            var cut = rest.offsetByCodePoints(0, 1)
            while (cut < rest.length) {
                val next = rest.offsetByCodePoints(cut, 1)
                if (measure(rest.substring(0, next)) > available) break
                cut = next
            }
            if (cut >= rest.length) break
            into += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        return rest
    }
}

/**
 * The weights offered for [CompositionFont], in steps of a hundred within what the file has.
 *
 * A variable family would accept any value in between, but each weight is a typeface built and kept
 * in memory, and nobody tells a 430 from a 400 in a composition.
 */
val CompositionFont.offeredWeights: List<Int>
    get() = (100..900 step 100).filter { it in weights }.ifEmpty { listOf(weights.first) }

/** A weight's typographic name, the same the families use in their styles. */
fun weightName(weight: Int): String = when (weight) {
    in Int.MIN_VALUE..149 -> "Thin"
    in 150..249 -> "ExtraLight"
    in 250..349 -> "Light"
    in 350..449 -> "Regular"
    in 450..549 -> "Medium"
    in 550..649 -> "SemiBold"
    in 650..749 -> "Bold"
    in 750..849 -> "ExtraBold"
    else -> "Black"
}

/**
 * Switches the family and keeps the requested weight. A Black text moving to a family without that
 * weight comes out in its closest one, and gets Black back if it returns to a family that has it.
 */
fun CompositionElement.Text.withFont(fontId: String): CompositionElement.Text =
    copy(fontFamily = CompositionFonts.resolve(fontId).id)

/** The weight in steps of a hundred, brought within what the family has. */
fun CompositionElement.Text.withWeight(weight: Int): CompositionElement.Text {
    val font = CompositionFonts.resolve(fontFamily)
    val offered = font.offeredWeights
    return copy(fontWeight = offered.minBy { kotlin.math.abs(it - weight) })
}

fun CompositionElement.Text.withLetterSpacing(value: Float): CompositionElement.Text =
    copy(letterSpacing = value.coerceIn(MIN_TEXT_LETTER_SPACING, MAX_TEXT_LETTER_SPACING))

fun CompositionElement.Text.withLineSpacing(value: Float): CompositionElement.Text =
    copy(lineSpacing = value.coerceIn(MIN_TEXT_LINE_SPACING, MAX_TEXT_LINE_SPACING))

fun CompositionElement.Text.withStrokeWidth(value: Float): CompositionElement.Text =
    copy(strokeWidth = value.coerceIn(MIN_TEXT_STROKE_WIDTH, MAX_TEXT_STROKE_WIDTH))

fun CompositionElement.Text.withShadowRadius(value: Float): CompositionElement.Text =
    copy(shadowRadius = value.coerceIn(0f, MAX_TEXT_SHADOW_RADIUS))

fun CompositionElement.Text.withShadowOffset(x: Float = shadowOffsetX, y: Float = shadowOffsetY): CompositionElement.Text =
    copy(
        shadowOffsetX = x.coerceIn(-MAX_TEXT_SHADOW_OFFSET, MAX_TEXT_SHADOW_OFFSET),
        shadowOffsetY = y.coerceIn(-MAX_TEXT_SHADOW_OFFSET, MAX_TEXT_SHADOW_OFFSET),
    )

fun CompositionElement.Text.withBackgroundPadding(value: Float): CompositionElement.Text =
    copy(backgroundPadding = value.coerceIn(0f, MAX_TEXT_BACKGROUND_PADDING))

fun CompositionElement.Text.withBackgroundCorner(value: Float): CompositionElement.Text =
    copy(backgroundCornerRadius = value.coerceIn(0f, MAX_TEXT_BACKGROUND_CORNER))
