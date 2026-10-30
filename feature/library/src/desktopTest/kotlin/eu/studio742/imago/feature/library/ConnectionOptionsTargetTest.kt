package eu.studio742.imago.feature.library

import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DesktopDataGraph
import eu.studio742.imago.core.model.ConnectionHeader
import eu.studio742.imago.core.model.ConnectionProfile
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.LibrarySource
import eu.studio742.imago.core.model.ServerVersion
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionOptionsTargetTest {
    @get:Rule val temporary = TemporaryFolder()

    private val home = "http://192.168.1.10:2283"
    private val away = "https://photos.example.test"
    private val protected = UserMessageException(UserMessage.IMMICH_PROTECTED)

    private fun withModel(failure: Exception?, block: suspend TestScope.(LibrarySettingsViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = DesktopDataGraph(temporary.newFolder().toPath())
        try {
            val configuration = object : ConfigurationRepository by graph.configuration {
                private fun answer(): ServerVersion = failure?.let { throw it } ?: ServerVersion(3, 3, 5)
                override fun source(id: String) = LibrarySource(id, id, listOf(home, away), apiKey = "test-key", activeUrl = away)
                override suspend fun saveLibrary(id: String?, name: String, serverUrl: String, apiKey: String, profile: ConnectionProfile) = answer()
                override suspend fun testLibrary(id: String) = answer()
                override suspend fun addServerUrl(id: String, serverUrl: String, profile: ConnectionProfile) = answer()
                override suspend fun setConnectionProfile(id: String, serverUrl: String, profile: ConnectionProfile) = answer()
                override suspend fun missingOptionalPermissions(connection: ImmichConnection) = emptyList<String>()
            }
            block(LibrarySettingsViewModel(configuration, graph.folders, graph.library))
        } finally { graph.close(); Dispatchers.resetMain() }
    }

    @Test fun aSaveStoppedByAProxyPointsAtTheAddressItTried() = withModel(protected) { model ->
        model.save("lib", "Home", away, "test-key", ConnectionProfile.None) {}
        runCurrent()
        assertEquals(away, model.needsConnectionOptions.value)
    }

    @Test fun aTestStoppedByAProxyPointsAtTheAddressInUse() = withModel(protected) { model ->
        model.test("lib")
        runCurrent()
        assertEquals(away, model.needsConnectionOptions.value)
    }

    @Test fun anAddressBeingAddedIsPointedAtAsTheNewOne() = withModel(protected) { model ->
        val typed = "https://other.example.test"
        model.addUrl("lib", typed, ConnectionProfile.None) {}
        runCurrent()
        assertEquals(typed, model.needsConnectionOptions.value)
    }

    @Test fun aFailureThatIsNotAProxyPointsNowhere() = withModel(IOException("Test failure")) { model ->
        model.save("lib", "Home", away, "test-key", ConnectionProfile.None) {}
        runCurrent()
        model.test("lib")
        runCurrent()
        assertNull(model.needsConnectionOptions.value)
    }

    @Test fun savingTheAddressOptionsClearsThePointer() = withModel(null) { model ->
        model.needsConnectionOptions.value = away
        model.setProfile("lib", away, ConnectionProfile(listOf(ConnectionHeader("CF-Access-Client-Id", "id")))) {}
        runCurrent()
        assertNull(model.needsConnectionOptions.value)
    }

    @Test fun onlyWhatIsTypedAndNotSavedCountsAsUnsaved() {
        val saved = ConnectionProfile(listOf(ConnectionHeader("CF-Access-Client-Id", "id")))
        assertFalse(hasUnsavedConnectionOptions("", ConnectionProfile.None, null, null))
        // Options opened and left as they were, or with only empty rows added.
        assertFalse(hasUnsavedConnectionOptions("", ConnectionProfile.None, saved, saved))
        assertFalse(hasUnsavedConnectionOptions(" ", listOf(ConnectionHeader("", "")).toProfile(), ConnectionProfile.None, null))
        assertTrue(hasUnsavedConnectionOptions("", ConnectionProfile.None, ConnectionProfile(saved.headers + ConnectionHeader("CF-Access-Client-Secret", "s")), saved))
        assertTrue(hasUnsavedConnectionOptions("", ConnectionProfile.None, saved, null))
        // A new address, or headers for it, not added yet.
        assertTrue(hasUnsavedConnectionOptions(away, ConnectionProfile.None, null, null))
        assertTrue(hasUnsavedConnectionOptions("", saved, null, null))
    }
}
