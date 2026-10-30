package eu.studio742.imago.core.sync

import eu.studio742.imago.core.data.SecurePreferences
import eu.studio742.imago.core.model.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Instant

data class ForumReplyState(
    val replyCount: Int = 0,
    val activity: String? = null,
    val readActivity: String? = null,
    val available: Boolean = true,
) {
    val unread: Boolean get() = available && replyCount > 0 && activity != null && activity != readActivity
}

/** Local ownership is independent of the account, so signing out does not lose guest posts. */
class CommunityRepository(private val backend: ForumBackend, private val preferences: SecurePreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val localPosts = MutableStateFlow(readPosts())
    private val localReplies = MutableStateFlow(readReplies())
    private val refreshFailure = MutableStateFlow(false)
    private val failedStorage = MutableStateFlow(false)
    private val mutex = Mutex()
    val posts = localPosts.asStateFlow()
    val replies = localReplies.asStateFlow()
    val replyCheckFailed = refreshFailure.asStateFlow()
    val storageSaveFailed = failedStorage.asStateFlow()
    val websiteUrl get() = backend.websiteUrl
    val storageFailed: Boolean get() = failedStorage.value
    val localSecrets: List<String> get() = localPosts.value.map { it.token }

    suspend fun profile(): ForumProfile = observed {
        backend.profile().let { profile ->
            if (profile.signedIn) profile else profile.copy(name = preferences.getString(NAME))
        }
    }

    suspend fun publish(draft: ForumDraft, images: List<ForumImage>): ForumPost = mutex.withLock { observed {
        val post = backend.publish(draft, images)
        localPosts.value = listOf(post) + localPosts.value
        save(draft.name.trim())
        post
    } }

    suspend fun delete(post: ForumPost) = mutex.withLock { observed {
        backend.delete(post)
        localPosts.value = localPosts.value.filterNot { it.id == post.id }
        localReplies.value = localReplies.value - post.id
        save()
    } }

    /** Failures retain the last known replies and are visible alongside the local diagnostics. */
    suspend fun refreshReplies(): Boolean = mutex.withLock {
        try {
            val ids = localPosts.value.map { it.id }
            val activity = if (ids.isEmpty()) emptyList() else observed { backend.activity(ids) }
            val byId = activity.associateBy { it.id }
            localReplies.value = ids.associateWith { id ->
                val previous = localReplies.value[id] ?: ForumReplyState()
                val current = byId[id]
                if (current == null) previous.copy(available = false, replyCount = 0)
                else previous.copy(replyCount = current.replyCount, activity = current.lastActivity, available = true)
            }
            refreshFailure.value = false
            save()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { refreshFailure.value = true; false }
    }

    /** Acknowledge only the snapshot opened by the user, never replies arriving after that click. */
    suspend fun markRead(id: String, activity: String?) = mutex.withLock {
        val state = localReplies.value[id] ?: return@withLock
        if (activity == null) return@withLock
        val opened = runCatching { Instant.parse(activity) }.getOrNull() ?: return@withLock
        val latest = state.activity?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@withLock
        val read = state.readActivity?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (opened > latest || read != null && opened < read) return@withLock
        localReplies.value = localReplies.value + (id to state.copy(readActivity = activity))
        save()
    }

    private suspend fun save(name: String? = null) = withContext(io) {
        try {
            val encoded = JsonArray(localPosts.value.map { post -> buildJsonObject {
                put("id", post.id); put("ownershipToken", post.token); put("title", post.title)
                localReplies.value[post.id]?.let { state ->
                    put("replyCount", state.replyCount)
                    put("activity", state.activity?.let(::JsonPrimitive) ?: JsonNull)
                    put("readActivity", state.readActivity?.let(::JsonPrimitive) ?: JsonNull)
                    put("available", state.available)
                }
            } }).toString()
            val changes = mutableMapOf<String, String?>(POSTS to encoded)
            if (name != null) changes[NAME] = name
            failedStorage.value = !preferences.write(changes)
        } catch (_: Exception) { failedStorage.value = true }
        // A failed local save must never turn a successful publication into an invitation to retry.
    }

    private fun readPosts(): List<ForumPost> = runCatching {
        Json.parseToJsonElement(preferences.getString(POSTS) ?: "[]").jsonArray.map { value ->
            val fields = value.jsonObject
            ForumPost(fields.getValue("id").jsonPrimitive.content, fields.getValue("ownershipToken").jsonPrimitive.content,
                fields.getValue("title").jsonPrimitive.content)
        }
    }.getOrDefault(emptyList())

    private fun readReplies(): Map<String, ForumReplyState> = runCatching {
        Json.parseToJsonElement(preferences.getString(POSTS) ?: "[]").jsonArray.associate { value ->
            val fields = value.jsonObject
            fields.getValue("id").jsonPrimitive.content to ForumReplyState(
                replyCount = fields["replyCount"]?.jsonPrimitive?.intOrNull ?: 0,
                activity = fields["activity"]?.jsonPrimitive?.contentOrNull,
                readActivity = fields["readActivity"]?.jsonPrimitive?.contentOrNull,
                available = fields["available"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    }.getOrDefault(emptyMap())

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
