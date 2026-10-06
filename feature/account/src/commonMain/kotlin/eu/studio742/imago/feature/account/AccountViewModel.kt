package eu.studio742.imago.feature.account

import eu.studio742.imago.core.sync.AccountException
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.asUiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.account.resources.*
import org.jetbrains.compose.resources.StringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import eu.studio742.imago.core.sync.AccountLinkState
import kotlinx.coroutines.launch
import eu.studio742.imago.core.sync.AccountRepository
import eu.studio742.imago.core.sync.RemoteDevice
import eu.studio742.imago.core.sync.SignUpResult
import eu.studio742.imago.core.sync.SyncEngine
import eu.studio742.imago.core.sync.SyncStatus

/** The account, for the screens of both apps. On Android it comes through [HiltAccountViewModel]. */
open class AccountViewModel(
    private val account: AccountRepository,
    private val sync: SyncEngine? = null,
) : ViewModel() {
    val isAvailable = account.isAvailable
    val session = account.session
    val deviceName = account.deviceName
    val passwordRecoveryPending = account.passwordRecoveryPending

    val linkState = account.linkState

    /** What sync is doing; null in an app without the engine. */
    val syncStatus: StateFlow<SyncStatus>? = sync?.status

    private val deviceList = MutableStateFlow<DevicesState>(DevicesState.Loading)
    val devices = deviceList.asStateFlow()
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<UiText?>(null)

    /** The email the confirmation link went to, while waiting for it. */
    val confirmationSentTo = MutableStateFlow<String?>(null)

    val currentDeviceId: String? get() = account.currentDeviceId

    init {
        viewModelScope.launch {
            // The list is only requested once this device is registered; asked for earlier, it came without it.
            combine(session, linkState) { current, link -> current to link }.collect { (current, link) ->
                if (current != null) confirmationSentTo.value = null
                when {
                    current == null -> deviceList.value = DevicesState.Loading
                    link is AccountLinkState.Linked -> refreshDevices()
                    link is AccountLinkState.Failed -> deviceList.value = DevicesState.Failed(link.text())
                    else -> deviceList.value = DevicesState.Loading
                }
            }
        }
    }

    fun signIn(email: String, password: String) = run {
        validate(email, password)
        account.signIn(email, password)
        null
    }

    fun signUp(email: String, password: String, repeated: String) = run {
        validate(email, password)
        invalidUnless(password == repeated, Res.string.account_passwords_differ)
        when (account.signUp(email, password)) {
            SignUpResult.CONFIRMATION_SENT -> { confirmationSentTo.value = email.trim(); null }
            SignUpResult.SIGNED_IN -> null
        }
    }

    fun sendPasswordReset(email: String) = run {
        invalidUnless(email.contains('@'), Res.string.account_reset_needs_email)
        account.sendPasswordReset(email)
        uiText(Res.string.account_reset_sent)
    }

    fun updatePassword(password: String, repeated: String) = run {
        invalidUnless(password.length >= MIN_PASSWORD, Res.string.account_password_too_short, MIN_PASSWORD)
        invalidUnless(password == repeated, Res.string.account_passwords_differ)
        account.updatePassword(password)
        uiText(Res.string.account_password_changed)
    }

    fun dismissPasswordRecovery() = account.dismissPasswordRecovery()

    /** Syncs now, without the pause of automatic requests. */
    fun syncNow() {
        val engine = sync ?: return
        viewModelScope.launch { engine.syncNow() }
    }

    fun renameDevice(name: String) = run {
        account.renameDevice(name)
        refreshDevices()
        uiText(Res.string.account_device_renamed)
    }

    fun removeDevice(device: RemoteDevice) = run {
        account.removeDevice(device.id)
        refreshDevices()
        uiText(Res.string.account_device_removed, device.name)
    }

    /** Exports the account data; [deliver] saves or shares the ZIP and says what happened. */
    fun exportData(deliver: suspend (fileName: String, bytes: ByteArray) -> UiText?) = run {
        val bytes = account.exportData()
        deliver(appString(Res.string.account_export_file_name, java.time.LocalDate.now().toString()), bytes)
    }

    fun deleteAccount(password: String) = run {
        invalidUnless(password.isNotEmpty(), Res.string.account_password_required)
        account.deleteAccount(password)
        uiText(Res.string.account_deleted)
    }

    fun signOut(keepCopy: Boolean) = run {
        account.signOut(keepCopy)
        uiText(if (keepCopy) Res.string.account_signed_out_kept else Res.string.account_signed_out_removed)
    }

    /** Retries what failed: the registration and the link, or just the list. */
    fun retry() {
        if (linkState.value is AccountLinkState.Failed) account.retryLink()
        else viewModelScope.launch { refreshDevices() }
    }

    private suspend fun refreshDevices() {
        deviceList.value = DevicesState.Loading
        deviceList.value = runCatching { account.devices() }
            .fold({ DevicesState.Loaded(it) }, { DevicesState.Failed(it.accountText(Res.string.account_devices_failed)) })
    }

    private fun validate(email: String, password: String) {
        invalidUnless(email.trim().contains('@'), Res.string.account_email_invalid)
        invalidUnless(password.length >= MIN_PASSWORD, Res.string.account_password_too_short, MIN_PASSWORD)
    }

    /** Runs an account action; what it returns is the success message, and the error becomes a message. */
    private fun run(action: suspend () -> UiText?) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = null
            runCatching { action() }
                .onSuccess { message.value = it }
                .onFailure { message.value = it.accountText(Res.string.account_request_failed) }
            busy.value = false
        }
    }

    private companion object {
        const val MIN_PASSWORD = 8
    }
}

sealed interface DevicesState {
    data object Loading : DevicesState
    data class Loaded(val devices: List<RemoteDevice>) : DevicesState
    data class Failed(val message: UiText) : DevicesState
}

/** The engine state in the indicator's shape; null without an account, and the indicator does not show. */
fun SyncStatus.toIndicator(): eu.studio742.imago.core.designsystem.SyncIndicatorState? =
    if (!signedIn) null else eu.studio742.imago.core.designsystem.SyncIndicatorState(
        running = running, pending = pending, offline = offline, failed = error != null,
    )

/** A failed validation, with the sentence for the person. */
private class InvalidInput(val text: UiText) : Exception()

private fun invalidUnless(condition: Boolean, resource: StringResource, vararg args: Any) {
    if (!condition) throw InvalidInput(UiText.Resource(resource, args.toList()))
}

/**
 * The sentence for an account failure. Validations bring their own; refusals from the account
 * server are told by their reason ([AccountException.Reason]); the rest keeps the caller's sentence.
 */
private fun Throwable.accountText(fallback: StringResource): UiText = when (this) {
    is InvalidInput -> text
    is AccountException -> UiText.Resource(reason.resource())
    else -> toUiText(fallback)
}

private fun AccountException.Reason.resource(): StringResource = when (this) {
    AccountException.Reason.NOT_AVAILABLE -> Res.string.account_error_not_available
    AccountException.Reason.OFFLINE -> Res.string.account_error_offline
    AccountException.Reason.INVALID_CREDENTIALS -> Res.string.account_error_invalid_credentials
    AccountException.Reason.EMAIL_NOT_CONFIRMED -> Res.string.account_error_email_not_confirmed
    AccountException.Reason.EMAIL_IN_USE -> Res.string.account_error_email_in_use
    AccountException.Reason.WEAK_PASSWORD -> Res.string.account_error_weak_password
    AccountException.Reason.SAME_PASSWORD -> Res.string.account_error_same_password
    AccountException.Reason.RATE_LIMITED -> Res.string.account_error_rate_limited
    AccountException.Reason.NOT_SIGNED_IN -> Res.string.account_error_not_signed_in
    AccountException.Reason.SERVER -> Res.string.account_error_server
}

/** Why the device, or some libraries, were left unlinked from the account. */
private fun AccountLinkState.Failed.text(): UiText =
    if (libraries.isNotEmpty()) UiText.Resource(Res.string.account_link_libraries_failed, listOf(libraries.joinToString()))
    else UiText.Resource(reason?.resource() ?: Res.string.account_link_device_failed)
