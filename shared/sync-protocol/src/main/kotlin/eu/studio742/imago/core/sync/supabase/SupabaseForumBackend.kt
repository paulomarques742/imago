package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** Uses the existing forum RPCs and upload slots. Never sends a request on construction. */
class SupabaseForumBackend(
    private val settings: SupabaseSettings,
    httpClient: OkHttpClient,
    private val accessToken: suspend () -> String?,
) : ForumBackend {
    override val websiteUrl = settings.forumWebsiteUrl?.takeIf { value -> runCatching {
        val uri = java.net.URI(value)
        uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))
    }.getOrDefault(false) }
    // A redirect must not forward account credentials or the public post to another host.
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun profile(): ForumProfile = withContext(Dispatchers.IO) {
        val token = accessToken() ?: return@withContext ForumProfile(false, null)
        val data = rpc("forum_me", buildJsonObject {}, token).jsonObject
        ForumProfile(true, data["name"]?.jsonPrimitive?.contentOrNull)
    }

    override suspend fun publish(draft: ForumDraft, images: List<ForumImage>): ForumPost = withContext(Dispatchers.IO) {
        if (!draft.isValid() || images.size > 4) throw ForumException(ForumException.Reason.INVALID)
        // One identity for the whole publication, including its images. Never retry a write as a guest.
        val token = accessToken()
        val paths = if (images.isEmpty()) emptyList() else {
            rpc("forum_request_uploads", buildJsonObject { put("p_count", images.size) }, token)
                .jsonArray.map { uuid(it.jsonPrimitive.content) }.also { paths ->
                    if (paths.size != images.size) throw ForumException(ForumException.Reason.FAILED)
                    paths.zip(images).forEach { (path, image) ->
                        request("storage/v1/object/forum-images/$path", image.bytes.toRequestBody("image/jpeg".toMediaType()), token,
                            mapOf("x-upsert" to "false", "cache-control" to "max-age=31536000"))
                    }
                }
        }
        val result = rpc("forum_create_topic", buildJsonObject {
            put("p_category", draft.category.wire)
            put("p_title", draft.title.trim()); put("p_body", draft.body.trim()); put("p_author_name", draft.name.trim())
            put("p_app_version", if (draft.category == ForumCategory.BUG) JsonPrimitive(draft.appVersion) else JsonNull)
            put("p_device", if (draft.category == ForumCategory.BUG) JsonPrimitive(draft.device) else JsonNull)
            put("p_website", "")
            put("p_images", JsonArray(paths.map(::JsonPrimitive)))
        }, token).jsonObject
        val id = uuid(result.getValue("id").jsonPrimitive.content)
        val owner = result.getValue("token").jsonPrimitive.content
        if (owner.length != 64 || owner.any { it !in '0'..'9' && it !in 'a'..'f' }) throw ForumException(ForumException.Reason.FAILED)
        ForumPost(id, owner, draft.title.trim())
    }

    override suspend fun delete(post: ForumPost): Unit = withContext(Dispatchers.IO) {
        val token = accessToken()
        val removed = rpc("forum_delete", buildJsonObject { put("p_id", uuid(post.id)); put("p_token", post.token) }, token)
        // The server's orphan cleanup also handles interruptions between deletion and storage cleanup.
        if (removed is JsonArray && removed.isNotEmpty()) {
            try {
                request("storage/v1/object/forum-images", buildJsonObject { put("prefixes", removed) }.toString()
                    .toRequestBody("application/json".toMediaType()), token, method = "DELETE")
            } catch (_: ForumException) { /* The post is already deleted. */ }
        }
    }

    private fun rpc(name: String, body: JsonObject, token: String?): JsonElement =
        json.parseToJsonElement(request("rest/v1/rpc/$name", body.toString().toRequestBody("application/json".toMediaType()), token))

    private fun request(path: String, body: RequestBody, token: String?, headers: Map<String, String> = emptyMap(), method: String = "POST"): String {
        val request = Request.Builder().url("${settings.url.trimEnd('/')}/$path").header("apikey", settings.publishableKey)
            .apply { if (token != null) header("Authorization", "Bearer $token"); headers.forEach { (key, value) -> header(key, value) } }
            .method(method, body).build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) return text.ifBlank { "null" }
                val hint = runCatching { json.parseToJsonElement(text).jsonObject["hint"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                val reason = when {
                    response.code == 401 && token != null -> ForumException.Reason.SESSION_EXPIRED
                    response.code == 413 -> ForumException.Reason.IMAGE_UPLOAD
                    else -> when (hint) {
                        "forum_invalid" -> ForumException.Reason.INVALID
                        "forum_rate_limited" -> ForumException.Reason.RATE_LIMITED
                        "forum_busy" -> ForumException.Reason.BUSY
                        "forum_too_many_links" -> ForumException.Reason.TOO_MANY_LINKS
                        "forum_rejected" -> ForumException.Reason.REJECTED
                        "forum_image_missing" -> ForumException.Reason.IMAGE_MISSING
                        "forum_forbidden" -> ForumException.Reason.FORBIDDEN
                        "forum_has_replies" -> ForumException.Reason.HAS_REPLIES
                        "forum_not_found" -> ForumException.Reason.NOT_FOUND
                        else -> ForumException.Reason.FAILED
                    }
                }
                throw ForumException(reason, response.code)
            }
        } catch (_: IOException) { throw ForumException(ForumException.Reason.OFFLINE) }
    }

    private fun uuid(value: String): String {
        if (runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) return value
        throw ForumException(ForumException.Reason.FAILED)
    }
}
