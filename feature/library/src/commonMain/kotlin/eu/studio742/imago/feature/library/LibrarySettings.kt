@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.feature.library.resources.*
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.i18n.LanguagePreference
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.resources.Res as CommonRes
import eu.studio742.imago.core.designsystem.resources.language_english
import eu.studio742.imago.core.designsystem.resources.language_portuguese
import eu.studio742.imago.core.designsystem.resources.language_system
import eu.studio742.imago.core.designsystem.resources.language_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Layers
import eu.studio742.imago.core.model.UNIFIED_LIBRARY_ID
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoBrand
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.*
import eu.studio742.imago.core.model.*

open class LibrarySettingsViewModel(
    val configuration: ConfigurationRepository,
    val device: DeviceLibrary,
) : ViewModel() {
    val message = MutableStateFlow<UiText?>(null)
    val busy = MutableStateFlow(false)
    fun save(id: String?, name: String, url: String, key: String, done: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            runCatching { configuration.saveLibrary(id, name, url, key) }
                .onSuccess {
                    val missing = configuration.missingOptionalPermissions(ImmichConnection(url, key.trim()))
                    message.value = if (missing.isEmpty()) uiText(Res.string.library_saved)
                    else uiText(Res.string.library_saved_limited, missing.joinToString(", "))
                    done()
                }
                .onFailure { message.value = it.toUiText(Res.string.library_save_failed) }
            busy.value = false
        }
    }
    fun test(id: String) { viewModelScope.launch {
        busy.value = true
        message.value = runCatching {
            val version = configuration.testLibrary(id)
            val missing = configuration.missingOptionalPermissions(configuration.source(id).connection())
            if (missing.isEmpty()) uiText(Res.string.library_connection_ok, version)
            else uiText(Res.string.library_connection_ok_limited, version, missing.joinToString(", "))
        }.getOrElse { it.toUiText(Res.string.library_connect_failed) }
        busy.value = false
    } }
    fun addUrl(id: String, url: String, done: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            runCatching { configuration.addServerUrl(id, url) }
                .onSuccess { message.value = uiText(Res.string.library_address_added, it); done() }
                .onFailure { message.value = it.toUiText(Res.string.library_address_add_failed) }
            busy.value = false
        }
    }
    fun removeUrl(id: String, url: String) {
        runCatching { configuration.removeServerUrl(id, url) }.onFailure { message.value = it.toUiText() }
    }
    fun moveUrl(id: String, url: String, offset: Int) = configuration.moveServerUrl(id, url, offset)
    fun testUrls() { viewModelScope.launch {
        busy.value = true
        runCatching { configuration.refreshEndpoints() }.onFailure { message.value = it.toUiText() }
        busy.value = false
    } }
}

@Composable
fun LibrarySourceButton(viewModel: LibrarySettingsViewModel = librarySettingsViewModel()) {
    val sources by viewModel.configuration.libraries.collectAsStateWithLifecycle()
    val selected by viewModel.configuration.selectedLibraryId.collectAsStateWithLifecycle()
    val revision by viewModel.device.accessRevision.collectAsStateWithLifecycle()
    val requestAccess = rememberRequestMediaAccess(viewModel)
    var expanded by remember { mutableStateOf(false) }
    // The unified library joins the phone with a server: without one, it is not offered.
    val canUnify = sources.any { !it.isDevice && it.isConnected }
    val selectedName = if (selected == UNIFIED_LIBRARY_ID) stringResource(Res.string.library_unified_name, stringResource(DeviceLibraryName))
        else sources.firstOrNull { it.id == selected }?.displayName() ?: stringResource(DeviceLibraryName)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.PhotoLibrary,
                contentDescription = stringResource(Res.string.library_libraries_selected, selectedName),
                tint = ImagoColors.TextPrimary)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            sources.filter { it.isConnected }.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.displayName()) },
                    leadingIcon = { Icon(if (source.isDevice) DeviceLibraryIcon else Icons.Outlined.CloudQueue, null) },
                    trailingIcon = { if (source.id == selected) Icon(Icons.Outlined.Check, stringResource(Res.string.library_selected_single)) },
                    onClick = { expanded = false; viewModel.configuration.selectLibrary(source.id) },
                )
            }
            if (canUnify) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.library_unified_name, stringResource(DeviceLibraryName))) },
                    leadingIcon = { Icon(Icons.Outlined.Layers, null) },
                    trailingIcon = { if (selected == UNIFIED_LIBRARY_ID) Icon(Icons.Outlined.Check, stringResource(Res.string.library_selected_single)) },
                    onClick = { expanded = false; viewModel.configuration.selectLibrary(UNIFIED_LIBRARY_ID) },
                )
            }
            if (selected == DEVICE_LIBRARY_ID) {
                HorizontalDivider()
                Text(remember(revision) { viewModel.device.accessSummary() }.toUiText().resolve(),
                    style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary,
                    modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp))
                DropdownMenuItem(text = { Text(stringResource(Res.string.library_manage_access)) },
                    onClick = { expanded = false; requestAccess() })
            }
        }
    }
}

@Composable
fun LibrarySettingsRoute(
    onBack: () -> Unit,
    /** The account section, put in by whoever assembles the app: this feature does not know the account. */
    accountSection: @Composable () -> Unit = {},
    /** The app version and what changed in it, last on the page; also from whoever assembles the app. */
    aboutSection: @Composable () -> Unit = {},
    viewModel: LibrarySettingsViewModel = librarySettingsViewModel(),
) {
    val requestAccess = rememberRequestMediaAccess(viewModel)
    val sources by viewModel.configuration.libraries.collectAsStateWithLifecycle()
    val revision by viewModel.device.accessRevision.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val probes by viewModel.configuration.endpointProbes.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    // Credentials deliberately stay out of saved-instance state.
    var key by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<LibrarySource?>(null) }
    val back = {
        if (!busy) {
            if (editing) { editing = false; key = ""; viewModel.message.value = null }
            else onBack()
        }
    }
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
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.library_back), tint = ImagoColors.TextPrimary)
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
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(ImagoSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Lg),
            ) {
                Text(stringResource(Res.string.library_settings), style = MaterialTheme.typography.headlineLarge, color = ImagoColors.TextPrimary)
                if (!editing) {
                    accountSection()
                    LanguageCard()
                    LibrarySettingsCard { OnThisDaySetting(viewModel) }
                }
                Text(if (editing) { if (editId == null) stringResource(Res.string.library_add_immich_server) else stringResource(Res.string.library_edit_library) } else stringResource(Res.string.library_libraries),
                    style = MaterialTheme.typography.titleLarge, color = ImagoColors.TextPrimary)
                if (editing) {
                    // A linked library manages its addresses in the list; the key is validated by the one in use.
                    val connected = sources.firstOrNull { it.id == editId && it.isConnected && it.serverUrls.isNotEmpty() }
                    LibrarySettingsCard {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.library_name)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (connected == null) {
                            OutlinedTextField(url, { url = it }, label = { Text(stringResource(Res.string.library_server_url)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(key, { key = it }, label = { Text(stringResource(Res.string.library_api_key)) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        ImmichKeyPermissions()
                        Button(enabled = !busy && name.isNotBlank() && url.isNotBlank() && key.isNotBlank(),
                            onClick = { viewModel.save(editId, name, connected?.serverUrl ?: url, key) { editing = false; key = "" } },
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.library_verify_save)) }
                        TextButton(enabled = !busy, onClick = back) { Text(stringResource(Res.string.library_cancel)) }
                    }
                    connected?.let { source ->
                        LibrarySettingsCard { ServerAddressList(source, probes[source.id].orEmpty(), busy, viewModel) }
                    }
                } else {
                    LibrarySettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                            Icon(DeviceLibraryIcon, null, tint = ImagoColors.Ivory)
                            Text(stringResource(DeviceLibraryName), style = MaterialTheme.typography.titleMedium)
                        }
                        DeviceLibraryAccess(viewModel, revision, requestAccess)
                    }
                    // With more than one server, the unified library needs to know which one joins the phone.
                    val servers = sources.filter { !it.isDevice && it.isConnected }
                    if (servers.size > 1) {
                        LibrarySettingsCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                                Icon(Icons.Outlined.Layers, null, tint = ImagoColors.Ivory)
                                Text(stringResource(Res.string.library_unified_name, stringResource(DeviceLibraryName)), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(stringResource(Res.string.library_unified_partner), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
                            var partner by remember(servers) { mutableStateOf(viewModel.configuration.unifiedPartnerId) }
                            Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                                servers.forEach { source ->
                                    androidx.compose.material3.FilterChip(
                                        selected = partner == source.id,
                                        onClick = { viewModel.configuration.setUnifiedPartner(source.id); partner = source.id },
                                        label = { Text(source.displayName()) },
                                    )
                                }
                            }
                        }
                    }
                    sources.filterNot { it.isDevice }.forEach { source ->
                        LibrarySettingsCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                                Icon(Icons.Outlined.CloudQueue, null, tint = ImagoColors.Ivory)
                                Text(source.displayName(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            }
                            Text(source.serverUrl.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (source.serverUrls.size > 1) Text(stringResource(Res.string.library_addresses_count, source.serverUrls.size),
                                style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary)
                            if (!source.isConnected) Text(stringResource(Res.string.library_disconnected),
                                style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
                                TextButton(enabled = !busy, onClick = {
                                    editId = source.id; name = source.name; url = source.serverUrl.orEmpty(); key = source.apiKey.orEmpty(); editing = true
                                    viewModel.message.value = null
                                }) { Text(if (source.isConnected) stringResource(Res.string.library_edit) else stringResource(Res.string.library_connect)) }
                                if (source.isConnected) {
                                    TextButton(enabled = !busy, onClick = { viewModel.test(source.id) }) { Text(stringResource(Res.string.library_test)) }
                                    TextButton(enabled = !busy, onClick = { remove = source }) { Text(stringResource(Res.string.library_remove), color = ImagoColors.Danger) }
                                }
                            }
                        }
                    }
                    OutlinedButton(enabled = !busy, onClick = {
                        editId = null; name = ""; url = ""; key = ""; editing = true; viewModel.message.value = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Text(stringResource(Res.string.library_add_immich_server), Modifier.padding(start = ImagoSpacing.Sm))
                    }
                    aboutSection()
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary) }
            }
        }
    }
    remove?.let { source -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(Res.string.library_remove_question, source.displayName())) },
        text = { Text(stringResource(Res.string.library_remove_body)) },
        confirmButton = { TextButton(onClick = { viewModel.configuration.removeLibrary(source.id); remove = null }) { Text(stringResource(Res.string.library_remove), color = ImagoColors.Danger) } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(Res.string.library_cancel)) } }) }
}

/**
 * An Immich library's addresses, in the order they are tried.
 *
 * The one in use has an indicator; the others show the result of the last test, or that they have
 * not been tested yet — the choice stops at the first that answers.
 */
@Composable
private fun ColumnScope.ServerAddressList(
    source: LibrarySource,
    probes: Map<String, Boolean>,
    busy: Boolean,
    viewModel: LibrarySettingsViewModel,
) {
    var newUrl by remember(source.id) { mutableStateOf("") }
    Text(stringResource(Res.string.library_addresses), style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary)
    Text(stringResource(Res.string.library_addresses_hint),
        style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
    source.serverUrls.forEachIndexed { index, address ->
        val active = address == source.serverUrl
        val probe = probes[address]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(
                if (active) ImagoColors.Gold else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(50),
            ))
            Column(Modifier.weight(1f).padding(start = ImagoSpacing.Sm)) {
                Text(address, style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        active && probe == false -> stringResource(Res.string.library_address_active_no_response)
                        active -> stringResource(Res.string.library_address_active)
                        probe == true -> stringResource(Res.string.library_address_responded)
                        probe == false -> stringResource(Res.string.library_address_no_response)
                        else -> stringResource(Res.string.library_address_untested)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probe == false) ImagoColors.Error else ImagoColors.TextSecondary,
                )
            }
            IconButton(enabled = !busy && index > 0, onClick = { viewModel.moveUrl(source.id, address, -1) }) {
                Icon(Icons.Outlined.ArrowUpward, stringResource(Res.string.library_move_up, address))
            }
            IconButton(enabled = !busy && index < source.serverUrls.lastIndex, onClick = { viewModel.moveUrl(source.id, address, 1) }) {
                Icon(Icons.Outlined.ArrowDownward, stringResource(Res.string.library_move_down, address))
            }
            IconButton(enabled = !busy && source.serverUrls.size > 1, onClick = { viewModel.removeUrl(source.id, address) }) {
                Icon(Icons.Outlined.Close, stringResource(Res.string.library_remove_address, address))
            }
        }
    }
    OutlinedTextField(newUrl, { newUrl = it }, label = { Text(stringResource(Res.string.library_new_address)) }, enabled = !busy, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy && newUrl.isNotBlank(), onClick = { viewModel.addUrl(source.id, newUrl) { newUrl = "" } }) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Text(stringResource(Res.string.library_add_address), Modifier.padding(start = ImagoSpacing.Sm))
        }
        if (source.serverUrls.size > 1) TextButton(enabled = !busy, onClick = viewModel::testUrls) { Text(stringResource(Res.string.library_test_addresses)) }
    }
}

/**
 * The app language: the system's, or a chosen one. The language names are always in the language
 * itself, so whoever got lost in one they cannot read can find their way back.
 */
@Composable
private fun LanguageCard() {
    val settings = LocalLanguageSettings.current ?: return
    val current by settings.preference.collectAsState()
    LibrarySettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
            Icon(Icons.Outlined.Language, null, tint = ImagoColors.Ivory)
            Text(stringResource(CommonRes.string.language_title), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.selectableGroup()) {
            LanguagePreference.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = ImagoSizes.TouchTarget)
                        .selectable(selected = option == current, role = Role.RadioButton, onClick = { settings.choose(option) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == current, onClick = null)
                    Text(
                        stringResource(
                            when (option) {
                                LanguagePreference.SYSTEM -> CommonRes.string.language_system
                                LanguagePreference.ENGLISH -> CommonRes.string.language_english
                                LanguagePreference.PORTUGUESE -> CommonRes.string.language_portuguese
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = ImagoSpacing.Md),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = ImagoColors.SurfaceElevated, shape = RoundedCornerShape(ImagoRadii.Medium), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ImagoSpacing.Lg), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md), content = content)
    }
}

/** Destination is returned explicitly to the caller and persisted by the export job. */
@Composable
fun ExportLibrarySelector(originId: String? = null, onSelected: (String?) -> Unit, viewModel: LibrarySettingsViewModel = librarySettingsViewModel()) {
    val sources by viewModel.configuration.libraries.collectAsStateWithLifecycle()
    val servers = sources.filter { !it.isDevice && it.isConnected }
    var selected by remember(originId) { mutableStateOf(originId?.takeIf { id -> servers.any { it.id == id } }
        ?: viewModel.configuration.lastExportLibraryId?.takeIf { id -> servers.any { it.id == id } } ?: servers.firstOrNull()?.id) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(selected, servers) {
        if (servers.none { it.id == selected }) selected = servers.firstOrNull()?.id
        onSelected(selected)
    }
    if (servers.isEmpty()) Text(stringResource(Res.string.library_connect_server_hint))
    else Box {
        TextButton(onClick = { expanded = true }) { Text(stringResource(Res.string.library_destination, servers.firstOrNull { it.id == selected }?.displayName().orEmpty())) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            servers.forEach { source -> DropdownMenuItem(text = { Text(source.displayName()) }, onClick = {
                selected = source.id; viewModel.configuration.lastExportLibraryId = source.id; expanded = false
            }) }
        }
    }
}
