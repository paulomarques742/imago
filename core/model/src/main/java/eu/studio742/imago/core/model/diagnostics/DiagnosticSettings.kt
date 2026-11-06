package eu.studio742.imago.core.model.diagnostics

import eu.studio742.imago.core.model.FolderTransfer
import eu.studio742.imago.core.model.RecipePart

enum class DiagnosticLibraryKind { UNKNOWN, DEVICE, IMMICH, UNIFIED, OPENED_FILE }
enum class DiagnosticLanguage { UNKNOWN, SYSTEM, ENGLISH, PORTUGUESE }
enum class DiagnosticMediaAccess { UNKNOWN, FULL, SELECTED, ONE_TYPE, NONE, FOLDERS }
enum class DiagnosticReelStyle { PANEL, PILL, LIST }

/** A position in this device's current configuration, never a persisted or hashed identifier. */
data class DiagnosticLibrary(val kind: DiagnosticLibraryKind, val slot: Int? = null) {
    override fun toString(): String = kind.name + (slot?.takeIf { kind == DiagnosticLibraryKind.IMMICH && it > 0 }?.let { "#$it" } ?: "")
}

data class DiagnosticEndpoint(
    val connection: DiagnosticConnection,
    val customHeaders: Boolean,
    val customCertificate: Boolean,
)

data class DiagnosticLibrarySettings(
    val library: DiagnosticLibrary,
    val connected: Boolean,
    val endpoints: List<DiagnosticEndpoint>,
    val activeEndpoint: Int?,
    val hiddenGridAlbums: Int,
)

/** An explicit allowlist. Raw preferences, names, paths and recipe contents cannot enter here. */
data class DiagnosticSettings(
    val languagePreference: DiagnosticLanguage = DiagnosticLanguage.UNKNOWN,
    val effectiveLanguage: DiagnosticLanguage = DiagnosticLanguage.UNKNOWN,
    val showOnThisDay: Boolean,
    val welcomeCompleted: Boolean,
    val selectedLibrary: DiagnosticLibrary,
    val unifiedPartner: DiagnosticLibrary,
    val lastExportLibrary: DiagnosticLibrary,
    val libraries: List<DiagnosticLibrarySettings>,
    val mediaAccess: DiagnosticMediaAccess,
    val folderCount: Int?,
    val hiddenDeviceAlbums: Int,
    val rememberedTransfer: FolderTransfer?,
    val recipeParts: Set<RecipePart>?,
    val reelStyle: DiagnosticReelStyle?,
    val brushConfigured: Boolean,
    val retouchConfigured: Boolean,
) {
    internal fun lines(): String = buildString {
        appendLine("\nApp settings (anonymous snapshot at report creation):")
        appendLine("languagePreference=$languagePreference effectiveLanguage=$effectiveLanguage")
        appendLine("showOnThisDay=$showOnThisDay welcomeCompleted=$welcomeCompleted")
        appendLine("selectedLibrary=$selectedLibrary unifiedPartner=$unifiedPartner lastExportLibrary=$lastExportLibrary")
        appendLine("mediaAccess=$mediaAccess folderCount=${folderCount?.coerceAtLeast(0) ?: "unknown"} hiddenDeviceAlbums=${hiddenDeviceAlbums.coerceAtLeast(0)} transfer=${rememberedTransfer ?: "ASK"}")
        appendLine("recipeParts=${recipeParts?.sortedBy { it.ordinal }?.joinToString(",") ?: "DEFAULT"} reelStyle=${reelStyle ?: "DEFAULT"} brushConfigured=$brushConfigured retouchConfigured=$retouchConfigured")
        libraries.forEach { source ->
            appendLine("library=${source.library} connected=${source.connected} activeEndpoint=${source.activeEndpoint ?: "unknown"} hiddenGridAlbums=${source.hiddenGridAlbums.coerceAtLeast(0)}")
            source.endpoints.forEachIndexed { index, endpoint ->
                appendLine("  endpoint=${index + 1} protocol=${endpoint.connection.protocol} address=${endpoint.connection.address} customHeaders=${endpoint.customHeaders} customCertificate=${endpoint.customCertificate}")
            }
        }
    }
}
