package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.RecipeLink
import eu.studio742.imago.core.sync.RecipeLinkBackend
import eu.studio742.imago.core.sync.RecipeLinkBackendException
import eu.studio742.imago.core.sync.RecipeLinkBackendException.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

private val LINK_ID = Regex("[A-Za-z0-9_-]{16,64}")

/** The `shared_recipe_*` functions. Opening a link sends no account, so that it works the same for anyone. */
class SupabaseRecipeLinkBackend(
    private val settings: SupabaseSettings,
    httpClient: OkHttpClient,
    override val websiteUrl: String?,
    private val accessToken: suspend () -> String?,
) : RecipeLinkBackend {
    // A redirect must not forward the account's credentials to another host.
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun create(recipeId: String, content: String): String = withContext(Dispatchers.IO) {
        val answer = rpc("shared_recipe_create", buildJsonObject {
            put("p_recipe_id", recipeId)
            put("p_content", parsed(content))
        }, requiredToken())
        validId(answer.jsonPrimitive.contentOrNull)
    }

    override suspend fun update(id: String, content: String): Unit = withContext(Dispatchers.IO) {
        rpc("shared_recipe_update", buildJsonObject {
            put("p_id", validId(id))
            put("p_content", parsed(content))
        }, requiredToken())
    }

    override suspend fun revoke(id: String): Unit = withContext(Dispatchers.IO) {
        rpc("shared_recipe_revoke", buildJsonObject { put("p_id", validId(id)) }, requiredToken())
    }

    override suspend fun mine(): List<RecipeLink> = withContext(Dispatchers.IO) {
        val rows = rpc("shared_recipe_mine", buildJsonObject {}, requiredToken()) as? JsonArray ?: throw RecipeLinkBackendException(Reason.FAILED)
        rows.map { row ->
            val fields = row.jsonObject
            RecipeLink(validId(fields["id"]?.jsonPrimitive?.contentOrNull), fields["recipe_id"]?.jsonPrimitive?.contentOrNull ?: throw RecipeLinkBackendException(Reason.FAILED))
        }
    }

    override suspend fun open(id: String): String? = withContext(Dispatchers.IO) {
        if (!LINK_ID.matches(id)) return@withContext null
        val answer = rpc("shared_recipe_get", buildJsonObject { put("p_id", id) }, token = null)
        if (answer is JsonNull) null else answer.toString()
    }

    private suspend fun requiredToken(): String = accessToken() ?: throw RecipeLinkBackendException(Reason.NO_ACCOUNT)

    private fun parsed(content: String): JsonElement =
        runCatching { json.parseToJsonElement(content) }.getOrElse { throw RecipeLinkBackendException(Reason.FAILED) }

    private fun validId(id: String?): String =
        id?.takeIf(LINK_ID::matches) ?: throw RecipeLinkBackendException(Reason.FAILED)

    private fun rpc(name: String, body: JsonObject, token: String?): JsonElement {
        val request = Request.Builder().url("${settings.url.trimEnd('/')}/rest/v1/rpc/$name")
            .header("apikey", settings.publishableKey)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return if (text.isBlank()) JsonNull else runCatching { json.parseToJsonElement(text) }.getOrElse { throw RecipeLinkBackendException(Reason.FAILED) }
                }
                val hint = runCatching { json.parseToJsonElement(text).jsonObject["hint"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                val reason = when {
                    response.code == 401 -> Reason.NO_ACCOUNT
                    else -> when (hint) {
                        "shared_recipe_no_account" -> Reason.NO_ACCOUNT
                        "shared_recipe_limit" -> Reason.LIMIT
                        "shared_recipe_too_large" -> Reason.TOO_LARGE
                        "shared_recipe_not_found" -> Reason.NOT_FOUND
                        else -> Reason.FAILED
                    }
                }
                throw RecipeLinkBackendException(reason, response.code)
            }
        } catch (_: IOException) {
            throw RecipeLinkBackendException(Reason.OFFLINE)
        }
    }
}
