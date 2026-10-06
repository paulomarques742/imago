package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import eu.studio742.imago.core.immich.*
import eu.studio742.imago.core.model.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LibraryEndpointsTest {
    private val reachable = mutableSetOf(HOME, AWAY)
    private val pings = AtomicInteger()
    private val api = object : ImmichApi by OkHttpImmichApi(OkHttpClient()) {
        override suspend fun validateConnection(connection: ImmichConnection): ServerVersion {
            if (connection.serverUrl == LOCKED) throw ImmichApiException.Authentication()
            return ServerVersion(2, 6, 3)
        }
        override suspend fun currentUserId(connection: ImmichConnection) =
            if (connection.serverUrl == STRANGER) "user-b" else "user-a"
        override suspend fun ping(serverUrl: String, timeoutMillis: Long): Boolean {
            pings.incrementAndGet()
            return serverUrl in reachable
        }
    }
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences(java.util.UUID.randomUUID().toString(), 0)

    @Test fun storedSingleAddressBecomesAListOfOne() {
        val prefs = prefs()
        prefs.edit().putString("libraries_v2",
            """[{"id":"device","name":"Dispositivo"},{"id":"old-key","name":"Casa","serverUrl":"$HOME","userId":"user-a","apiKey":"secret"}]""",
        ).commit()
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        assertEquals(listOf(HOME), repo.source("old-key").serverUrls)
        assertEquals(HOME, repo.source("old-key").connection().serverUrl)
        assertFalse(prefs.getString("libraries_v2", null)!!.contains("\"serverUrl\""))
        assertEquals(repo.libraries.value, EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api).libraries.value)
    }

    @Test fun newLibraryIsTheAccountAndAnotherAddressJoinsIt() = runBlocking {
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs()), api)
        repo.saveLibrary(null, "Casa", HOME, "secret")
        val id = repo.libraries.value.last().id
        assertEquals(libraryKeyOf("immich|user-a"), id)
        repo.saveLibrary(null, "Casa", AWAY, "secret")
        assertEquals(2, repo.libraries.value.size)
        assertEquals(listOf(HOME, AWAY), repo.source(id).serverUrls)
    }

    @Test fun addressOfAnotherServerOrAccountIsRefused() = runBlocking {
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs()), api)
        repo.saveLibrary(null, "Casa", HOME, "secret")
        val id = repo.libraries.value.last().id
        val stranger = runCatching { repo.addServerUrl(id, STRANGER) }.exceptionOrNull()
        assertEquals(UserMessage.ADDRESS_WRONG_SERVER, (stranger as? UserMessageException)?.userMessage)
        val locked = runCatching { repo.addServerUrl(id, LOCKED) }.exceptionOrNull()
        assertEquals(UserMessage.ADDRESS_OTHER_ACCOUNT, (locked as? UserMessageException)?.userMessage)
        assertTrue(runCatching { repo.addServerUrl(id, "$HOME/api/") }.isFailure)
        assertEquals(listOf(HOME), repo.source(id).serverUrls)
        repo.addServerUrl(id, AWAY)
        assertEquals(listOf(HOME, AWAY), repo.source(id).serverUrls)
        assertTrue(runCatching { repo.removeServerUrl(id, HOME); repo.removeServerUrl(id, AWAY) }.isFailure)
        assertEquals(listOf(AWAY), repo.source(id).serverUrls)
    }

    @Test fun activeAddressIsTheFirstThatAnswersAndInFlightRequestsKeepTheirs() = runBlocking {
        val prefs = prefs()
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        repo.saveLibrary(null, "Casa", HOME, "secret")
        val id = repo.libraries.value.last().id
        repo.addServerUrl(id, AWAY)
        repo.selectLibrary(id)
        assertEquals(HOME, repo.currentConnection()!!.serverUrl)
        val captured = SourceConfiguration(repo, repo.source(id))

        reachable -= HOME
        repo.refreshEndpoints()
        assertEquals(AWAY, repo.currentConnection()!!.serverUrl)
        assertEquals(mapOf(HOME to false, AWAY to true), repo.endpointProbes.value[id])
        assertEquals(HOME, captured.currentConnection().serverUrl)
        assertEquals(AWAY, EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api).source(id).serverUrl)

        reachable.clear()
        repo.refreshEndpoints()
        assertEquals("Without an answer, the last one that worked stays", AWAY, repo.currentConnection()!!.serverUrl)

        reachable += listOf(HOME, AWAY)
        repo.refreshEndpoints()
        assertEquals(HOME, repo.currentConnection()!!.serverUrl)
        assertEquals(mapOf(HOME to true), repo.endpointProbes.value[id])

        repo.moveServerUrl(id, AWAY, -1)
        repo.refreshEndpoints()
        assertEquals(listOf(AWAY, HOME), repo.source(id).serverUrls)
        assertEquals(AWAY, repo.currentConnection()!!.serverUrl)
    }

    @Test fun repeatedConnectionFailuresDoNotKeepProbing() = runBlocking {
        val triggers = MutableSharedFlow<EndpointTrigger>(extraBufferCapacity = 8)
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs()), api, endpointTriggers = triggers, clock = { 100_000L })
        repo.saveLibrary(null, "Casa", HOME, "secret")
        repo.addServerUrl(repo.libraries.value.last().id, AWAY)
        delay(200)
        pings.set(0)
        triggers.emit(EndpointTrigger.CONNECTION_FAILED)
        delay(900)
        val afterFirst = pings.get()
        assertTrue(afterFirst > 0)
        triggers.emit(EndpointTrigger.CONNECTION_FAILED)
        delay(900)
        assertEquals(afterFirst, pings.get())
        triggers.emit(EndpointTrigger.NETWORK_CHANGED)
        delay(900)
        assertTrue(pings.get() > afterFirst)
    }

    private companion object {
        const val HOME = "http://192.168.1.10:2283"
        const val AWAY = "https://photos.example.test"
        const val STRANGER = "https://someone-else.test"
        const val LOCKED = "https://locked.test"
    }
}
