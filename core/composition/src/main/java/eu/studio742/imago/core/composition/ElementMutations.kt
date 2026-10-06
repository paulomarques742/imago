package eu.studio742.imago.core.composition

fun CompositionElement.withTransform(value: ElementTransform): CompositionElement = when (this) {
    is CompositionElement.MediaPlaceholder -> copy(transform = value)
    is CompositionElement.Photo -> copy(transform = value)
    is CompositionElement.Video -> copy(transform = value)
    is CompositionElement.Text -> copy(transform = value)
    is CompositionElement.Shape -> copy(transform = value)
    is CompositionElement.Drawing -> copy(transform = value)
}

fun CompositionElement.withLocked(value: Boolean): CompositionElement = when (this) {
    is CompositionElement.MediaPlaceholder -> copy(locked = value)
    is CompositionElement.Photo -> copy(locked = value)
    is CompositionElement.Video -> copy(locked = value)
    is CompositionElement.Text -> copy(locked = value)
    is CompositionElement.Shape -> copy(locked = value)
    is CompositionElement.Drawing -> copy(locked = value)
}

fun CompositionElement.withVisible(value: Boolean): CompositionElement = when (this) {
    is CompositionElement.MediaPlaceholder -> copy(visible = value)
    is CompositionElement.Photo -> copy(visible = value)
    is CompositionElement.Video -> copy(visible = value)
    is CompositionElement.Text -> copy(visible = value)
    is CompositionElement.Shape -> copy(visible = value)
    is CompositionElement.Drawing -> copy(visible = value)
}

fun CompositionElement.withZIndex(value: Int): CompositionElement = when (this) {
    is CompositionElement.MediaPlaceholder -> copy(zIndex = value)
    is CompositionElement.Photo -> copy(zIndex = value)
    is CompositionElement.Video -> copy(zIndex = value)
    is CompositionElement.Text -> copy(zIndex = value)
    is CompositionElement.Shape -> copy(zIndex = value)
    is CompositionElement.Drawing -> copy(zIndex = value)
}

fun CompositionElement.withRepeatOnPages(value: Set<Int>): CompositionElement = when (this) {
    is CompositionElement.MediaPlaceholder -> copy(repeatOnPages = value)
    is CompositionElement.Photo -> copy(repeatOnPages = value)
    is CompositionElement.Video -> this
    is CompositionElement.Text -> copy(repeatOnPages = value)
    is CompositionElement.Shape -> copy(repeatOnPages = value)
    is CompositionElement.Drawing -> copy(repeatOnPages = value)
}

fun CompositionElement.withGroupId(value: String?): CompositionElement = when (this) {
    is CompositionElement.MediaPlaceholder -> copy(groupId = value)
    is CompositionElement.Photo -> copy(groupId = value)
    is CompositionElement.Video -> copy(groupId = value)
    is CompositionElement.Text -> copy(groupId = value)
    is CompositionElement.Shape -> copy(groupId = value)
    is CompositionElement.Drawing -> copy(groupId = value)
}

/**
 * The same element with another identity — what a copy needs to be.
 *
 * Duplicating without this put two elements with the same `id` in the list, and from then on
 * everything that looks up by `id` (selection, layers, gestures) found the first of the two.
 */
fun CompositionElement.withId(value: String): CompositionElement = when (this) {
    is CompositionElement.MediaPlaceholder -> copy(id = value)
    is CompositionElement.Photo -> copy(id = value)
    is CompositionElement.Video -> copy(id = value)
    is CompositionElement.Text -> copy(id = value)
    is CompositionElement.Shape -> copy(id = value)
    is CompositionElement.Drawing -> copy(id = value)
}
