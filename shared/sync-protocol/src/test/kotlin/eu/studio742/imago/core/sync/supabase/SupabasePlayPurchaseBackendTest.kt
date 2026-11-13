package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.PlayPurchaseException
import eu.studio742.imago.core.sync.PlayPurchaseException.Reason
import eu.studio742.imago.core.sync.playAccountHash
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupabasePlayPurchaseBackendTest {
    private val server = MockWebServer().apply { start() }

    private fun backend(signedIn: Boolean = true) = SupabasePlayPurchaseBackend(
        SupabaseSettings(server.url("/").toString(), "sb_publishable_fixture"),
        OkHttpClient(),
    ) { if (signedIn) "session" else null }

    private fun answer(body: String, status: Int) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun failure(block: suspend () -> Unit): PlayPurchaseException =
        assertThrows(PlayPurchaseException::class.java) { runBlocking { block() } }

    @After fun close() = server.shutdown()

    @Test fun thePurchaseGoesToTheFunctionWithTheSession() = runBlocking {
        answer("""{"recorded":true}""", 200)
        backend().record("purchase-token")
        val request = server.takeRequest()
        assertEquals("/functions/v1/play-subscription", request.path)
        assertEquals("Bearer session", request.getHeader("Authorization"))
        assertEquals(JsonPrimitive("purchase-token"), Json.parseToJsonElement(request.body.readUtf8()).jsonObject["purchaseToken"])
    }

    @Test fun withoutAnAccountNothingIsSent() {
        assertEquals(Reason.NO_ACCOUNT, failure { backend(signedIn = false).record("purchase-token") }.reason)
        assertEquals(0, server.requestCount)
    }

    @Test fun theFunctionsAnswersAreNamed() {
        listOf(
            Triple(409, "other_account", Reason.OTHER_ACCOUNT),
            Triple(422, "not_paid", Reason.NOT_PAID),
            Triple(422, "test_purchase", Reason.NOT_PAID),
            Triple(503, "not_configured", Reason.UNAVAILABLE),
            Triple(401, "not_signed_in", Reason.NO_ACCOUNT),
            Triple(500, "boom", Reason.FAILED),
        ).forEach { (status, error, reason) ->
            answer("""{"error":"$error"}""", status)
            val thrown = failure { backend().record("purchase-token") }
            assertEquals(error, reason, thrown.reason)
            assertEquals(status, thrown.status)
        }
    }

    @Test fun noConnectionIsOffline() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(Reason.OFFLINE, failure { backend().record("purchase-token") }.reason)
    }

    @Test fun theAccountHashIsTheSameTheServerComputes() {
        // The same value as `accountHash` in the Edge Function (play.ts): hex SHA-256 of the account id.
        assertEquals("bd7662a5eeb41614e720d477abfcb2272e19a8a70a93b7e3bc8560d44ad326e9", playAccountHash("11111111-1111-4111-8111-111111111111"))
    }
}
