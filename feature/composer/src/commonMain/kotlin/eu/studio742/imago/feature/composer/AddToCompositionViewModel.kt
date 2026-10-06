package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.composition.BuiltInLayout
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.MediaInsertion
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.composition.withMedia
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.RecipeRepository
import java.time.Instant

data class AddToCompositionUiState(
    val projects: List<CompositionProject> = emptyList(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    /** The composition to open as soon as the UI can do it. */
    val openProjectId: String? = null,
    val warning: PartialInsert? = null,
    val error: UiText? = null,
)

/** What was left out, and the composition the rest went into. */
data class PartialInsert(val projectId: String, val message: UiText)

open class AddToCompositionViewModel(
    private val projects: CompositionRepository,
    private val recipes: RecipeRepository,
) : ViewModel() {
    private val transient = MutableStateFlow(AddToCompositionUiState())
    val state = combine(projects.observeAll(), transient) { list, local ->
        local.copy(projects = list, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddToCompositionUiState())

    /** Adds these media to a composition that already exists. */
    fun addTo(projectId: String, media: List<ComposerMedia>) = insert {
        val project = projects.get(projectId) ?: throw LocalizedException(uiText(Res.string.composer_composition_missing))
        project.withMedia(media.toInsertable(recipes), Instant.now().toString())
    }

    /** Creates the requested composition and puts these media in it. */
    fun createWith(format: PageFormat, layout: BuiltInLayout, media: List<ComposerMedia>) = insert {
        newCompositionProject(format, layout).withMedia(media.toInsertable(recipes), Instant.now().toString())
    }

    /**
     * Clears what was left from last time.
     *
     * The `ViewModel` belongs to the activity and survives the sheet closing: without this, an error
     * from ten minutes ago appeared in red on top of a new request that had not even run yet.
     */
    fun reset() = transient.update { it.copy(isWorking = false, warning = null, error = null) }

    /** After saying what did not fit, open the composition anyway. */
    fun openWarned() = transient.update { it.copy(openProjectId = it.warning?.projectId, warning = null) }

    fun consumeOpen() = transient.update { it.copy(openProjectId = null) }

    private fun insert(block: suspend () -> MediaInsertion) = viewModelScope.launch {
        transient.update { it.copy(isWorking = true, error = null, warning = null) }
        runCatching {
            val insertion = block()
            projects.save(insertion.project)
            insertion
        }
            .onSuccess { insertion ->
                transient.update {
                    if (insertion.skipped > 0) {
                        it.copy(
                            isWorking = false,
                            warning = PartialInsert(
                                projectId = insertion.project.id,
                                message = uiPlural(Res.plurals.composer_videos_skipped, insertion.skipped, insertion.skipped),
                            ),
                        )
                    } else {
                        it.copy(isWorking = false, openProjectId = insertion.project.id)
                    }
                }
            }
            .onFailure { error ->
                transient.update {
                    it.copy(
                        isWorking = false,
                        error = error.toUiText(Res.string.composer_add_failed),
                    )
                }
            }
    }
}
