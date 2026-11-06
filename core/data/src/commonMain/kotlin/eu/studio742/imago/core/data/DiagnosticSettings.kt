package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.*
import eu.studio742.imago.core.model.diagnostics.*
import kotlinx.coroutines.flow.first

/** IDs are compared locally and discarded; a report only knows the server's position in this list. */
fun ConfigurationRepository.diagnosticLibrary(id: String?): DiagnosticLibrary = diagnosticLibrary(libraries.value, id)

private fun diagnosticLibrary(sources: List<LibrarySource>, id: String?): DiagnosticLibrary {
    val kind = when (id) {
        DEVICE_LIBRARY_ID -> DiagnosticLibraryKind.DEVICE
        UNIFIED_LIBRARY_ID -> DiagnosticLibraryKind.UNIFIED
        OPENED_LIBRARY_ID -> DiagnosticLibraryKind.OPENED_FILE
        else -> DiagnosticLibraryKind.IMMICH
    }
    if (kind != DiagnosticLibraryKind.IMMICH) return DiagnosticLibrary(kind)
    val index = sources.filter { !it.isDevice && !it.isUnified }.indexOfFirst { it.id == id }
    return if (index >= 0) DiagnosticLibrary(kind, index + 1) else DiagnosticLibrary(DiagnosticLibraryKind.UNKNOWN)
}

/** Reads local state only; creating or reviewing a report must never probe the server. */
suspend fun ConfigurationRepository.diagnosticSettings(device: DeviceLibrary): DiagnosticSettings {
    val sources = libraries.value
    fun reference(id: String?) = diagnosticLibrary(sources, id)
    val summary = device.accessSummary()
    val access = when (summary.message) {
        UserMessage.DEVICE_ACCESS_FULL -> DiagnosticMediaAccess.FULL
        UserMessage.DEVICE_ACCESS_SELECTED -> DiagnosticMediaAccess.SELECTED
        UserMessage.DEVICE_ACCESS_ONE_TYPE -> DiagnosticMediaAccess.ONE_TYPE
        UserMessage.DEVICE_ACCESS_NONE -> DiagnosticMediaAccess.NONE
        UserMessage.FOLDERS_NONE, UserMessage.FOLDERS_ONE, UserMessage.FOLDERS_MANY -> DiagnosticMediaAccess.FOLDERS
        else -> DiagnosticMediaAccess.UNKNOWN
    }
    return DiagnosticSettings(
        showOnThisDay = showOnThisDay.value,
        welcomeCompleted = welcomeCompleted.value,
        selectedLibrary = reference(selectedLibraryId.value),
        unifiedPartner = reference(unifiedPartnerId),
        lastExportLibrary = reference(lastExportLibraryId),
        libraries = sources.map { source ->
            DiagnosticLibrarySettings(
                library = reference(source.id), connected = source.isConnected,
                endpoints = source.serverUrls.map { url ->
                    val profile = source.profileOf(url)
                    DiagnosticEndpoint(DiagnosticConnection.from(url), !profile?.headers.isNullOrEmpty(), profile?.certificate != null)
                },
                activeEndpoint = source.serverUrls.indexOf(source.serverUrl).takeIf { it >= 0 }?.plus(1),
                hiddenGridAlbums = albumsHiddenFromGrid.value[source.id].orEmpty().size,
            )
        },
        mediaAccess = access,
        folderCount = when (summary.message) {
            UserMessage.FOLDERS_NONE -> 0
            UserMessage.FOLDERS_ONE -> 1
            UserMessage.FOLDERS_MANY -> (summary.args.firstOrNull() as? Int)?.coerceAtLeast(0)
            else -> null
        },
        hiddenDeviceAlbums = device.hiddenAlbums().first().size,
        rememberedTransfer = device.rememberedTransfer.value,
        recipeParts = recipePartsChoice?.let { RecipePart.parse(it.split(',')) },
        reelStyle = DiagnosticReelStyle.entries.firstOrNull { it.name == reelStyle },
        brushConfigured = brushTool != null,
        retouchConfigured = retouchTool != null,
    )
}
