package eu.studio742.imago.core.sync

import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import eu.studio742.imago.core.data.AccountLocalData
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.db.*
import eu.studio742.imago.core.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AccountRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var database: ImmichRoomDatabase
    private val backend = FakeBackend()
    private val configuration = FakeConfiguration(
        listOf(
            LibrarySource(DEVICE_LIBRARY_ID, "Dispositivo"),
            LibrarySource("home", "Casa", listOf("http://192.168.1.10"), userId = "immich-user", apiKey = "secret"),
            LibrarySource("legacy", "Antiga", listOf("https://old.test"), userId = null, apiKey = "secret"),
            LibrarySource("removed", "Removida", listOf("https://gone.test"), userId = "other", apiKey = null),
        ),
    )
    private lateinit var account: AccountRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ImmichRoomDatabase::class.java).allowMainThreadQueries().build()
        account = repository()
    }

    @After fun tearDown() = database.close()

    private fun repository() = AccountRepository(
        backend, configuration, database, AccountLocalData(database),
        eu.studio742.imago.core.data.SharedPreferencesStore(context.getSharedPreferences("sync-device-test", 0)), "Pixel 8",
        platformName = "android",
    )

    @Test fun enteringRegistersThisDeviceOnceAndLinksEveryLibraryWithAnAccountIdentity() = runBlocking {
        account.signIn("  ana@example.test ", "password")
        assertEquals("ana@example.test", backend.lastEmail)
        assertEquals(listOf("Pixel 8"), backend.registered)
        val deviceId = account.currentDeviceId!!

        val linked = backend.linked.associateBy { it.first }
        assertEquals(setOf("device", "immich"), linked.keys)
        assertEquals(LibraryFingerprint.device(deviceId), linked.getValue("device").second)
        assertEquals(LibraryFingerprint.immich("user-a", "immich-user"), linked.getValue("immich").second)
        assertEquals(setOf(DEVICE_LIBRARY_ID, "home"), database.libraryLinkDao().all().map { it.localKey }.toSet())

        account.signIn("ana@example.test", "password")
        assertEquals("The same device in the same account is not registered again", 1, backend.registered.size)
        assertEquals(2, backend.linked.size)
    }

    @Test fun fingerprintNeverCarriesTheImmichIdAndDiffersBetweenAccounts() {
        val a = LibraryFingerprint.immich("user-a", "immich-user")
        assertEquals(a, LibraryFingerprint.immich("user-a", "immich-user"))
        assertNotEquals(a, LibraryFingerprint.immich("user-b", "immich-user"))
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
        assertFalse(a.contains("immich-user"))
    }

    @Test fun aRemovedDeviceRegistersAgainAndOnlyTheDeviceLibraryIsRelinked() = runBlocking {
        account.signIn("ana@example.test", "password")
        val first = account.currentDeviceId!!
        backend.devices.remove(first)
        backend.linked.clear()

        account.retryLink()
        kotlinx.coroutines.withTimeout(2_000) { while (account.currentDeviceId == first || backend.linked.isEmpty()) kotlinx.coroutines.delay(10) }
        assertNotEquals(first, account.currentDeviceId)
        assertEquals(listOf("device"), backend.linked.map { it.first })
    }

    @Test fun anotherAccountWithoutSigningOutTurnsTheOldDataIntoLocalData() = runBlocking {
        account.signIn("ana@example.test", "password")
        database.savedRecipeDao().upsert(preset("p", SyncState(remoteRevision = 3, editedAt = "x")))

        backend.nextUser = "user-b"
        account.signIn("rui@example.test", "password")
        assertNull(database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "p")!!.sync.remoteRevision)
        assertEquals(2, backend.registered.size)
        assertEquals(LibraryFingerprint.immich("user-b", "immich-user"), backend.linked.last { it.first == "immich" }.second)
    }

    @Test fun signingOutKeepingACopyLeavesLocalDataWithoutTheAccount() = runBlocking {
        account.signIn("ana@example.test", "password")
        database.savedRecipeDao().upsert(preset("kept", SyncState(remoteRevision = 3, editedAt = "x", editedByDevice = "d")))
        database.savedRecipeDao().upsert(preset("gone", SyncState(remoteRevision = 4, editedAt = "x", deletedAt = "y", dirty = true)))

        account.signOut(keepCopy = true)
        assertNull(backend.session.value)
        assertEquals(SyncState(editedAt = "x"), database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "kept")!!.sync)
        assertNull(database.savedRecipeDao().getAny(GLOBAL_LIBRARY_ID, "gone"))
        assertTrue(database.libraryLinkDao().all().isEmpty())
        assertEquals(4, configuration.libraries.value.size)
    }

    @Test fun signingOutRemovingTheDataEmptiesEverythingThatSyncs() = runBlocking {
        account.signIn("ana@example.test", "password")
        database.assetDao().upsertAll(listOf(AssetEntity("home", "photo", "", "a.jpg", "2026", "", null, null, false, false, true, "IMAGE")))
        database.recipeDao().upsert(RecipeEntity("home", "photo", "{}", "x"))
        database.savedRecipeDao().upsert(preset("p", SyncState()))

        account.signOut(keepCopy = false)
        assertTrue(database.recipeDao().list("home").isEmpty())
        assertTrue(database.savedRecipeDao().list(GLOBAL_LIBRARY_ID).isEmpty())
        assertFalse(database.assetDao().asset("home", "photo")!!.hasLocalRecipe)
        assertEquals("Libraries and keys never leave by signing out", "secret", configuration.libraries.value[1].apiKey)
    }

    @Test fun thisDeviceIsRemovedBySigningOutAndTheRecoveryLinkAsksForAPassword() = runBlocking {
        account.signIn("ana@example.test", "password")
        assertTrue(runCatching { account.removeDevice(account.currentDeviceId!!) }.isFailure)

        assertFalse(account.handleAuthLink("https://example.test"))
        assertTrue(account.handleAuthLink("imago://auth/recovery?code=1"))
        assertTrue(account.passwordRecoveryPending.value)
        account.updatePassword("nova-password")
        assertFalse(account.passwordRecoveryPending.value)
        assertEquals("nova-password", backend.password)
    }

    private fun preset(id: String, sync: SyncState) =
        SavedRecipeEntity(GLOBAL_LIBRARY_ID, id, id, "C", "{}", "x", "x", sync = sync)

    private class FakeBackend : SyncBackend {
        override val isAvailable = true
        override val session = MutableStateFlow<AccountSession?>(null)
        var nextUser = "user-a"
        var lastEmail: String? = null
        var password: String? = null
        val registered = mutableListOf<String>()
        val devices = mutableMapOf<String, String>()
        val linked = mutableListOf<Pair<String, String>>()

        override suspend fun signUp(email: String, password: String) = SignUpResult.CONFIRMATION_SENT
        override suspend fun signIn(email: String, password: String) {
            lastEmail = email
            session.value = AccountSession(nextUser, email)
        }
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun updatePassword(password: String) { this.password = password }
        override suspend fun signOut() { session.value = null }
        override fun handleAuthLink(uri: String) = when {
            uri.startsWith("imago://auth/recovery") -> AuthLink.RECOVERY
            uri.startsWith("imago://auth/") -> AuthLink.CONFIRMATION
            else -> null
        }
        override suspend fun registerDevice(name: String, platform: String): String {
            registered += name
            return "device-${registered.size}".also { devices[it] = name }
        }
        override suspend fun touchDevice(deviceId: String, name: String?) = deviceId in devices
        override suspend fun devices() = devices.map { (id, name) -> RemoteDevice(id, name, "android", "", "") }
        override suspend fun removeDevice(deviceId: String) { devices.remove(deviceId) }
        override suspend fun linkLibrary(provider: String, fingerprint: String, displayName: String, deviceId: String?): String {
            linked += provider to fingerprint
            return "remote-${linked.size}"
        }
        override suspend fun push(entity: SyncEntity, changes: List<OutgoingChange>) = error("unused")
        override suspend fun pull(entity: SyncEntity, cursor: Long, limit: Int) = error("unused")
        override suspend fun recordConflict(entity: SyncEntity, key: String, revision: Long, payload: kotlinx.serialization.json.JsonObject, editedAt: String, deviceId: String?) = error("unused")
        override fun changes(): Flow<SyncEntity> = emptyFlow()
    }

    private class FakeConfiguration(sources: List<LibrarySource>) : ConfigurationRepository {
        override val libraries = MutableStateFlow(sources)
        override val selectedLibraryId = MutableStateFlow(DEVICE_LIBRARY_ID)
        override val endpointProbes = MutableStateFlow<Map<String, Map<String, Boolean>>>(emptyMap())
        override val connection = MutableStateFlow<ImmichConnection?>(null)
        override var lastExportLibraryId: String? = null
        override val welcomeCompleted = MutableStateFlow(true)
        override fun completeWelcome() = Unit
        override var lastSeenAppVersion: String? = null
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
