package eu.studio742.imago.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The account logic, the same on Android and desktop, with an in-memory platform. */
class AccountCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = FakeBackend()
    private val platform = MemoryPlatform()
    private val libraries = MutableStateFlow(listOf(
        AccountLibrary(AccountCoordinator.DEVICE_LIBRARY_KEY, "Este computador", AccountLibrary.Kind.DEVICE),
        AccountLibrary("home", "Casa", AccountLibrary.Kind.IMMICH, accountUserId = "immich-user"),
        AccountLibrary("legacy", "Antiga", AccountLibrary.Kind.IMMICH, accountUserId = null),
    ))
    private val account = AccountCoordinator(backend, platform, libraries, "Pixel 8", "android", scope)

    @After fun tearDown() = scope.cancel()

    private suspend fun linked() = withTimeout(2_000) { account.linkState.first { it is AccountLinkState.Linked } } as AccountLinkState.Linked

    @Test fun enteringRegistersThisDeviceOnceAndLinksEveryLibraryWithAnIdentity() = runBlocking {
        account.signIn("  ana@example.test ", "password")
        assertEquals("ana@example.test", backend.lastEmail)
        val state = linked()
        assertEquals(listOf("Antiga"), state.withoutIdentity)
        assertEquals(listOf("Pixel 8" to "android"), backend.registered)
        val deviceId = account.currentDeviceId!!

        val linked = backend.linked.associate { it.first to it.second }
        assertEquals(LibraryFingerprint.device(deviceId), linked.getValue("device"))
        assertEquals(LibraryFingerprint.immich("user-a", "immich-user"), linked.getValue("immich"))
        assertEquals(setOf("device", "home"), platform.links.keys)

        account.signIn("ana@example.test", "password")
        assertEquals("The same device in the same account is not registered again", 1, backend.registered.size)
        assertEquals(2, backend.linked.size)
    }

    @Test fun aLibraryAddedAfterEnteringIsLinkedAndOneFailureDoesNotStopTheOthers() = runBlocking {
        backend.failProvider = "immich"
        account.signIn("ana@example.test", "password")
        val failed = withTimeout(2_000) { account.linkState.first { it is AccountLinkState.Failed } } as AccountLinkState.Failed
        assertTrue("Casa" in failed.libraries)
        assertTrue("The device library is linked anyway", "device" in platform.links)

        backend.failProvider = null
        libraries.value = libraries.value + AccountLibrary("office", "Office", AccountLibrary.Kind.IMMICH, "other-user")
        linked()
        assertEquals(setOf("device", "home", "office"), platform.links.keys)
    }

    @Test fun exportingWritesOneJsonPerEntityFromTheServer() = runBlocking {
        account.signIn("ana@example.test", "password")
        linked()
        backend.stored[SyncEntity.SAVED_RECIPE] = List(501) { index ->
            IncomingChange("preset-$index", 1, index + 1L, kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("Preset $index"))
            }, "2026-09-16T10:00:00Z", "device-1", null)
        }

        val zip = account.exportData(now = "2026-09-17T09:00:00Z")

        val entries = java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            generateSequence { input.nextEntry }.associate { it.name to input.readBytes().toString(Charsets.UTF_8) }
        }
        val expected = setOf("conta.json", "aparelhos.json", "bibliotecas.json") + SyncEntity.entries.map { "${it.wireName.lowercase()}.json" }
        assertEquals(expected, entries.keys)
        assertTrue(entries.getValue("conta.json").contains("ana@example.test"))
        assertTrue("the server's pages are followed to the end", entries.getValue("saved_recipe.json").contains("Preset 500"))
    }

    @Test fun deletingTheAccountAsksForThePasswordAndKeepsTheDataHere() = runBlocking {
        account.signIn("ana@example.test", "password")
        linked()
        backend.lastEmail = null

        account.deleteAccount("password")

        assertEquals("The password is checked again before deleting", "ana@example.test", backend.lastEmail)
        assertEquals(1, backend.deleted)
        assertNull(account.session.value)
        assertEquals("It ends as with \"Sign out · Keep a copy\"", 1, platform.detached)
        assertEquals(0, platform.removed)
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
        linked()
        val first = account.currentDeviceId!!
        backend.devices.remove(first)
        backend.linked.clear()

        account.retryLink()
        withTimeout(2_000) { while (account.currentDeviceId == first) kotlinx.coroutines.delay(10) }
        linked()
        assertEquals(listOf("device"), backend.linked.map { it.first })
        assertEquals(0, platform.detached)
    }

    @Test fun anotherAccountWithoutSigningOutTurnsTheOldDataIntoLocalData() = runBlocking {
        account.signIn("ana@example.test", "password")
        linked()
        backend.nextUser = "user-b"
        account.signIn("rui@example.test", "password")
        assertEquals(1, platform.detached)
        assertEquals(2, backend.registered.size)
    }

    @Test fun signingOutChoosesWhatHappensToTheDataAndRecoveryAsksForAPassword() = runBlocking {
        account.signIn("ana@example.test", "password")
        linked()
        assertTrue(runCatching { account.removeDevice(account.currentDeviceId!!) }.isFailure)

        account.signOut(keepCopy = true)
        assertNull(backend.session.value)
        assertEquals(1, platform.detached)
        account.signIn("ana@example.test", "password")
        account.signOut(keepCopy = false)
        assertEquals(1, platform.removed)

        assertFalse(account.handleAuthLink("https://example.test"))
        assertTrue(account.handleAuthLink("imago://auth/recovery?code=1"))
        assertTrue(account.passwordRecoveryPending.value)
        account.updatePassword("nova-password")
        assertFalse(account.passwordRecoveryPending.value)
    }

    @Test fun renamingKeepsTheNameForTheNextRegistration() = runBlocking {
        account.renameDevice("  Studio laptop ")
        assertEquals("Studio laptop", platform.name)
        account.signIn("ana@example.test", "password")
        linked()
        assertEquals("Studio laptop", backend.registered.single().first)
    }

    private class MemoryPlatform : AccountPlatform {
        var device: StoredDevice? = null
        var name: String? = null
        val links = mutableMapOf<String, String>()
        var detached = 0
        var removed = 0
        override fun storedDevice() = device
        override fun storeDevice(device: StoredDevice) { this.device = device }
        override fun storedDeviceName() = name
        override fun storeDeviceName(name: String) { this.name = name }
        override suspend fun libraryLink(localKey: String) = links[localKey]
        override suspend fun saveLibraryLink(localKey: String, remoteLibraryId: String) { links[localKey] = remoteLibraryId }
        override suspend fun forgetLibraryLink(localKey: String) { links.remove(localKey) }
        override suspend fun detachFromAccount() { detached++; links.clear() }
        override suspend fun removeAccountData() { removed++; links.clear() }
        override fun log(message: String) = Unit
    }

    private class FakeBackend : SyncBackend {
        override val isAvailable = true
        override val session = MutableStateFlow<AccountSession?>(null)
        @Volatile var nextUser = "user-a"
        @Volatile var failProvider: String? = null
        var lastEmail: String? = null
        val registered = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val devices = java.util.concurrent.ConcurrentHashMap<String, String>()
        val linked = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val stored = java.util.concurrent.ConcurrentHashMap<SyncEntity, List<IncomingChange>>()
        @Volatile var deleted = 0

        override suspend fun signUp(email: String, password: String) = SignUpResult.CONFIRMATION_SENT
        override suspend fun signIn(email: String, password: String) {
            lastEmail = email
            session.value = AccountSession(nextUser, email)
        }
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun updatePassword(password: String) = Unit
        override suspend fun signOut() { session.value = null }
        override fun handleAuthLink(uri: String) = when {
            uri.startsWith("imago://auth/recovery") -> AuthLink.RECOVERY
            uri.startsWith("imago://auth/") -> AuthLink.CONFIRMATION
            else -> null
        }
        override suspend fun registerDevice(name: String, platform: String): String {
            registered += name to platform
            return "device-${registered.size}".also { devices[it] = name }
        }
        override suspend fun touchDevice(deviceId: String, name: String?) = devices.containsKey(deviceId)
        override suspend fun devices() = devices.map { (id, name) -> RemoteDevice(id, name, "android", "", "") }
        override suspend fun removeDevice(deviceId: String) { devices.remove(deviceId) }
        override suspend fun linkLibrary(provider: String, fingerprint: String, displayName: String, deviceId: String?): String {
            if (provider == failProvider) error("offline")
            linked += provider to fingerprint
            return "remote-${linked.size}"
        }
        override suspend fun push(entity: SyncEntity, changes: List<OutgoingChange>) = error("unused")
        override suspend fun pull(entity: SyncEntity, cursor: Long, limit: Int): PullPage {
            val page = stored[entity].orEmpty().filter { it.seq > cursor }.take(limit)
            return PullPage(page, page.lastOrNull()?.seq ?: cursor)
        }
        override suspend fun deleteAccount() { deleted++ }
        override suspend fun recordConflict(entity: SyncEntity, key: String, revision: Long, payload: kotlinx.serialization.json.JsonObject, editedAt: String, deviceId: String?) = error("unused")
        override fun changes(): Flow<SyncEntity> = emptyFlow()
    }
}
