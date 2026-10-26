package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.library.resources.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.model.ConnectionHeader

data class ConfigurationUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    /** The rows of the Advanced section, blank ones included while they are being typed. */
    val headers: List<ConnectionHeader> = emptyList(),
    val certificate: eu.studio742.imago.core.model.ClientCertificate? = null,
    val isChecking: Boolean = false,
    val error: UiText? = null,
    /** The error is about something in front of the server, which the Advanced section configures. */
    val needsConnectionOptions: Boolean = false,
)

open class ConfigurationViewModel(
    private val repository: ConfigurationRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ConfigurationUiState())
    val state = mutableState.asStateFlow()

    fun updateServerUrl(value: String) = mutableState.update { it.copy(serverUrl = value, error = null) }
    fun updateApiKey(value: String) = mutableState.update { it.copy(apiKey = value, error = null) }
    fun updateHeaders(value: List<ConnectionHeader>) = mutableState.update { it.copy(headers = value, error = null) }
    fun updateCertificate(value: eu.studio742.imago.core.model.ClientCertificate?) = mutableState.update { it.copy(certificate = value, error = null) }

    val certificates = CertificateSupport(repository::importClientCertificate, repository::describeClientCertificate)

    /** Connects to the server and stays on it; only with the connection saved does the welcome end. */
    fun connect(onConnected: () -> Unit) {
        val snapshot = state.value
        if (snapshot.serverUrl.isBlank() || snapshot.apiKey.isBlank()) {
            mutableState.update { it.copy(error = uiText(Res.string.library_fill_url_key)) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(isChecking = true, error = null, needsConnectionOptions = false) }
            runCatching { repository.validateAndSave(snapshot.serverUrl, snapshot.apiKey, snapshot.headers.toProfile(snapshot.certificate)) }
                .onSuccess {
                    // The key does not stay in the state once saved: it already lives encrypted in the repository.
                    mutableState.value = ConfigurationUiState()
                    repository.completeWelcome()
                    onConnected()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isChecking = false, error = error.toUiText(Res.string.library_validation_failed),
                            needsConnectionOptions = error.isProtection())
                    }
                }
        }
    }

    /** Keeps only the device library. */
    fun chooseDevice() {
        repository.selectLibrary(eu.studio742.imago.core.model.DEVICE_LIBRARY_ID)
        repository.completeWelcome()
    }
}

