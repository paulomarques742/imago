@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.account

import java.time.format.FormatStyle
import eu.studio742.imago.core.designsystem.i18n.text
import eu.studio742.imago.core.designsystem.i18n.LocalAppLocale
import eu.studio742.imago.core.designsystem.i18n.resolve
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.account.resources.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoBrand
import eu.studio742.imago.core.sync.AccountLinkState
import eu.studio742.imago.core.sync.RemoteDevice
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The "Account" section of Settings, above the libraries.
 *
 * Without a session it invites to sign in or create an account; with one it shows who is signed in
 * and opens account management.
 */
@Composable
fun AccountSettingsCard(onOpenAccount: (signUp: Boolean) -> Unit, viewModel: AccountViewModel = accountViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    AccountCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
            Icon(Icons.Outlined.AccountCircle, null, tint = ImagoColors.Ivory)
            Text(stringResource(Res.string.account_imago_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        when {
            !viewModel.isAvailable -> Text(
                stringResource(Res.string.account_unavailable_detail),
                style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary,
            )
            session == null -> {
                // What syncs and what never leaves the device — said before the person creates an account.
                Text(stringResource(Res.string.account_privacy_note), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                    Button(onClick = { onOpenAccount(false) }) { Text(stringResource(Res.string.account_sign_in)) }
                    OutlinedButton(onClick = { onOpenAccount(true) }) { Text(stringResource(Res.string.account_create)) }
                }
            }
            else -> {
                Text(session?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onOpenAccount(false) }) { Text(stringResource(Res.string.account_manage)) }
            }
        }
    }
}

/**
 * The account invitation on the welcome screen. In a build without accounts, or with the session
 * already open, it shows nothing: the app only offers what works.
 */
@Composable
fun WelcomeAccountPrompt(onOpenAccount: (signUp: Boolean) -> Unit, viewModel: AccountViewModel = accountViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    if (!viewModel.isAvailable || session != null) return
    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
        Text(stringResource(Res.string.account_welcome_prompt), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            OutlinedButton(onClick = { onOpenAccount(true) }) { Text(stringResource(Res.string.account_create)) }
            TextButton(onClick = { onOpenAccount(false) }) { Text(stringResource(Res.string.account_sign_in)) }
        }
    }
}

@Composable
fun AccountRoute(startWithSignUp: Boolean, onBack: () -> Unit, viewModel: AccountViewModel = accountViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val recovery by viewModel.passwordRecoveryPending.collectAsStateWithLifecycle()
    val back = { if (!busy) onBack() }
    BackHandler(onBack = back)
    Scaffold(
        containerColor = ImagoColors.Background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(ImagoColors.Background).statusBarsPadding()
                    .height(64.dp).padding(horizontal = ImagoSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = back, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.account_back), tint = ImagoColors.TextPrimary)
                }
                Spacer(Modifier.weight(1f))
                Image(rememberVectorPainter(ImagoBrand.Wordmark), contentDescription = "IMAGO", modifier = Modifier.height(17.dp))
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(ImagoSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Lg),
            ) {
                Text(stringResource(Res.string.account_title), style = MaterialTheme.typography.headlineLarge, color = ImagoColors.TextPrimary)
                when {
                    !viewModel.isAvailable -> AccountCard {
                        Text(stringResource(Res.string.account_unavailable), style = MaterialTheme.typography.bodyMedium)
                    }
                    session == null -> SignedOut(startWithSignUp, busy, viewModel)
                    else -> SignedIn(session?.email.orEmpty(), busy, viewModel)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary) }
            }
        }
    }
    if (recovery && session != null) NewPasswordDialog(busy, viewModel)
}

@Composable
private fun SignedOut(startWithSignUp: Boolean, busy: Boolean, viewModel: AccountViewModel) {
    var signUp by rememberSaveable { mutableStateOf(startWithSignUp) }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords do not go into the saved state.
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    val sentTo by viewModel.confirmationSentTo.collectAsStateWithLifecycle()

    Text(stringResource(Res.string.account_privacy_note), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
    sentTo?.let { address ->
        AccountCard {
            Text(stringResource(Res.string.account_confirm_email_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.account_confirm_email_body, address),
                style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary,
            )
        }
    }
    AccountCard {
        Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            FilterChip(selected = !signUp, onClick = { signUp = false }, label = { Text(stringResource(Res.string.account_sign_in)) }, enabled = !busy)
            FilterChip(selected = signUp, onClick = { signUp = true }, label = { Text(stringResource(Res.string.account_create)) }, enabled = !busy)
        }
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(Res.string.account_email)) }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(Res.string.account_password)) }, singleLine = true, enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (signUp) {
            OutlinedTextField(repeated, { repeated = it }, label = { Text(stringResource(Res.string.account_repeat_password)) }, singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        }
        Button(
            enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
            onClick = { if (signUp) viewModel.signUp(email, password, repeated) else viewModel.signIn(email, password) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (signUp) stringResource(Res.string.account_create) else stringResource(Res.string.account_sign_in)) }
        if (!signUp) TextButton(enabled = !busy, onClick = { viewModel.sendPasswordReset(email) }) {
            Text(stringResource(Res.string.account_forgot_password))
        }
    }
}

@Composable
private fun SignedIn(email: String, busy: Boolean, viewModel: AccountViewModel) {
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val link by viewModel.linkState.collectAsStateWithLifecycle()
    var name by remember(deviceName) { mutableStateOf(deviceName) }
    var removing by remember { mutableStateOf<RemoteDevice?>(null) }
    var signingOut by remember { mutableStateOf(false) }

    AccountCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
            Icon(Icons.Outlined.AccountCircle, null, tint = ImagoColors.Ivory)
            Text(email, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            when (link) {
                is AccountLinkState.Linked -> stringResource(Res.string.account_link_linked)
                is AccountLinkState.Failed -> stringResource(Res.string.account_link_failed)
                else -> stringResource(Res.string.account_link_pending)
            },
            style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary,
        )
        if (link is AccountLinkState.Linked) viewModel.syncStatus?.let { status -> SyncStatusRow(status, viewModel::syncNow) }
        (link as? AccountLinkState.Linked)?.withoutIdentity?.takeIf { it.isNotEmpty() }?.let { names ->
            Text(
                stringResource(Res.string.account_libraries_without_identity, names.joinToString()),
                style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary,
            )
        }
    }

    AccountCard {
        Text(stringResource(Res.string.account_this_device), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.account_device_name_hint),
            style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.account_device_name_label)) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !busy && name.isNotBlank() && name.trim() != deviceName, onClick = { viewModel.renameDevice(name) }) {
            Text(stringResource(Res.string.account_save_name))
        }
    }

    AccountCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
            Icon(Icons.Outlined.Devices, null, tint = ImagoColors.Ivory)
            Text(stringResource(Res.string.account_devices), style = MaterialTheme.typography.titleMedium)
        }
        when (val state = devices) {
            DevicesState.Loading -> Text(stringResource(Res.string.account_devices_loading), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
            is DevicesState.Failed -> {
                Text(state.message.resolve(), style = MaterialTheme.typography.bodySmall, color = ImagoColors.Error)
                TextButton(enabled = !busy, onClick = viewModel::retry) { Text(stringResource(Res.string.account_try_again)) }
            }
            is DevicesState.Loaded -> if (state.devices.isEmpty()) {
                Text(stringResource(Res.string.account_devices_empty), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
                TextButton(enabled = !busy, onClick = viewModel::retry) { Text(stringResource(Res.string.account_refresh)) }
            }
        }
        (devices as? DevicesState.Loaded)?.devices.orEmpty().forEach { device ->
            val current = device.id == viewModel.currentDeviceId
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (current) stringResource(Res.string.account_this_device) else stringResource(Res.string.account_last_seen, formatSeen(device.lastSeenAt)),
                        style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
                }
                if (!current) TextButton(enabled = !busy, onClick = { removing = device }) {
                    Text(stringResource(Res.string.account_remove), color = ImagoColors.Danger)
                }
            }
        }
    }

    val deliverExport = rememberAccountExportDelivery()
    var deleting by remember { mutableStateOf(false) }
    AccountCard {
        Text(stringResource(Res.string.account_your_data), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(Res.string.account_your_data_body),
            style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = { viewModel.exportData(deliverExport) }) { Text(stringResource(Res.string.account_export_data)) }
            TextButton(enabled = !busy, onClick = { deleting = true }) { Text(stringResource(Res.string.account_delete), color = ImagoColors.Danger) }
        }
    }

    OutlinedButton(enabled = !busy, onClick = { signingOut = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.account_sign_out)) }

    if (deleting) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(Res.string.account_delete_question)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                    Text(stringResource(Res.string.account_delete_body))
                    OutlinedTextField(
                        password, { password = it }, label = { Text(stringResource(Res.string.account_password_label)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty(), onClick = { viewModel.deleteAccount(password); deleting = false }) {
                    Text(stringResource(Res.string.account_delete_confirm), color = ImagoColors.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(Res.string.account_cancel)) } },
        )
    }

    removing?.let { device ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(Res.string.account_remove_device_question, device.name)) },
            text = { Text(stringResource(Res.string.account_remove_device_body)) },
            confirmButton = { TextButton(onClick = { viewModel.removeDevice(device); removing = null }) { Text(stringResource(Res.string.account_remove), color = ImagoColors.Danger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(Res.string.account_cancel)) } },
        )
    }
    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text(stringResource(Res.string.account_sign_out_question)) },
            text = {
                Text(stringResource(Res.string.account_sign_out_body))
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { viewModel.signOut(keepCopy = true); signingOut = false }) { Text(stringResource(Res.string.account_sign_out_keep)) }
                    TextButton(onClick = { viewModel.signOut(keepCopy = false); signingOut = false }) {
                        Text(stringResource(Res.string.account_sign_out_remove), color = ImagoColors.Danger)
                    }
                    TextButton(onClick = { signingOut = false }) { Text(stringResource(Res.string.account_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun NewPasswordDialog(busy: Boolean, viewModel: AccountViewModel) {
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) viewModel.dismissPasswordRecovery() },
        title = { Text(stringResource(Res.string.account_new_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(Res.string.account_new_password)) }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(repeated, { repeated = it }, label = { Text(stringResource(Res.string.account_repeat)) }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotEmpty(), onClick = { viewModel.updatePassword(password, repeated) }) { Text(stringResource(Res.string.account_save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = viewModel::dismissPasswordRecovery) { Text(stringResource(Res.string.account_not_now)) } },
    )
}

@Composable
private fun AccountCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = ImagoColors.SurfaceElevated, shape = RoundedCornerShape(ImagoRadii.Medium), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ImagoSpacing.Lg), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md), content = content)
    }
}

@Composable
private fun formatSeen(timestamp: String): String {
    // The day in the language's pattern ("24 Sep" / "Sep 24"), the time in its clock (16:30 / 4:30 PM).
    val locale = LocalAppLocale.current
    val day = DateTimeFormatter.ofPattern(stringResource(Res.string.account_seen_date_pattern), locale)
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    return runCatching {
        val seen = OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault())
        "${seen.format(day)}, ${seen.format(clock)}"
    }.getOrNull() ?: stringResource(Res.string.account_seen_a_while_ago)
}

/** The sync state on one line, with the button so as not to wait. */
@Composable
private fun SyncStatusRow(statusFlow: kotlinx.coroutines.flow.StateFlow<eu.studio742.imago.core.sync.SyncStatus>, onSyncNow: () -> Unit) {
    val status by statusFlow.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
        Text(
            syncStatusText(status),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.error != null) MaterialTheme.colorScheme.error else ImagoColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = !status.running, onClick = onSyncNow) { Text(stringResource(Res.string.account_sync_now)) }
    }
}

@Composable
internal fun syncStatusText(status: eu.studio742.imago.core.sync.SyncStatus): String {
    val pending = if (status.pending == 0) null
    else pluralStringResource(Res.plurals.account_pending_changes, status.pending, status.pending)
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalAppLocale.current)
    val last = status.lastSyncedAt?.let { instant ->
        runCatching {
            java.time.Instant.parse(instant).atZone(java.time.ZoneId.systemDefault())
                .format(clock)
        }.getOrNull()
    }
    return when {
        status.running -> stringResource(Res.string.account_syncing)
        status.error != null -> status.error?.let { it.text().resolve() }.orEmpty() + (pending?.let { " · $it" } ?: "")
        pending != null -> pending
        last != null -> stringResource(Res.string.account_synced_at, last)
        else -> stringResource(Res.string.account_never_synced)
    }
}
