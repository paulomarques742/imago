package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.*

/** A provisional edit never enters autosave, export or undo until it is accepted. */
data class ComposerTextSession(
    val original: CompositionElement.Text?,
    val draft: CompositionElement.Text,
    val selectedIds: Set<String>,
    val selectedId: String?,
    val contentEditing: Boolean = true,
    val fontBeforePreview: Pair<String, Int>? = null,
)

internal fun ComposerEditorUiState.displayProject(): CompositionProject? = project?.let { project ->
    textSession?.let { session ->
        project.copy(elements = if (session.original == null) project.elements + session.draft
            else project.elements.map { if (it.id == session.draft.id) session.draft else it })
    } ?: project
}
