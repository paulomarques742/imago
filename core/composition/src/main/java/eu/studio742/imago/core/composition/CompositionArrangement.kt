package eu.studio742.imago.core.composition

enum class CompositionArrangementReference { PAGE, SELECTION }

data class CompositionSelectionUnit(val members: List<CompositionElement>) {
    val groupId: String? get() = members.first().groupId
    val key: String get() = groupId?.let { "group:$it" } ?: "element:${members.first().id}"
    fun bounds(aspect: Float): NormalizedRect = members.map { it.visualBounds(aspect = aspect) }.boundingBox()!!
}

/** Existing groups remain atomic unless their members are being edited explicitly. */
fun List<CompositionElement>.selectionUnits(editingGroupId: String? = null): List<CompositionSelectionUnit> =
    groupBy { element -> element.groupId?.takeIf { it != editingGroupId }?.let { "group:$it" } ?: "element:${element.id}" }
        .values.map(::CompositionSelectionUnit)

fun List<CompositionElement>.alignedSelection(
    horizontal: Int?, vertical: Int?, reference: NormalizedRect, aspect: Float, editingGroupId: String? = null,
): List<CompositionElement> {
    val offsets = mutableMapOf<String, Pair<Float, Float>>()
    selectionUnits(editingGroupId).forEach { unit ->
        val box = unit.bounds(aspect)
        val dx = when (horizontal) {
            -1 -> reference.x - box.x
            0 -> (reference.x + reference.right - box.x - box.right) / 2f
            1 -> reference.right - box.right
            else -> 0f
        }
        val dy = when (vertical) {
            -1 -> reference.y - box.y
            0 -> (reference.y + reference.bottom - box.y - box.bottom) / 2f
            1 -> reference.bottom - box.bottom
            else -> 0f
        }
        unit.members.forEach { offsets[it.id] = dx to dy }
    }
    return translatedMembers(offsets)
}

/** Equal gaps use the visible bounds, including rotation; translation never resizes the artwork. */
fun List<CompositionElement>.distributedSelection(
    horizontal: Boolean, reference: NormalizedRect, aspect: Float, editingGroupId: String? = null,
): List<CompositionElement> {
    val units = selectionUnits(editingGroupId).sortedWith(compareBy<CompositionSelectionUnit> {
        val box = it.bounds(aspect); if (horizontal) box.x else box.y
    }.thenBy { it.members.first().id })
    if (units.size < 2) return this
    val totalSize = units.sumOf { val box = it.bounds(aspect); (if (horizontal) box.width else box.height).toDouble() }.toFloat()
    val gap = ((if (horizontal) reference.width else reference.height) - totalSize) / (units.size - 1)
    var cursor = if (horizontal) reference.x else reference.y
    val offsets = mutableMapOf<String, Pair<Float, Float>>()
    units.forEach { unit ->
        val box = unit.bounds(aspect)
        val delta = cursor - if (horizontal) box.x else box.y
        unit.members.forEach { offsets[it.id] = if (horizontal) delta to 0f else 0f to delta }
        cursor += (if (horizontal) box.width else box.height) + gap
    }
    return translatedMembers(offsets)
}

private fun List<CompositionElement>.translatedMembers(offsets: Map<String, Pair<Float, Float>>) = map { element ->
    val (dx, dy) = offsets[element.id] ?: return@map element
    val box = element.transform.bounds
    element.withTransform(element.transform.copy(bounds = box.copy(x = box.x + dx, y = box.y + dy)))
}
