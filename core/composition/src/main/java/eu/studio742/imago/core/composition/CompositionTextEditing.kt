package eu.studio742.imago.core.composition

/** Presentation offsets keep automatic wrapping and capitals out of the saved words. */
data class CompositionTextVisualMapping(val text: String, val originalToVisual: List<Int>, val visualToOriginal: List<Int>)

fun CompositionElement.Text.visualMapping(lines: List<CompositionTextLayout.Line>): CompositionTextVisualMapping {
    val display = StringBuilder()
    val originalToDisplay = IntArray(text.length + 1)
    val displayToOriginal = mutableListOf(0)
    var cursor = 0
    while (cursor < text.length) {
        val end = text.offsetByCodePoints(cursor, 1)
        val part = text.substring(cursor, end).let { if (allCaps) it.uppercase() else it }
        val start = display.length
        display.append(part)
        for (offset in cursor..end) originalToDisplay[offset] = start + (offset - cursor) * part.length / (end - cursor)
        for (offset in 1..part.length) displayToOriginal += cursor + offset * (end - cursor) / part.length
        cursor = end
    }
    val source = display.toString()
    val visual = StringBuilder()
    val displayToVisual = IntArray(source.length + 1)
    val visualToDisplay = mutableListOf(0)
    var previous = 0
    lines.forEachIndexed { index, line ->
        val start = line.startOffset.coerceIn(previous, source.length)
        val end = line.endOffset.coerceIn(start, source.length)
        if (index > 0) {
            val before = visual.length
            visual.append('\n'); visualToDisplay += start
            for (offset in previous..start) displayToVisual[offset] = if (offset == start) before + 1 else before
        } else {
            for (offset in 0..start) displayToVisual[offset] = 0
            visualToDisplay[0] = start
        }
        for (offset in start until end) {
            displayToVisual[offset] = visual.length
            visual.append(source[offset]); visualToDisplay += offset + 1
        }
        displayToVisual[end] = visual.length
        previous = end
    }
    for (offset in previous..source.length) displayToVisual[offset] = visual.length
    visualToDisplay[visualToDisplay.lastIndex] = source.length
    val forward = originalToDisplay.map { displayToVisual[it] }
    val backward = visualToDisplay.map { displayToOriginal[it.coerceIn(displayToOriginal.indices)] }
    return CompositionTextVisualMapping(visual.toString(), forward, backward)
}

/** Moves a resized box so that its chosen local anchor stays at the same physical page point. */
fun ElementTransform.withAnchoredTextBounds(width: Float, height: Float, anchorX: Float = 0f, anchorY: Float = 0f, aspect: Float): ElementTransform {
    val before = bounds
    val dx = (width - before.width) * (.5f - anchorX)
    val dy = (height - before.height) * (.5f - anchorY)
    val (x, y) = rotateVector(dx * aspect, dy, rotationDegrees)
    return copy(bounds = NormalizedRect(before.x + before.width / 2f + x / aspect - width / 2f,
        before.y + before.height / 2f + y - height / 2f, width, height))
}

fun CompositionElement.Text.withTextWidth(width: Float, fromLeft: Boolean, aspect: Float): CompositionElement.Text =
    copy(transform = transform.withAnchoredTextBounds((width.takeIf(Float::isFinite) ?: transform.bounds.width).coerceAtLeast(MIN_ELEMENT_SIZE), transform.bounds.height,
        anchorX = if (fromLeft) 1f else 0f, aspect = aspect))

/** Typography scales with the object; relative measurements already expressed in em stay relative. */
fun CompositionElement.Text.scaledTypography(factor: Float): CompositionElement.Text {
    if (!factor.isFinite() || factor <= 0f) return this
    return copy(fontSize = fontSize * factor, backgroundPadding = backgroundPadding * factor,
        backgroundCornerRadius = backgroundCornerRadius * factor, shadowRadius = shadowRadius * factor,
        shadowOffsetX = shadowOffsetX * factor, shadowOffsetY = shadowOffsetY * factor)
}

fun CompositionElement.Text.withAutomaticTextHeight(height: Float, aspect: Float): CompositionElement.Text =
    copy(transform = transform.withAnchoredTextBounds(transform.bounds.width, height.coerceAtLeast(MIN_ELEMENT_SIZE), aspect = aspect))
