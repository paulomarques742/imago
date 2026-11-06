package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*

data class ComposerGridEditor(
    val base: CompositionProject,
    val layout: CompositionLayout,
    val reference: CompositionArrangementReference,
    val pageIndex: Int,
    val combinesGroups: Boolean = false,
    val addedElements: List<CompositionElement> = emptyList(),
    val pages: List<CompositionPage>? = null,
) {
    fun preview(): CompositionProject {
        val ids = layout.root.memberIds().toSet()
        val oldGroups = base.elements.filter { it.id in ids }.mapNotNull { it.groupId }.toSet()
        return base.copy(elements = layout.applyTo(base.elements + addedElements, base.format.aspectRatio), pages = pages ?: base.pages,
            layouts = base.layouts.filterNot { it.groupId in oldGroups } + layout)
    }
}

internal fun ComposerEditorUiState.selectedLayout(): CompositionLayout? {
    val members = selectionElements()
    val group = members.firstOrNull()?.groupId ?: return null
    return project?.layouts?.firstOrNull { it.groupId == group && members.all { member -> member.groupId == group } }
}

internal fun ComposerEditorUiState.manipulatedLayout(): CompositionLayout? = selectedLayout()?.takeIf {
    editingGroupId == null && editableSelection().map { member -> member.id }.toSet() == it.root.memberIds().toSet()
}

internal fun CompositionLayout.proxy() = CompositionElement.MediaPlaceholder(root.memberIds().first(), frame, 0, groupId = groupId)

internal fun ComposerEditorUiState.manipulationProject(): CompositionProject {
    val source = checkNotNull(project)
    val layout = manipulatedLayout() ?: return source
    val ids = layout.root.memberIds().toSet()
    return source.copy(elements = source.elements.filterNot { it.id in ids } + layout.proxy(), layouts = emptyList())
}

internal fun ComposerEditorUiState.manipulationIds(): Set<String> = manipulatedLayout()?.let { setOf(it.root.memberIds().first()) }
    ?: editableSelection().mapTo(mutableSetOf()) { it.id }
