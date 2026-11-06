package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*

data class ComposerGroupMergeRequest(val members: Map<String, String?>)

internal fun ComposerEditorUiState.selectionElements(source: CompositionProject? = project): List<CompositionElement> {
    val elements = source?.elements ?: return emptyList()
    val ids = selectedElementIds + setOfNotNull(selectedElementId)
    if (editingGroupId != null) return elements.filter { it.id in ids && it.groupId == editingGroupId }
    val groups = elements.filter { it.id in ids }.mapNotNullTo(mutableSetOf()) { it.groupId }
    return elements.filter { it.id in ids || it.groupId != null && it.groupId in groups }
}

internal fun ComposerEditorUiState.canEditElement(id: String, source: CompositionProject? = project): Boolean {
    val elements = source?.elements ?: return false
    val target = elements.firstOrNull { it.id == id } ?: return false
    return !target.locked && (target.groupId == null || target.groupId == editingGroupId ||
        elements.none { it.groupId == target.groupId && it.locked })
}

internal fun ComposerEditorUiState.editableSelection(source: CompositionProject? = project): List<CompositionElement> =
    selectionElements(source).takeIf { members -> members.all { canEditElement(it.id, source) } }.orEmpty()

internal fun ComposerEditorUiState.hasSelectionTools(): Boolean {
    val members = selectionElements()
    return members.size > 1 || editingGroupId == null && members.any { it.groupId != null }
}

internal fun ComposerEditorUiState.selectionUnitCount() = selectionElements().selectionUnits(editingGroupId).size
