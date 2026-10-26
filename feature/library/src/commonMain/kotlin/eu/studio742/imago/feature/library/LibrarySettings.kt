@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.feature.library.resources.*
import eu.studio742.imago.core.designsystem.i18n.LanguagePreference
import eu.studio742.imago.core.designsystem.i18n.LocalLanguageSettings
import eu.studio742.imago.core.designsystem.resources.Res as CommonRes
import eu.studio742.imago.core.designsystem.resources.language_english
import eu.studio742.imago.core.designsystem.resources.language_portuguese
import eu.studio742.imago.core.designsystem.resources.language_system
import eu.studio742.imago.core.designsystem.resources.language_title
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.runtime.saveable.rememberSaveable
import eu.studio742.imago.core.designsystem.CollapsibleSettingsCard
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.*
import eu.studio742.imago.core.model.*

data class ConnectionTestState(val running: Boolean = false, val result: UiText? = null, val failed: Boolean = false)

open class LibrarySettingsViewModel(
    val configuration: ConfigurationRepository,
    val device: DeviceLibrary,
    /** Where the albums of any library are read from, to choose which ones its albums grid shows. */
    val library: LibraryRepository,
) : ViewModel() {
    val message = MutableStateFlow<UiText?>(null)
    val busy = MutableStateFlow(false)
    /** The last save failed on something in front of the server: the form opens its Advanced section. */
    val needsConnectionOptions = MutableStateFlow(false)
    val certificates = CertificateSupport(configuration::importClientCertificate, configuration::describeClientCertificate)
    val connectionTests = MutableStateFlow<Map<String, ConnectionTestState>>(emptyMap())
    fun save(id: String?, name: String, url: String, key: String, profile: ConnectionProfile, done: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            needsConnectionOptions.value = false
            runCatching { configuration.saveLibrary(id, name, url, key, profile) }
                .onSuccess {
                    if (id != null) connectionTests.update { it - id }
                    val missing = configuration.missingOptionalPermissions(ImmichConnection(url, key.trim()))
                    message.value = if (missing.isEmpty()) uiText(Res.string.library_saved)
                    else uiText(Res.string.library_saved_limited, missing.joinToString(", "))
                    done()
                }
                .onFailure { message.value = it.toUiText(Res.string.library_save_failed); needsConnectionOptions.value = it.isProtection() }
            busy.value = false
        }
    }
    fun test(id: String) { viewModelScope.launch {
        if (busy.value) return@launch
        busy.value = true
        connectionTests.update { it + (id to ConnectionTestState(running = true)) }
        try {
            val version = configuration.testLibrary(id)
            val missing = configuration.missingOptionalPermissions(configuration.source(id).connection())
            val result = if (missing.isEmpty()) uiText(Res.string.library_connection_ok, version)
                else uiText(Res.string.library_connection_ok_limited, version, missing.joinToString(", "))
            connectionTests.update { it + (id to ConnectionTestState(result = result)) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            connectionTests.update { it - id }
            throw cancelled
        } catch (error: Exception) {
            connectionTests.update { it + (id to ConnectionTestState(result = error.toUiText(Res.string.library_connect_failed), failed = true)) }
        } finally { busy.value = false }
    } }
    fun addUrl(id: String, url: String, profile: ConnectionProfile, done: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            needsConnectionOptions.value = false
            runCatching { configuration.addServerUrl(id, url, profile) }
                .onSuccess { message.value = uiText(Res.string.library_address_added, it); done() }
                .onFailure { message.value = it.toUiText(Res.string.library_address_add_failed); needsConnectionOptions.value = it.isProtection() }
            busy.value = false
        }
    }
    fun setProfile(id: String, url: String, profile: ConnectionProfile, done: () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            runCatching { configuration.setConnectionProfile(id, url, profile) }
                .onSuccess { message.value = uiText(Res.string.connection_options_saved); done() }
                .onFailure { message.value = it.toUiText(Res.string.connection_options_failed) }
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
    community: eu.studio742.imago.core.sync.CommunityRepository? = null,
) {
    val requestAccess = rememberRequestMediaAccess(viewModel)
    val sources by viewModel.configuration.libraries.collectAsStateWithLifecycle()
    val revision by viewModel.device.accessRevision.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val needsConnectionOptions by viewModel.needsConnectionOptions.collectAsStateWithLifecycle()
    val probes by viewModel.configuration.endpointProbes.collectAsStateWithLifecycle()
    val connectionTests by viewModel.connectionTests.collectAsStateWithLifecycle()
    var preferencesExpanded by rememberSaveable { mutableStateOf(false) }
    var librariesExpanded by rememberSaveable { mutableStateOf(false) }
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
                    CollapsibleSettingsCard(stringResource(Res.string.library_settings_preferences), Icons.Outlined.Tune,
                        preferencesExpanded, { preferencesExpanded = it }) {
                        LanguageSetting()
                        SettingsDivider()
                        OnThisDaySetting(viewModel)
                        SettingsDivider()
                        GridAlbumsSetting(viewModel, sources)
                    }
                }
                if (editing) {
                    Text(if (editId == null) stringResource(Res.string.library_add_immich_server) else stringResource(Res.string.library_edit_library),
                        style = MaterialTheme.typography.titleLarge, color = ImagoColors.TextPrimary)
                    // A linked library manages its addresses in the list; the key is validated by the one in use.
                    val connected = sources.firstOrNull { it.id == editId && it.isConnected && it.serverUrls.isNotEmpty() }
                    var headers by remember(editId) { mutableStateOf(emptyList<ConnectionHeader>()) }
                    var certificate by remember(editId) { mutableStateOf<ClientCertificate?>(null) }
                    LibrarySettingsCard {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.library_name)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (connected == null) {
                            OutlinedTextField(url, { url = it }, label = { Text(stringResource(Res.string.library_server_url)) }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(key, { key = it }, label = { Text(stringResource(Res.string.library_api_key)) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        ImmichKeyPermissions()
                        // A linked library keeps each address's options in the list below.
                        if (connected == null) ConnectionAdvanced(headers, { headers = it }, enabled = !busy, highlight = needsConnectionOptions,
                            certificate = certificate, onCertificateChange = { certificate = it }, certificates = viewModel.certificates)
                        Button(enabled = !busy && name.isNotBlank() && url.isNotBlank() && key.isNotBlank(),
                            onClick = {
                                val profile = if (connected == null) headers.toProfile(certificate)
                                    else connected.serverUrl?.let(connected::profileOf) ?: ConnectionProfile.None
                                viewModel.save(editId, name, connected?.serverUrl ?: url, key, profile) { editing = false; key = ""; headers = emptyList(); certificate = null }
                            },
                            modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.library_verify_save)) }
                        TextButton(enabled = !busy, onClick = back) { Text(stringResource(Res.string.library_cancel)) }
                    }
                    connected?.let { source ->
                        LibrarySettingsCard { ServerAddressList(source, probes[source.id].orEmpty(), busy, viewModel) }
                    }
                } else {
                    CollapsibleSettingsCard(stringResource(Res.string.library_libraries), Icons.Outlined.PhotoLibrary,
                        librariesExpanded, { librariesExpanded = it }) {
                        Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                                Icon(DeviceLibraryIcon, null, tint = ImagoColors.Ivory)
                                Text(stringResource(DeviceLibraryName), style = MaterialTheme.typography.titleMedium)
                            }
                            DeviceLibraryAccess(viewModel, revision, requestAccess)
                        }
                        // With more than one server, the unified library needs to know which one joins the phone.
                        val servers = sources.filter { !it.isDevice && it.isConnected }
                        if (servers.size > 1) {
                            SettingsDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
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
                            SettingsDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
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
                                connectionTests[source.id]?.let { test -> ConnectionTestResult(test) }
                            }
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            editId = null; name = ""; url = ""; key = ""; editing = true; viewModel.message.value = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                            Text(stringResource(Res.string.library_add_immich_server), Modifier.padding(start = ImagoSpacing.Sm))
                        }
                    }
                    SupportContactCard(viewModel, community)
                    aboutSection()
                }
                if (busy && connectionTests.values.none { it.running }) LinearProgressIndicator(Modifier.fillMaxWidth())
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
    var newHeaders by remember(source.id) { mutableStateOf(emptyList<ConnectionHeader>()) }
    // The address whose connection options are open, and what is typed in them.
    var optionsOf by remember(source.id) { mutableStateOf<String?>(null) }
    var optionsDraft by remember(source.id) { mutableStateOf(emptyList<ConnectionHeader>()) }
    var optionsCertificate by remember(source.id) { mutableStateOf<ClientCertificate?>(null) }
    var newCertificate by remember(source.id) { mutableStateOf<ClientCertificate?>(null) }
    Text(stringResource(Res.string.library_addresses), style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary)
    Text(stringResource(Res.string.library_addresses_hint),
        style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
    source.serverUrls.forEachIndexed { index, address ->
        val active = address == source.serverUrl
        val probe = probes[address]
        val certificateInfo = source.profileOf(address)?.certificate?.let(viewModel.certificates.describe)
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
                // The server refuses a device whose certificate ran out without saying why.
                if (certificateInfo != null && (certificateInfo.expired || certificateInfo.expiresSoon())) {
                    val until = java.time.Instant.ofEpochMilli(certificateInfo.notAfterMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
                    Text(if (certificateInfo.expired) stringResource(Res.string.connection_certificate_expired_on, until)
                        else stringResource(Res.string.connection_certificate_days_left, certificateInfo.daysLeft()),
                        style = MaterialTheme.typography.bodySmall, color = ImagoColors.Error)
                }
            }
            val options = source.profileOf(address)
            IconButton(enabled = !busy, onClick = {
                optionsOf = if (optionsOf == address) null else address
                optionsDraft = options?.headers.orEmpty()
                optionsCertificate = options?.certificate
            }) {
                Icon(Icons.Outlined.Tune, stringResource(Res.string.connection_address_settings, address),
                    tint = if (options != null) ImagoColors.Gold else androidx.compose.material3.LocalContentColor.current)
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
    optionsOf?.takeIf { it in source.serverUrls }?.let { address ->
        Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            Text(stringResource(Res.string.connection_address_settings, address), style = MaterialTheme.typography.titleSmall, color = ImagoColors.TextPrimary)
            ConnectionAdvanced(optionsDraft, { optionsDraft = it }, enabled = !busy, initiallyOpen = true,
                certificate = optionsCertificate, onCertificateChange = { optionsCertificate = it }, certificates = viewModel.certificates)
            Button(enabled = !busy, onClick = { viewModel.setProfile(source.id, address, optionsDraft.toProfile(optionsCertificate)) { optionsOf = null; optionsDraft = emptyList(); optionsCertificate = null } }) {
                Text(stringResource(Res.string.connection_save_options))
            }
        }
    }
    OutlinedTextField(newUrl, { newUrl = it }, label = { Text(stringResource(Res.string.library_new_address)) }, enabled = !busy, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    ConnectionAdvanced(newHeaders, { newHeaders = it }, enabled = !busy, highlight = viewModel.needsConnectionOptions.collectAsStateWithLifecycle().value,
        certificate = newCertificate, onCertificateChange = { newCertificate = it }, certificates = viewModel.certificates)
    Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy && newUrl.isNotBlank(), onClick = { viewModel.addUrl(source.id, newUrl, newHeaders.toProfile(newCertificate)) { newUrl = ""; newHeaders = emptyList(); newCertificate = null } }) {
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
private fun LanguageSetting() {
    val settings = LocalLanguageSettings.current ?: return
    val current by settings.preference.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        LanguagePreference.SYSTEM to CommonRes.string.language_system,
        LanguagePreference.ENGLISH to CommonRes.string.language_english,
        LanguagePreference.PORTUGUESE to CommonRes.string.language_portuguese,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
        Icon(Icons.Outlined.Language, null, tint = ImagoColors.Ivory)
        Text(stringResource(CommonRes.string.language_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(labels.getValue(current)))
                Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = ImagoSpacing.Xs).size(18.dp))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                labels.forEach { (option, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) },
                        trailingIcon = { if (option == current) Icon(Icons.Outlined.Check, null) },
                        onClick = { expanded = false; settings.choose(option) })
                }
            }
        }
    }
}

@Composable
internal fun LibrarySettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = ImagoColors.SurfaceElevated, shape = RoundedCornerShape(ImagoRadii.Medium), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ImagoSpacing.Lg), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md), content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = ImagoColors.BorderSubtle, modifier = Modifier.padding(vertical = ImagoSpacing.Xs))
}

@Composable
internal fun ConnectionTestResult(test: ConnectionTestState) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
        if (test.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(Res.string.library_connection_testing), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        } else test.result?.let { result ->
            Text(result.resolve(), style = MaterialTheme.typography.bodyMedium,
                color = if (test.failed) ImagoColors.Error else ImagoColors.TextSecondary)
        }
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
