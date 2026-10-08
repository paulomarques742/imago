package eu.studio742.imago.core.data

import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import eu.studio742.imago.core.immich.*
import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions as Permissions
import eu.studio742.imago.core.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LibraryConfigurationTest {
    private val api = object : ImmichApi by OkHttpImmichApi(OkHttpClient()) {
        override suspend fun validateConnection(connection: ImmichConnection): ServerVersion {
            check(connection.apiKey != "invalid") { "Invalid key" }; return ServerVersion(2,6,3)
        }
        override suspend fun currentUserId(connection: ImmichConnection) = if (connection.apiKey == "other-account") "user-b" else "user-a"
        override suspend fun keyPermissions(connection: ImmichConnection): Set<String> = when (connection.apiKey) {
            "read-only" -> Permissions.REQUIRED.toSet()
            "no-originals" -> Permissions.REQUIRED.toSet() - Permissions.DOWNLOAD_ASSET - Permissions.VIEW_ASSET
            "unreadable-permissions" -> throw ImmichApiException.Server(500, "")
            else -> setOf(Permissions.ALL)
        }
    }
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences(java.util.UUID.randomUUID().toString(), 0)
    @Test fun migratesSingleConnectionAndKeepsItsExistingDataKey() = runBlocking {
        val prefs = prefs()
        prefs.edit().putString("server_url", "https://example.test/api").putString("api_key", "old-secret").commit()
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        assertEquals(libraryKeyOf("https://example.test/api"), repo.selectedLibraryId.value)
        assertEquals("old-secret", repo.currentConnection()!!.apiKey)
        assertFalse(prefs.contains("api_key"))
        // A migrated library learns whose key it holds in the background. Reopening before that is
        // saved would race it, so wait for the saved list, not the in-memory one, which is updated
        // first; this also checks that the learned identity survives a restart.
        withTimeout(5_000) {
            while (prefs.getString("libraries_v2", null)?.contains("\"user-a\"") != true) delay(10)
        }
        val reopened = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        assertEquals(repo.libraries.value, reopened.libraries.value)
    }
    /** Names older versions saved in Portuguese for libraries the app named go; names the person gave stay. */
    @Test fun legacyAppNamesAreDroppedButNamesThePersonGaveStay() {
        val prefs = prefs()
        val saved = listOf(
            LibrarySource(DEVICE_LIBRARY_ID, "Dispositivo"),
            LibrarySource("old", "Biblioteca anterior · old12345"),
            LibrarySource("home", "Biblioteca anterior · casa", listOf("https://example.test/api"), apiKey = "secret"),
        )
        prefs.edit().putString("libraries_v2", Json.encodeToString(saved)).commit()
        val names = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api).libraries.value.associate { it.id to it.name }
        assertEquals(mapOf(DEVICE_LIBRARY_ID to "", "old" to "", "home" to "Biblioteca anterior · casa"), names)
    }
    @Test fun aNewInstallShowsTheWelcomeUntilItIsCompleted() {
        val prefs = prefs()
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        assertFalse(repo.welcomeCompleted.value)
        // The first launch already saved the library list; closing the app without choosing must
        // not pass for an old install.
        assertFalse(EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api).welcomeCompleted.value)
        repo.completeWelcome()
        assertTrue(repo.welcomeCompleted.value)
        assertTrue(EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api).welcomeCompleted.value)
    }
    @Test fun anInstallFromBeforeTheWelcomeSkipsIt() {
        val withLibraries = prefs()
        EncryptedConfigurationRepository(SharedPreferencesStore(withLibraries), api)
        withLibraries.edit().remove("welcome_completed").commit()
        assertTrue(EncryptedConfigurationRepository(SharedPreferencesStore(withLibraries), api).welcomeCompleted.value)

        val withLegacyKey = prefs()
        withLegacyKey.edit().putString("server_url", "https://example.test").putString("api_key", "old-secret").commit()
        assertTrue(EncryptedConfigurationRepository(SharedPreferencesStore(withLegacyKey), api).welcomeCompleted.value)
    }
    /**
     * Only the read permissions are required: a key without the write ones connects, and says which
     * it lacks. One without a required permission is refused naming all it lacks, not as a key Immich
     * rejected.
     */
    @Test fun aKeyNeedsOnlyTheRequiredPermissions() = runBlocking {
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs()), api)

        repo.saveLibrary(null, "Home", "https://example.test", "read-only")
        assertEquals(
            listOf(
                "album.read", "asset.upload", "stack.create", "asset.update", "asset.delete",
                "album.create", "albumAsset.create", "albumAsset.delete", "album.update", "album.delete",
                "asset.edit.get", "asset.edit.create", "asset.edit.delete", "person.read",
            ),
            repo.missingOptionalPermissions(ImmichConnection("https://example.test", "read-only")),
        )
        assertEquals(emptyList<String>(), repo.missingOptionalPermissions(ImmichConnection("https://example.test", "everything")))

        val refused = runCatching { repo.saveLibrary(null, "Other", "https://other.test", "no-originals") }.exceptionOrNull()
        assertEquals(listOf("asset.view", "asset.download"), (refused as ImmichApiException.MissingPermission).permissions)
        assertEquals(1, repo.libraries.value.count { !it.isDevice })

        // Permissions that cannot be read are no reason to refuse: the endpoints still say what is missing.
        repo.saveLibrary(null, "Unknown", "https://unknown.test", "unreadable-permissions")
        assertEquals(emptyList<String>(), repo.missingOptionalPermissions(ImmichConnection("https://unknown.test", "unreadable-permissions")))
    }
    @Test fun legacyAccountCannotBeReplacedByAnotherUsersCredential() = runBlocking {
        val prefs = prefs()
        prefs.edit().putString("server_url", "https://example.test").putString("api_key", "old-secret").commit()
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        val original = repo.selectedLibraryId.value
        assertTrue(runCatching { repo.saveLibrary(original, "Changed", "https://example.test", "other-account") }.isFailure)
        assertEquals("old-secret", repo.currentConnection()!!.apiKey)
        repo.saveLibrary(null, "Other", "https://example.test", "other-account")
        assertNotEquals(original, repo.libraries.value.last().id)
        assertEquals("user-a", repo.source(original).userId)
    }
    @Test fun multipleAccountsReconnectAndValidationFailureAreIsolated() = runBlocking {
        val prefs = prefs()
        val repo = EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api)
        assertEquals(DEVICE_LIBRARY_ID, repo.selectedLibraryId.value)
        repo.saveLibrary(null, "A", "https://example.test/api/", "secret")
        val first = repo.libraries.value.last().id
        repo.saveLibrary(null, "A renamed", "https://example.test", "new-secret")
        assertEquals(2, repo.libraries.value.size)
        repo.selectLibrary(first)
        val captured = SourceConfiguration(repo, repo.source(first))
        repo.saveLibrary(null, "B", "https://example.test", "other-account")
        val second = repo.libraries.value.last().id
        assertNotEquals(first, second)
        repo.selectLibrary(second)
        assertEquals(first, captured.currentConnection().libraryId)
        assertEquals("new-secret", captured.currentConnection().apiKey)
        val before = repo.libraries.value
        assertTrue(runCatching { repo.saveLibrary(first, "Broken", "https://example.test", "invalid") }.isFailure)
        assertEquals(before, repo.libraries.value)
        repo.removeLibrary(first)
        assertEquals(second, repo.selectedLibraryId.value)
        repo.removeLibrary(second)
        assertEquals(DEVICE_LIBRARY_ID, repo.selectedLibraryId.value)
        assertNull(repo.source(first).apiKey)
        repo.saveLibrary(null, "Reconnected", "https://example.test", "secret")
        assertEquals(first, repo.libraries.value.last().id)
        repo.selectLibrary(first)
        assertEquals(first, EncryptedConfigurationRepository(SharedPreferencesStore(prefs), api).selectedLibraryId.value)
    }
}
