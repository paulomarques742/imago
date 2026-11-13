package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.GalleryBackend
import eu.studio742.imago.core.sync.GalleryBackendException
import eu.studio742.imago.core.sync.GalleryBackendException.Reason
import eu.studio742.imago.core.sync.GalleryImage
import eu.studio742.imago.core.sync.SupporterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

private const val BUCKET = "gallery-images"
private val LINK_ID = Regex("[A-Za-z0-9_-]{16,64}")

/** The account a request goes as: its token, and its id, which names the folder its photos go into. */
data class GallerySession(val accessToken: String, val userId: String)

/**
 * The `gallery_*` functions and the `gallery-images` bucket (20261111150000_supporters_gallery.sql),
 * the same the website uses. Photos go first, into the account's own folder; if publishing then
 * fails, they are deleted again, so a refused post leaves nothing behind.
 */
class SupabaseGalleryBackend(
    private val settings: SupabaseSettings,
    httpClient: OkHttpClient,
    override val websiteUrl: String,
    private val session: suspend () -> GallerySession?,
) : GalleryBackend {
    // A redirect must not forward the account's credentials to another host.
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val base get() = settings.url.trimEnd('/')

    override suspend fun supporter(): SupporterState = withContext(Dispatchers.IO) {
        val answer = try {
            rpc("supporter_me", JsonObject(emptyMap()), required().accessToken).jsonObject
        } catch (_: ProgrammeMissing) {
            return@withContext SupporterState(active = false, until = null)
        }
        SupporterState(
            active = answer["active"]?.jsonPrimitive?.booleanOrNull ?: false,
            until = (answer["until"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    override suspend fun publishedLinks(): Set<String> = withContext(Dispatchers.IO) {
        val rows = try {
            rpc("gallery_mine", JsonObject(emptyMap()), required().accessToken) as? JsonArray ?: throw GalleryBackendException(Reason.FAILED)
        } catch (_: ProgrammeMissing) {
            return@withContext emptySet()
        }
        rows.mapNotNull { row -> (row as? JsonObject)?.get("link_id")?.jsonPrimitive?.contentOrNull?.takeIf(LINK_ID::matches) }.toSet()
    }

    override suspend fun publish(linkId: String, title: String, authorName: String, before: GalleryImage, after: GalleryImage): String =
        withContext(Dispatchers.IO) {
            if (!LINK_ID.matches(linkId)) throw GalleryBackendException(Reason.LINK_NOT_FOUND)
            val account = required()
            val uploaded = mutableListOf<String>()
            try {
                for (image in listOf(before, after)) uploaded += upload(account, image)
                val answer = rpc("gallery_publish", buildJsonObject {
                    put("p_link", linkId)
                    put("p_title", title)
                    put("p_author_name", authorName)
                    put("p_before", uploaded[0])
                    put("p_after", uploaded[1])
                }, account.accessToken)
                val id = answer.jsonPrimitive.contentOrNull ?: throw GalleryBackendException(Reason.FAILED)
                uploaded.clear()
                id
            } finally {
                if (uploaded.isNotEmpty()) remove(account, uploaded)
            }
        }

    private suspend fun required(): GallerySession = session() ?: throw GalleryBackendException(Reason.NO_ACCOUNT)

    private fun upload(account: GallerySession, image: GalleryImage): String {
        val path = "${account.userId}/${UUID.randomUUID()}.jpg"
        val request = Request.Builder().url("$base/storage/v1/object/$BUCKET/$path")
            .header("apikey", settings.publishableKey)
            .header("Authorization", "Bearer ${account.accessToken}")
            // Paths are random and never rewritten: the photo can stay cached for good.
            .header("cache-control", "max-age=31536000")
            .header("x-upsert", "false")
            .post(image.bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) return path
                // The bucket's policy refuses anyone who is not a supporter, or a folder not their own.
                throw GalleryBackendException(
                    when (response.code) {
                        401 -> Reason.NO_ACCOUNT
                        403 -> Reason.NOT_SUPPORTER
                        else -> Reason.IMAGE_UPLOAD
                    },
                    response.code,
                )
            }
        } catch (_: IOException) {
            throw GalleryBackendException(Reason.OFFLINE)
        }
    }

    /** Best effort: a photo left behind is only one unused file in the account's own folder. */
    private fun remove(account: GallerySession, paths: List<String>) {
        val body = buildJsonObject { put("prefixes", JsonArray(paths.map(::JsonPrimitive))) }
        val request = Request.Builder().url("$base/storage/v1/object/$BUCKET")
            .header("apikey", settings.publishableKey)
            .header("Authorization", "Bearer ${account.accessToken}")
            .delete(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { http.newCall(request).execute().close() }
    }

    private fun rpc(name: String, body: JsonObject, token: String): JsonElement {
        val request = Request.Builder().url("$base/rest/v1/rpc/$name")
            .header("apikey", settings.publishableKey)
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return if (text.isBlank()) JsonNull else runCatching { json.parseToJsonElement(text) }.getOrElse { throw GalleryBackendException(Reason.FAILED) }
                }
                val error = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                // PostgREST's answer for a function that does not exist: a server without the programme yet.
                if (response.code == 404 && error?.get("code")?.jsonPrimitive?.contentOrNull == "PGRST202") throw ProgrammeMissing()
                val hint = error?.get("hint")?.jsonPrimitive?.contentOrNull
                val reason = if (response.code == 401) Reason.NO_ACCOUNT else when (hint) {
                    "supporter_no_account" -> Reason.NO_ACCOUNT
                    "gallery_not_supporter" -> Reason.NOT_SUPPORTER
                    "gallery_link_not_found" -> Reason.LINK_NOT_FOUND
                    "gallery_imported" -> Reason.IMPORTED
                    "gallery_already_published" -> Reason.ALREADY_PUBLISHED
                    "gallery_invalid" -> Reason.INVALID
                    "gallery_limit" -> Reason.LIMIT
                    "gallery_image_missing" -> Reason.IMAGE_UPLOAD
                    else -> Reason.FAILED
                }
                throw GalleryBackendException(reason, response.code)
            }
        } catch (_: IOException) {
            throw GalleryBackendException(Reason.OFFLINE)
        }
    }

    private class ProgrammeMissing : Exception()
}
