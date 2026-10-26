package eu.studio742.imago.feature.library

import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityComposerTest {
    @Test fun nothingPublishesUntilTheUserReviewsAValidDraftAndExplicitlyPublishes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val backend = object : ForumBackend {
                override suspend fun profile() = ForumProfile(true, "Public name")
                override suspend fun publish(draft: ForumDraft, images: List<ForumImage>): ForumPost {
                    calls++; return ForumPost("post", "owner", draft.title)
                }
                override suspend fun delete(post: ForumPost) = Unit
            }
            val preferences = object : SecurePreferences {
                override fun getString(key: String): String? = null
                override fun write(changes: Map<String, String?>) = true
            }
            val composer = CommunityComposer(CommunityRepository(backend, preferences, StandardTestDispatcher(testScheduler)))
            composer.loadProfile(); advanceUntilIdle()
            assertEquals("Public name", composer.draft.value.name)
            composer.publish(); advanceUntilIdle(); assertEquals(0, calls)
            composer.draft.value = composer.draft.value.copy(title = "A question", body = "Message")
            composer.attach(listOf(ForumImage(byteArrayOf(1))))
            composer.publish(); advanceUntilIdle(); assertEquals(0, calls)
            composer.reviewing.value = true
            assertEquals(0, calls)
            composer.preparingImages.value = true
            composer.publish(); advanceUntilIdle(); assertEquals(0, calls)
            composer.preparingImages.value = false
            composer.publish(); composer.publish(); advanceUntilIdle()
            assertEquals(1, calls)
            assertNotNull(composer.created.value)
            composer.publish(); advanceUntilIdle(); assertEquals(1, calls)
            composer.reset()
            assertTrue(composer.images.value.isEmpty())
            assertFalse(composer.profileLoaded.value)
        } finally { Dispatchers.resetMain() }
    }
}
