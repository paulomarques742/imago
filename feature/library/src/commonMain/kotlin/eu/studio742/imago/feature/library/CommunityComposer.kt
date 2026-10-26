package eu.studio742.imago.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal class CommunityComposer(val repository: CommunityRepository) : ViewModel() {
    val draft = MutableStateFlow(ForumDraft(ForumCategory.QUESTION, "", "", "",
        appVersion = LocalDiagnostics.metadata["App"].orEmpty().take(40),
        device = listOfNotNull(LocalDiagnostics.metadata["Model"], LocalDiagnostics.metadata["Platform"],
            LocalDiagnostics.metadata["OS"]).joinToString(", ").take(80)))
    val images = MutableStateFlow<List<ForumImage>>(emptyList())
    val loading = MutableStateFlow(false)
    val profileLoaded = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val preparingImages = MutableStateFlow(false)
    val reviewing = MutableStateFlow(false)
    val error = MutableStateFlow<ForumException.Reason?>(null)
    val imageError = MutableStateFlow(false)
    val created = MutableStateFlow<ForumPost?>(null)
    private var profileJob: Job? = null
    private var profileRevision = 0

    fun loadProfile() {
        if (profileLoaded.value || loading.value) return
        loading.value = true
        val revision = ++profileRevision
        profileJob = viewModelScope.launch {
            error.value = null
            try {
                val profile = repository.profile()
                if (revision != profileRevision) return@launch
                if (draft.value.name.isBlank()) draft.value = draft.value.copy(name = profile.name.orEmpty())
                profileLoaded.value = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = (failure as? ForumException)?.reason ?: ForumException.Reason.FAILED }
            finally { if (revision == profileRevision) loading.value = false }
        }
    }

    fun attach(added: List<ForumImage>) {
        if (images.value.size + added.size > 4) { imageError.value = true; return }
        images.value += added
        imageError.value = false
    }

    fun publish() {
        if (busy.value || preparingImages.value || created.value != null || !profileLoaded.value || !reviewing.value || !draft.value.isValid()) return
        busy.value = true
        viewModelScope.launch {
            error.value = null
            try { created.value = repository.publish(draft.value, images.value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = (failure as? ForumException)?.reason ?: ForumException.Reason.FAILED }
            finally { busy.value = false }
        }
    }

    fun reset() {
        profileRevision++
        profileJob?.cancel()
        loading.value = false
        draft.value = draft.value.copy(title = "", body = "", name = "")
        profileLoaded.value = false
        images.value = emptyList()
        reviewing.value = false
        created.value = null
        error.value = null
        imageError.value = false
    }
}
