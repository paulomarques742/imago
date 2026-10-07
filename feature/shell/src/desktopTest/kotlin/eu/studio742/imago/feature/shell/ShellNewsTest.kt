package eu.studio742.imago.feature.shell

import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.LibrarySource
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNewsTest {
    @Test fun aNewInstallRecordsItsVersionAndShowsNothing() {
        val configuration = FakeConfiguration(lastSeen = null, welcomeCompleted = false)
        assertTrue(ShellViewModel(configuration).newsState.value.isEmpty())
        assertEquals(APP_VERSION, configuration.lastSeenAppVersion)
    }

    @Test fun theNewsIsShownOnceAndNotAgainAfterClosing() {
        val configuration = FakeConfiguration(lastSeen = null, welcomeCompleted = true)
        val first = ShellViewModel(configuration)
        assertEquals(unseenReleases(null, true).map { it.version }, first.newsState.value.map { it.version })
        first.newsSeen()
        assertTrue(first.newsState.value.isEmpty())
        assertEquals(APP_VERSION, configuration.lastSeenAppVersion)
        assertTrue(ShellViewModel(configuration).newsState.value.isEmpty())
    }

    /** Leaving the app with the news still open is not having seen it. */
    @Test fun theNewsStaysPendingUntilClosed() {
        val configuration = FakeConfiguration(lastSeen = null, welcomeCompleted = true)
        val pending = ShellViewModel(configuration).newsState.value
        assertEquals(pending.map { it.version }, ShellViewModel(configuration).newsState.value.map { it.version })
    }

    private class FakeConfiguration(lastSeen: String?, welcomeCompleted: Boolean) : ConfigurationRepository {
        override var lastSeenAppVersion: String? = lastSeen
        override val welcomeCompleted = MutableStateFlow(welcomeCompleted)
        override val libraries = MutableStateFlow(listOf(LibrarySource(DEVICE_LIBRARY_ID, "")))
        override val selectedLibraryId = MutableStateFlow(DEVICE_LIBRARY_ID)
        override val endpointProbes = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())
        override val connection = MutableStateFlow<ImmichConnection?>(null)
        override var lastExportLibraryId: String? = null
        override fun completeWelcome() = Unit
        override fun selectLibrary(id: String) = Unit
        override fun source(id: String) = libraries.value.first { it.id == id }
        override suspend fun saveLibrary(id: String?, name: String, serverUrl: String, apiKey: String) = error("unused")
        override suspend fun testLibrary(id: String) = error("unused")
        override suspend fun addServerUrl(id: String, serverUrl: String) = error("unused")
        override fun removeServerUrl(id: String, serverUrl: String) = Unit
        override fun moveServerUrl(id: String, serverUrl: String, offset: Int) = Unit
        override suspend fun refreshEndpoints() = Unit
        override fun removeLibrary(id: String) = Unit
        override fun currentConnection(): ImmichConnection? = null
        override suspend fun validateAndSave(serverUrl: String, apiKey: String) = error("unused")
        override suspend fun clear() = Unit
    }
}
