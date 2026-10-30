package eu.studio742.imago.feature.library

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityUpdatesTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    private class Backend : ForumBackend {
        var checks = 0
        var fails = false
        var replyCount = 0
        override suspend fun profile() = ForumProfile(false, null)
        override suspend fun publish(draft: ForumDraft, images: List<ForumImage>) = ForumPost("own-topic", "local-token", draft.title)
        override suspend fun delete(post: ForumPost) = Unit
        override suspend fun activity(topicIds: List<String>): List<ForumActivity> {
            assertEquals(listOf("own-topic"), topicIds)
            checks++
            if (fails) throw ForumException(ForumException.Reason.OFFLINE)
            return listOf(ForumActivity("own-topic", replyCount, "2026-10-28T10:00:00Z"))
        }
    }

    private fun repository(backend: Backend, dispatcher: TestDispatcher) = CommunityRepository(backend, object : SecurePreferences {
        override fun getString(key: String): String? = null
        override fun write(changes: Map<String, String?>) = true
    }, dispatcher)

    @Test fun checksOnceOnOpeningAndReturningWithNoRequestsWhileOpenOrInTheBackground() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val backend = Backend()
            val repository = repository(backend, dispatcher)
            repository.publish(ForumDraft(ForumCategory.QUESTION, "A question", "Message", "Guest"), emptyList())
            val owner = Owner()
            backgroundScope.launch { observeCommunityReplies(repository, owner.lifecycle) }
            runCurrent()
            assertEquals(0, backend.checks)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(1, backend.checks)
            backend.replyCount = 1
            advanceTimeBy(24 * 60 * 60_000L)
            runCurrent()
            assertEquals(1, backend.checks)
            assertFalse(repository.replies.value.getValue("own-topic").unread)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            advanceTimeBy(24 * 60 * 60_000L)
            runCurrent()
            assertEquals(1, backend.checks)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(2, backend.checks)
            assertTrue(repository.replies.value.getValue("own-topic").unread)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(2, backend.checks)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun aFailedCheckRetriesOnTheNextForegroundEntryWithoutATimer() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val backend = Backend().apply { fails = true }
            val repository = repository(backend, dispatcher)
            repository.publish(ForumDraft(ForumCategory.QUESTION, "A question", "Message", "Guest"), emptyList())
            val owner = Owner()
            backgroundScope.launch { observeCommunityReplies(repository, owner.lifecycle) }
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(1, backend.checks)
            assertTrue(repository.replyCheckFailed.value)
            backend.fails = false
            advanceTimeBy(60 * 60_000L)
            runCurrent()
            assertEquals(1, backend.checks)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(2, backend.checks)
            assertFalse(repository.replyCheckFailed.value)
        } finally { Dispatchers.resetMain() }
    }
}
