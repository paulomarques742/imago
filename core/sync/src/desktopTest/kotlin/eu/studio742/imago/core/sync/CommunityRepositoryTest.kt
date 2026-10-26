package eu.studio742.imago.core.sync

import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.model.diagnostics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import eu.studio742.imago.core.data.ContentHashRepository
import eu.studio742.imago.core.data.MediaResolver
import eu.studio742.imago.core.data.ReferenceTranslator
import eu.studio742.imago.core.data.db.ImmichRoomDatabase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CommunityRepositoryTest {
    private class Preferences : SecurePreferences {
        val data = mutableMapOf<String, String>()
        var writable = true
        override fun getString(key: String) = data[key]
        override fun write(changes: Map<String, String?>): Boolean {
            if (!writable) return false
            changes.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
            return true
        }
    }
    private class Backend : ForumBackend {
        var profile = ForumProfile(false, null)
        var failure: Exception? = null
        var published = 0
        override suspend fun profile() = profile
        override suspend fun publish(draft: ForumDraft, images: List<ForumImage>): ForumPost {
            failure?.let { throw it }; published++
            return ForumPost("post-id", "private-ownership-key", draft.title)
        }
        override suspend fun delete(post: ForumPost) { failure?.let { throw it } }
    }
    private val draft = ForumDraft(ForumCategory.IDEA, "An idea", "Message", "Guest")

    @Test fun ownershipAndGuestNameSurviveRestartAndAreRemovedOnlyAfterSuccessfulDeletion() = runBlocking {
        val preferences = Preferences(); val backend = Backend()
        val repository = CommunityRepository(backend, preferences)
        repository.publish(draft, emptyList())
        val restarted = CommunityRepository(backend, preferences)
        assertEquals("Guest", restarted.profile().name)
        assertEquals("private-ownership-key", restarted.posts.value.single().token)
        backend.failure = ForumException(ForumException.Reason.HAS_REPLIES)
        assertTrue(runCatching { restarted.delete(restarted.posts.value.single()) }.isFailure)
        assertEquals(1, restarted.posts.value.size)
        backend.failure = null
        restarted.delete(restarted.posts.value.single())
        assertTrue(CommunityRepository(backend, preferences).posts.value.isEmpty())
    }

    @Test fun accountWithoutAPublicNameNeverInheritsTheGuestNameOrEmail() = runBlocking {
        val backend = Backend(); val repository = CommunityRepository(backend, Preferences())
        repository.publish(draft, emptyList())
        backend.profile = ForumProfile(true, null)
        assertNull(repository.profile().name)
        backend.profile = ForumProfile(true, "Account public name")
        assertEquals("Account public name", repository.profile().name)
    }

    @Test fun failedLocalStorageNeverTurnsASuccessfulPublicationIntoARetry() = runBlocking {
        val preferences = Preferences().apply { writable = false }; val backend = Backend()
        val repository = CommunityRepository(backend, preferences)
        repository.publish(draft, emptyList())
        assertEquals(1, backend.published)
        assertEquals(1, repository.posts.value.size)
        assertTrue(repository.storageFailed)
    }

    @Test fun diagnosticsNeverContainPostContentNameImagesOrOwnershipTokens() = runBlocking {
        val directory = Files.createTempDirectory("community-diagnostics").toFile()
        val store = DiagnosticStore(directory)
        LocalDiagnostics.start(store, emptyMap())
        try {
            val backend = Backend(); val repository = CommunityRepository(backend, Preferences())
            repository.publish(draft, emptyList())
            backend.failure = IllegalStateException("Secret content Guest private-ownership-key https://private.invalid")
            runCatching { repository.publish(draft, emptyList()) }
            val report = store.report(emptyMap(), "private-ownership-key", DiagnosticAccess.UNKNOWN, repository.localSecrets).readText()
            assertTrue(report.contains("COMMUNITY"))
            listOf("Guest", "An idea", "Secret content", "private-ownership-key", "private.invalid").forEach { assertFalse(report.contains(it)) }
        } finally { LocalDiagnostics.stop(); directory.deleteRecursively() }
    }

    @Test fun communityOwnershipAndLocalNamesCannotEnterAccountSyncPayloads() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder<ImmichRoomDatabase>().setDriver(BundledSQLiteDriver()).build()
        try {
            val hashes = ContentHashRepository(database, { error("No media expected") }, { error("No server expected") })
            val translator = ReferenceTranslator(database, MediaResolver(database, hashes))
            for (field in listOf("ownershipToken", "community_posts", "community_name")) {
                val payload = buildJsonObject { put("nested", buildJsonObject { put(field, "private-ownership-key") }) }
                assertTrue(runCatching { translator.outgoing(payload) }.exceptionOrNull() is SecurityException)
            }
        } finally { database.close() }
    }
}
