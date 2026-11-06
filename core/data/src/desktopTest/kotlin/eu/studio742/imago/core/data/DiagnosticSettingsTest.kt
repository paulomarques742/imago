package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.*
import eu.studio742.imago.core.model.diagnostics.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class DiagnosticSettingsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = LibrarySource("private-library-id", "Private library name",
        listOf("http://192.168.42.5", "https://private.example.invalid"), "private-user-id", "private-api-key",
        activeUrl = "https://private.example.invalid", profiles = mapOf("https://private.example.invalid" to
            ConnectionProfile(listOf(ConnectionHeader("Private-Header", "private-header-value")), ClientCertificate("private-pkcs12", "private-password"))))
    private val sources = MutableStateFlow(listOf(LibrarySource(DEVICE_LIBRARY_ID, ""), source,
        LibrarySource("other-private-id", "Other private name")))
    private val selected = MutableStateFlow(source.id)

    private inline fun <reified T> proxy(crossinline read: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> read(method.name) } as T

    private fun configuration() = proxy<ConfigurationRepository> { method -> when (method) {
        "getLibraries" -> sources
        "getSelectedLibraryId" -> selected
        "getUnifiedPartnerId", "getLastExportLibraryId" -> source.id
        "getShowOnThisDay" -> MutableStateFlow(false)
        "getWelcomeCompleted" -> MutableStateFlow(true)
        "getAlbumsHiddenFromGrid" -> MutableStateFlow(mapOf(source.id to setOf("private-album-id")))
        "getRecipePartsChoice" -> "EXPOSURE,MASKS,private-injected-text"
        "getReelStyle" -> "PILL"
        "getBrushTool", "getRetouchTool" -> "private-invalid-preference"
        else -> error("Report must not call $method")
    } }
    private fun device(message: UserMessage = UserMessage.DEVICE_ACCESS_SELECTED) = proxy<DeviceLibrary> { method -> when (method) {
        "accessSummary" -> UserText(message, if (message == UserMessage.FOLDERS_MANY) listOf(3, "private-folder-path") else emptyList())
        "hiddenAlbums" -> flowOf(setOf("private-hidden-album"))
        "getRememberedTransfer" -> MutableStateFlow(FolderTransfer.COPY)
        else -> error("Report must not call $method")
    } }

    @Test fun reportsSettingsAndSelectedLibraryWithoutAnyIdentityOrRawPreferences() = runBlocking {
        val settings = configuration().diagnosticSettings(device())
        assertEquals(DiagnosticLibrary(DiagnosticLibraryKind.IMMICH, 1), settings.selectedLibrary)
        assertEquals(2, settings.libraries[1].activeEndpoint)
        assertEquals(1, settings.libraries[1].hiddenGridAlbums)
        assertEquals(DiagnosticMediaAccess.SELECTED, settings.mediaAccess)
        assertEquals(setOf(RecipePart.EXPOSURE, RecipePart.MASKS), settings.recipeParts)
        assertEquals(DiagnosticReelStyle.PILL, settings.reelStyle)
        val report = DiagnosticStore(temporary.newFolder()).report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList(), settings).readText()
        assertTrue(report.contains("selectedLibrary=IMMICH#1"))
        assertTrue(report.contains("showOnThisDay=false"))
        assertTrue(report.contains("protocol=HTTPS address=HOSTNAME customHeaders=true customCertificate=true"))
        assertTrue(report.contains("library=IMMICH#2 connected=false"))
        listOf(source.id, source.name, source.userId!!, source.apiKey!!, "192.168.42.5", "private.example.invalid",
            "Private-Header", "private-header-value", "private-pkcs12", "private-password", "private-album-id",
            "private-hidden-album", "private-invalid-preference", "private-injected-text").forEach { assertFalse(it, report.contains(it)) }
    }

    @Test fun identifiesDeviceUnifiedAndMissingLibrariesAndCountsFoldersWithoutPaths() = runBlocking {
        val configuration = configuration()
        selected.value = UNIFIED_LIBRARY_ID
        assertEquals(DiagnosticLibraryKind.UNIFIED, configuration.diagnosticSettings(device()).selectedLibrary.kind)
        selected.value = DEVICE_LIBRARY_ID
        val settings = configuration.diagnosticSettings(device(UserMessage.FOLDERS_MANY))
        assertEquals(DiagnosticLibraryKind.DEVICE, settings.selectedLibrary.kind)
        assertEquals(3, settings.folderCount)
        assertEquals(DiagnosticMediaAccess.FOLDERS, settings.mediaAccess)
        assertEquals(DiagnosticLibraryKind.UNKNOWN, configuration.diagnosticLibrary("removed-private-library").kind)
        assertEquals(DiagnosticLibraryKind.OPENED_FILE, configuration.diagnosticLibrary(OPENED_LIBRARY_ID).kind)
    }
}
