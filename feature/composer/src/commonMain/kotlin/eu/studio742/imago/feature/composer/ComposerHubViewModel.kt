package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.feature.composer.resources.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.composition.BuiltInLayout
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.CompositionTemplate
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository

data class ComposerHubUiState(
    val projects: List<CompositionProject> = emptyList(),
    val templates: List<CompositionTemplate> = emptyList(),
    val isLoading: Boolean = true,
    val openProjectId: String? = null,
    val error: UiText? = null,
)

open class ComposerHubViewModel(
    private val projects: CompositionRepository,
    private val templates: CompositionTemplateRepository,
    private val mediaRepository: CompositionMediaRepository,
) : ViewModel() {
    /** The thumbnail addresses, so the list can draw each project's first page. */
    fun thumbnailUrl(assetId: String) = mediaRepository.thumbnailUrl(assetId)
    fun apiKey(assetId: String) = mediaRepository.apiKey(assetId)

    private val transient = MutableStateFlow(ComposerHubUiState())
    val state = combine(projects.observeAll(), templates.observeAll(), transient) { projectList, templateList, local ->
        local.copy(projects = projectList, templates = templateList, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComposerHubUiState())

    fun create(format: PageFormat, layout: BuiltInLayout) = viewModelScope.launch {
        runCatching { newCompositionProject(format, layout).also { projects.save(it) } }
            .onSuccess { project -> transient.update { it.copy(openProjectId = project.id, error = null) } }
            .onFailure { error -> transient.update { it.copy(error = error.toUiText(Res.string.composer_create_failed)) } }
    }

    fun createFromTemplate(template: CompositionTemplate) = viewModelScope.launch {
        val now = Instant.now().toString()
        val source = template.project
        val project = source.copy(
            id = UUID.randomUUID().toString(),
            name = template.name,
            revision = 1,
            createdAt = now,
            updatedAt = now,
        )
        runCatching { projects.save(project) }
            .onSuccess { transient.update { it.copy(openProjectId = project.id) } }
            .onFailure { error -> transient.update { it.copy(error = error.toUiText(Res.string.composer_create_failed)) } }
    }

    fun rename(project: CompositionProject, name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        projects.save(project.copy(name = name.trim(), revision = project.revision + 1, updatedAt = Instant.now().toString()))
    }

    fun duplicate(id: String) = viewModelScope.launch {
        runCatching { projects.duplicate(id) { original -> appString(Res.string.composer_copy_name, original) } }
            .onSuccess { project -> transient.update { it.copy(openProjectId = project.id) } }
            .onFailure { error -> transient.update { it.copy(error = error.toUiText(Res.string.composer_duplicate_failed)) } }
    }

    fun delete(id: String) = viewModelScope.launch { projects.delete(id) }
    fun consumeOpen() = transient.update { it.copy(openProjectId = null) }
    fun consumeError() = transient.update { it.copy(error = null) }
}
