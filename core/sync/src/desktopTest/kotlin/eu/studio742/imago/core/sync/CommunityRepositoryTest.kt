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
        var activity = emptyList<ForumActivity>()
        var activityCalls = 0
        override suspend fun activity(topicIds: List<String>): List<ForumActivity> {
            activityCalls++
            failure?.let { throw it }
            return activity
        }
        override suspend fun profile() = profile
        override suspend fun publish(draft: ForumDraft, images: List<ForumImage>): ForumPost {
            failure?.let { throw it }; published++
            return ForumPost("post-id", "private-ownership-key", draft.title)
        }
        override suspend fun delete(post: ForumPost) { failure?.let { throw it } }
    }
    private val draft = ForumDraft(ForumCategory.IDEA, "An idea", "Message", "Guest")
    private val firstReply = ForumActivity("post-id", 1, "2026-10-28T10:00:00Z")
    private val secondReply = ForumActivity("post-id", 2, "2026-10-28T11:00:00Z")

    @Test fun noTopicsMeansNoBackgroundForumRequests() = runBlocking {
        val backend = Backend()
        assertTrue(CommunityRepository(backend, Preferences()).refreshReplies())
        assertEquals(0, backend.activityCalls)
    }

    @Test fun guestRepliesStayUnreadAcrossRestartsUntilTheTopicIsOpened() = runBlocking {
        val preferences = Preferences(); val backend = Backend()
        val repository = CommunityRepository(backend, preferences)
        repository.publish(draft, emptyList())
        backend.activity = listOf(firstReply)
        assertTrue(repository.refreshReplies())
        assertTrue(repository.replies.value.getValue("post-id").unread)
        val restarted = CommunityRepository(backend, preferences)
        assertTrue(restarted.replies.value.getValue("post-id").unread)
        assertTrue(restarted.refreshReplies())
        assertTrue(restarted.replies.value.getValue("post-id").unread)
        restarted.markRead("post-id", firstReply.lastActivity)
        assertFalse(CommunityRepository(backend, preferences).replies.value.getValue("post-id").unread)
        backend.activity = listOf(secondReply)
        restarted.refreshReplies()
        assertTrue(restarted.replies.value.getValue("post-id").unread)
    }

    @Test fun openingAnOlderReplySnapshotNeverMarksLaterRepliesReadOrMovesTheReadCursorBackwards() = runBlocking {
        val backend = Backend(); val repository = CommunityRepository(backend, Preferences())
        repository.publish(draft, emptyList())
        backend.activity = listOf(secondReply)
        repository.refreshReplies()
        repository.markRead("post-id", firstReply.lastActivity)
        assertTrue(repository.replies.value.getValue("post-id").unread)
        repository.markRead("post-id", "2099-01-01T00:00:00Z")
        repository.markRead("post-id", "invalid")
        assertTrue(repository.replies.value.getValue("post-id").unread)
        repository.markRead("post-id", secondReply.lastActivity)
        repository.markRead("post-id", firstReply.lastActivity)
        assertFalse(repository.replies.value.getValue("post-id").unread)
    }

    @Test fun aNewReplyAfterADeletionIsDetectedEvenWhenTheTotalCountIsUnchanged() = runBlocking {
        val backend = Backend(); val repository = CommunityRepository(backend, Preferences())
        repository.publish(draft, emptyList())
        backend.activity = listOf(firstReply)
        repository.refreshReplies()
        repository.markRead("post-id", firstReply.lastActivity)
        backend.activity = listOf(firstReply.copy(lastActivity = secondReply.lastActivity))
        repository.refreshReplies()
        assertTrue(repository.replies.value.getValue("post-id").unread)
        backend.activity = listOf(secondReply.copy(replyCount = 0))
        repository.refreshReplies()
        assertFalse(repository.replies.value.getValue("post-id").unread)
    }

    @Test fun unavailableTopicsStopNotifyingWithoutLosingTheLocalOwnershipKey() = runBlocking {
        val backend = Backend(); val repository = CommunityRepository(backend, Preferences())
        repository.publish(draft, emptyList())
        backend.activity = listOf(firstReply)
        repository.refreshReplies()
        backend.activity = emptyList()
        assertTrue(repository.refreshReplies())
        assertFalse(repository.replies.value.getValue("post-id").available)
        assertFalse(repository.replies.value.getValue("post-id").unread)
        assertEquals("private-ownership-key", repository.posts.value.single().token)
    }

    @Test fun failedChecksPreserveUnreadRepliesAndExposeAnErrorThatClearsAfterRetry() = runBlocking {
        val backend = Backend(); val repository = CommunityRepository(backend, Preferences())
        repository.publish(draft, emptyList())
        backend.activity = listOf(firstReply)
        repository.refreshReplies()
        backend.failure = ForumException(ForumException.Reason.OFFLINE)
        assertFalse(repository.refreshReplies())
        assertTrue(repository.replyCheckFailed.value)
        assertTrue(repository.replies.value.getValue("post-id").unread)
        backend.failure = null
        assertTrue(repository.refreshReplies())
        assertFalse(repository.replyCheckFailed.value)
    }

    @Test fun topicsSavedBeforeReplyTrackingStillShowNewReplies() = runBlocking {
        val preferences = Preferences().apply { data["community_posts"] =
            """[{"id":"post-id","ownershipToken":"private-ownership-key","title":"Legacy topic"}]""" }
        val backend = Backend().apply { activity = listOf(firstReply) }
        val repository = CommunityRepository(backend, preferences)
        assertTrue(repository.refreshReplies())
        assertTrue(repository.replies.value.getValue("post-id").unread)
        assertEquals("Legacy topic", CommunityRepository(backend, preferences).posts.value.single().title)
    }

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
        assertTrue(repository.storageSaveFailed.value)
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
            assertFalse(repository.refreshReplies())
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
