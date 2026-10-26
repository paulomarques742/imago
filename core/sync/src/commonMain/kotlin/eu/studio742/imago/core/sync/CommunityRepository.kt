package eu.studio742.imago.core.sync

import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.model.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Local ownership is independent of the account, so signing out does not lose guest posts. */
class CommunityRepository(private val backend: ForumBackend, private val preferences: SecurePreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val localPosts = MutableStateFlow(readPosts())
    val posts = localPosts.asStateFlow()
    val websiteUrl get() = backend.websiteUrl
    var storageFailed = false
        private set
    val localSecrets: List<String> get() = localPosts.value.map { it.token }

    suspend fun profile(): ForumProfile = observed {
        backend.profile().let { profile ->
            if (profile.signedIn) profile else profile.copy(name = preferences.getString(NAME))
        }
    }

    suspend fun publish(draft: ForumDraft, images: List<ForumImage>): ForumPost = observed {
        val post = backend.publish(draft, images)
        localPosts.value = listOf(post) + localPosts.value
        save(draft.name.trim())
        post
    }

    suspend fun delete(post: ForumPost) = observed {
        backend.delete(post)
        localPosts.value = localPosts.value.filterNot { it.id == post.id }
        save()
    }

    private suspend fun save(name: String? = null) = withContext(io) {
        try {
            val encoded = JsonArray(localPosts.value.map { post -> buildJsonObject {
                put("id", post.id); put("ownershipToken", post.token); put("title", post.title)
            } }).toString()
            val changes = mutableMapOf<String, String?>(POSTS to encoded)
            if (name != null) changes[NAME] = name
            storageFailed = !preferences.write(changes)
        } catch (_: Exception) { storageFailed = true }
        // A failed local save must never turn a successful publication into an invitation to retry.
    }

    private fun readPosts(): List<ForumPost> = runCatching {
        Json.parseToJsonElement(preferences.getString(POSTS) ?: "[]").jsonArray.map { value ->
            val fields = value.jsonObject
            ForumPost(fields.getValue("id").jsonPrimitive.content, fields.getValue("ownershipToken").jsonPrimitive.content,
                fields.getValue("title").jsonPrimitive.content)
        }
    }.getOrDefault(emptyList())

    private suspend fun <T> observed(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) {
        LocalDiagnostics.record { DiagnosticEvent(DiagnosticArea.COMMUNITY,
            if (error is ForumException && error.status != null) DiagnosticOutcome.HTTP_ERROR else diagnosticOutcome(error),
            status = (error as? ForumException)?.status, failure = DiagnosticFailure.from(error)) }
        throw error
    }

    private companion object {
        const val POSTS = "community_posts"
        const val NAME = "community_name"
    }
}
