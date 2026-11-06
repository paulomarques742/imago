package eu.studio742.imago.feature.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.studio742.imago.core.composition.SavedCompositionFormat
import eu.studio742.imago.core.data.CompositionFormatRepository
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.feature.composer.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ComposerFormatsUiState(
    val formats: List<SavedCompositionFormat> = emptyList(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val error: UiText? = null,
    val saved: SavedCompositionFormat? = null,
)

open class ComposerFormatsViewModel(private val repository: CompositionFormatRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ComposerFormatsUiState())
    val state = mutableState.asStateFlow()
    fun reload() = viewModelScope.launch {
        mutableState.update { it.copy(isLoading = true, error = null) }
        try {
            val formats = repository.load()
            mutableState.update { it.copy(formats = formats, isLoading = false) }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.update { it.copy(isLoading = false, error = error.toUiText(Res.string.composer_formats_load_failed)) } }
    }
    fun save(name: String, width: Int, height: Int, id: String? = null) = change {
        val saved = repository.save(name, width, height, id)
        val formats = repository.load()
        mutableState.update { it.copy(formats = formats, saved = saved) }
    }
    fun delete(id: String) = change {
        repository.delete(id)
        val formats = repository.load()
        mutableState.update { it.copy(formats = formats) }
    }
    private fun change(block: suspend () -> Unit) {
        if (state.value.isWorking || state.value.isLoading) return
        mutableState.update { it.copy(isWorking = true, error = null, saved = null) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.update { it.copy(error = error.toUiText(Res.string.composer_format_save_failed)) } }
            finally { mutableState.update { it.copy(isWorking = false) } }
        }
    }
    fun consumeSaved() = mutableState.update { it.copy(saved = null) }
    fun clearError() = mutableState.update { it.copy(error = null) }
}
