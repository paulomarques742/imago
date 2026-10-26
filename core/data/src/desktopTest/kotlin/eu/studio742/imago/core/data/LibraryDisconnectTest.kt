package eu.studio742.imago.core.data

import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.OkHttpImmichApi
import eu.studio742.imago.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDisconnectTest {
    private fun configuration(): EncryptedConfigurationRepository {
        val values = mutableMapOf<String, String>()
        val preferences = object : SecurePreferences {
            override fun getString(key: String) = values[key]
            override fun write(changes: Map<String, String?>): Boolean {
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
        }
        val api = object : ImmichApi by OkHttpImmichApi() {
            override suspend fun validateConnection(connection: ImmichConnection) = ServerVersion(3, 3, 5)
            override suspend fun currentUserId(connection: ImmichConnection) = "user-${connection.apiKey}"
            override suspend fun keyPermissions(connection: ImmichConnection) = setOf("all")
        }
        return EncryptedConfigurationRepository(preferences, api)
    }

    @Test fun removingTheActiveLibraryNeverPublishesItAsSelectedAndDisconnected() = runTest {
        val configuration = configuration()
        configuration.saveLibrary(null, "Test library", "https://test.invalid", "test-key")
        val id = configuration.libraries.value.last().id
        configuration.selectLibrary(id)
        val disconnectedSelections = mutableListOf<String>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            configuration.libraries.collect { sources ->
                val selected = configuration.selectedLibraryId.value
                if (sources.any { it.id == selected && !it.isConnected }) disconnectedSelections += selected
            }
        }
        configuration.removeLibrary(id)
        assertEquals(DEVICE_LIBRARY_ID, configuration.selectedLibraryId.value)
        assertFalse(configuration.source(id).isConnected)
        assertTrue("A source observer must never see a disconnected current selection", disconnectedSelections.isEmpty())
        observer.cancelAndJoin()
    }

    @Test fun removingAnotherLibraryKeepsTheCurrentLibraryAndItsCredential() = runTest {
        val configuration = configuration()
        configuration.saveLibrary(null, "First", "https://first.invalid", "first-key")
        val first = configuration.libraries.value.last().id
        configuration.saveLibrary(null, "Second", "https://second.invalid", "second-key")
        val second = configuration.libraries.value.last().id
        configuration.selectLibrary(first)
        configuration.removeLibrary(second)
        assertEquals(first, configuration.selectedLibraryId.value)
        assertTrue(configuration.source(first).isConnected)
        assertFalse(configuration.source(second).isConnected)
    }
}
