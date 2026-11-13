package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.PlayPurchaseBackend
import eu.studio742.imago.core.sync.PlayPurchaseException
import eu.studio742.imago.core.sync.PlayPurchaseException.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

/** The `play-subscription` Edge Function, with the account's session. */
class SupabasePlayPurchaseBackend(
    private val settings: SupabaseSettings,
    httpClient: OkHttpClient,
    private val accessToken: suspend () -> String?,
) : PlayPurchaseBackend {
    // A redirect must not forward the account's credentials or the purchase token to another host.
    private val http = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun record(purchaseToken: String): Unit = withContext(Dispatchers.IO) {
        if (purchaseToken.isEmpty() || purchaseToken.length > 2000) throw PlayPurchaseException(Reason.FAILED)
        val token = try {
            accessToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw PlayPurchaseException(Reason.NO_ACCOUNT)
        val request = Request.Builder().url("${settings.url.trimEnd('/')}/functions/v1/play-subscription")
            .header("apikey", settings.publishableKey)
            .header("Authorization", "Bearer $token")
            .post(buildJsonObject { put("purchaseToken", purchaseToken) }.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) return@withContext
                val error = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                val reason = when {
                    response.code == 401 -> Reason.NO_ACCOUNT
                    error == "other_account" -> Reason.OTHER_ACCOUNT
                    error in setOf("not_paid", "wrong_product", "not_found", "test_purchase") -> Reason.NOT_PAID
                    // The function answers 503 while Play is not configured, or when Google cannot be reached.
                    response.code == 503 || response.code == 404 -> Reason.UNAVAILABLE
                    else -> Reason.FAILED
                }
                throw PlayPurchaseException(reason, response.code)
            }
        } catch (_: IOException) {
            throw PlayPurchaseException(Reason.OFFLINE)
        }
    }
}
