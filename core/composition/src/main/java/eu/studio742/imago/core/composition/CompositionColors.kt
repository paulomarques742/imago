package eu.studio742.imago.core.composition

/** Few enough to scan in one row of swatches. */
private const val MAX_COMPOSITION_COLORS = 24

/**
 * The colours the composition already uses — backgrounds, text and its effects, shapes, strokes and
 * frame outlines — in the order they appear, each once.
 *
 * It is what lets a new element be matched to what is on the page without retyping a hex code. Fully
 * transparent colours are left out: they are a way of saying "none", not a colour to reuse.
 */
fun CompositionProject.usedColors(): List<Long> {
    val found = LinkedHashSet<Long>()
    fun add(argb: Long?) {
        if (argb != null && ((argb ushr 24) and 0xFFL) != 0L) found += argb
    }
    fun add(background: CompositionBackground?) {
        when (background) {
            is CompositionBackground.Solid -> add(background.argb)
            is CompositionBackground.Gradient -> { add(background.startArgb); add(background.endArgb) }
            is CompositionBackground.Photo, null -> Unit
        }
    }
    fun add(frame: FrameStyle) {
        if (frame.borderWidth > 0f) add(frame.borderArgb)
    }
    add(background)
    pages.forEach { add(it.backgroundOverride) }
    elements.forEach { element ->
        when (element) {
            is CompositionElement.Text -> {
                add(element.colorArgb); add(element.backgroundArgb); add(element.strokeArgb); add(element.shadowArgb)
            }
            is CompositionElement.Shape -> { add(element.fillArgb); add(element.strokeArgb) }
            is CompositionElement.Drawing -> if (element.kind != StrokeKind.ERASER) add(element.colorArgb)
            is CompositionElement.Photo -> add(element.frame)
            is CompositionElement.Video -> add(element.frame)
            is CompositionElement.MediaPlaceholder -> add(element.frame)
        }
    }
    return found.take(MAX_COMPOSITION_COLORS)
}
