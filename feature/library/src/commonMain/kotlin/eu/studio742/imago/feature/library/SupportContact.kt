package eu.studio742.imago.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.model.diagnostics.*
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.net.URI

internal data class SupportDraft(val attachment: File, val subject: String, val body: String)

@Composable
internal expect fun rememberSupportEmail(): suspend (SupportDraft) -> Unit

@Composable
internal fun SupportContactCard(viewModel: LibrarySettingsViewModel, community: eu.studio742.imago.core.sync.CommunityRepository? = null) {
    var open by remember { mutableStateOf(false) }
    var communityOpen by rememberSaveable { mutableStateOf(false) }
    var postsOpen by remember { mutableStateOf(false) }
    val posts = community?.posts?.collectAsState()?.value.orEmpty()
    var expanded by rememberSaveable { mutableStateOf(false) }
    eu.studio742.imago.core.designsystem.CollapsibleSettingsCard(
        stringResource(Res.string.library_support_title), Icons.Outlined.HelpOutline, expanded, { expanded = it }) {
        Text(stringResource(Res.string.library_support_privacy), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        OutlinedButton(onClick = { open = true }) { Text(stringResource(Res.string.library_support_contact)) }
        if (community != null) {
            HorizontalDivider()
            Text(stringResource(Res.string.community_invitation), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(Res.string.community_description), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
            OutlinedButton(onClick = { communityOpen = true }) { Text(stringResource(Res.string.community_new)) }
            if (posts.isNotEmpty()) TextButton(onClick = { postsOpen = true }) { Text(stringResource(Res.string.community_my_posts)) }
        }
    }
    if (open) SupportDialog(viewModel, community, onDismiss = { open = false })
    if (communityOpen && community != null) CommunityDialog(community, onDismiss = { communityOpen = false })
    if (postsOpen && community != null) CommunityPostsDialog(community, onDismiss = { postsOpen = false })
}

@Composable
private fun SupportDialog(viewModel: LibrarySettingsViewModel, community: eu.studio742.imago.core.sync.CommunityRepository?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val email = rememberSupportEmail()
    var description by remember { mutableStateOf("") }
    var access by remember { mutableStateOf(DiagnosticAccess.UNKNOWN) }
    var expanded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<SupportDraft?>(null) }
    var preview by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val labels = mapOf(
        DiagnosticAccess.UNKNOWN to Res.string.library_support_access_unknown,
        DiagnosticAccess.LOCAL_NETWORK to Res.string.library_support_access_local,
        DiagnosticAccess.DIRECT_REMOTE to Res.string.library_support_access_direct,
        DiagnosticAccess.REVERSE_PROXY to Res.string.library_support_access_proxy,
        DiagnosticAccess.VPN to Res.string.library_support_access_vpn,
        DiagnosticAccess.TUNNEL to Res.string.library_support_access_tunnel,
    )
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.library_support_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (draft == null) {
                    Text(stringResource(Res.string.library_support_privacy))
                    OutlinedTextField(description, { description = it.take(2_000) }, enabled = !busy,
                        label = { Text(stringResource(Res.string.library_support_description)) },
                        minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.library_support_connection))
                    Box {
                        OutlinedButton(enabled = !busy, onClick = { expanded = true }) { Text(stringResource(labels.getValue(access))) }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            labels.forEach { (value, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { access = value; expanded = false })
                            }
                        }
                    }
                } else {
                    Text(stringResource(Res.string.library_support_review_hint))
                    val lines = remember(preview) { preview.lineSequence().toList() }
                    SelectionContainer {
                        LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                            items(lines) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (LocalDiagnostics.storageFailed) Text(stringResource(Res.string.library_support_incomplete), color = MaterialTheme.colorScheme.error)
                if (error) Text(stringResource(Res.string.library_support_failed), color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && description.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    error = false
                    try {
                        val prepared = draft
                        if (prepared != null) {
                            email(prepared)
                            onDismiss()
                        } else {
                            val sources = viewModel.configuration.libraries.value
                            val secrets = community?.localSecrets.orEmpty() + sources.flatMap { source ->
                                listOfNotNull(source.apiKey) + source.profiles.values.flatMap { profile ->
                                    profile.headers.flatMap { listOf(it.name, it.value) }.plus(listOfNotNull(profile.certificate?.password)).filter { it.length >= 3 }
                                } + source.serverUrls.flatMap { url ->
                                    listOfNotNull(url, runCatching { URI(url).host }.getOrNull())
                                }
                            }
                            val body = redactSupportDescription(description.trim(), secrets)
                            val report = withContext(Dispatchers.IO) {
                                checkNotNull(LocalDiagnostics.store).report(LocalDiagnostics.metadata, body, access, secrets)
                            }
                            preview = withContext(Dispatchers.IO) { report.readText(Charsets.UTF_8) }
                            draft = SupportDraft(report, appString(Res.string.library_support_subject), body)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(if (draft == null) Res.string.library_support_review else Res.string.library_support_email)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { if (draft != null) { draft = null; preview = ""; error = false } else onDismiss() }) {
                Text(stringResource(if (draft != null) Res.string.library_support_edit else Res.string.library_cancel))
            }
        },
    )
}
