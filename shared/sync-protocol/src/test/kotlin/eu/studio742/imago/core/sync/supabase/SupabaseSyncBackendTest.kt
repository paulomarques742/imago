package eu.studio742.imago.core.sync.supabase

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import eu.studio742.imago.core.sync.*
import java.util.concurrent.CopyOnWriteArrayList

class SupabaseSyncBackendTest {
    private val stores = mutableMapOf<String, SessionStore>()
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, MockResponse>()
    /** Routes whose answer depends on the request. */
    private val handlers = mutableMapOf<String, (RecordedRequest) -> MockResponse>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty().substringBefore('?')
                return handlers[path]?.invoke(request) ?: routes[path] ?: MockResponse().setResponseCode(404).setBody("{}")
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun backend(prefs: String = java.util.UUID.randomUUID().toString()) = SupabaseSyncBackend(
        SupabaseSettings(server.url("/").toString().trimEnd('/'), "sb_publishable_test"),
        stores.getOrPut(prefs) { InMemorySessionStore() },
        OkHttpClient(),
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun signInResponds(userId: String = "user-a") {
        routes["/auth/v1/token"] = json(
            """{"access_token":"jwt-token","token_type":"bearer","expires_in":3600,"refresh_token":"refresh",
               "user":{"id":"$userId","aud":"authenticated","email":"ana@example.test"}}""",
        )
    }

    @Test fun withoutAnAccountNothingLeavesTheDevice() = runBlocking {
        val backend = backend()
        assertTrue(backend.isAvailable)
        delay(300)
        assertNull(backend.session.value)
        withTimeout(1_000) { assertTrue(runCatching { withTimeout(300) { backend.changes().first() } }.isFailure) }
        assertEquals(0, server.requestCount)
    }

    @Test fun anIncompleteProjectMeansNoAccountAndNoClient() = runBlocking {
        val backend = SupabaseSyncBackend(SupabaseSettings("", ""), null, OkHttpClient(), false)
        assertFalse(backend.isAvailable)
        val error = runCatching { backend.signIn("a@b.c", "x") }.exceptionOrNull() as AccountException
        assertEquals(AccountException.Reason.NOT_AVAILABLE, error.reason)
    }

    @Test fun signingInKeepsTheSessionAndCallsCarryOnlyTheAccountCredentials() = runBlocking {
        signInResponds()
        routes["/rest/v1/rpc/sync_push"] = json(
            """[{"status":"applied","key":"p","revision":1,"seq":10},
                {"status":"conflict","key":"q","remote":{"key":"q","revision":2,"seq":9,"payload":{"name":"remoto"},
                  "edited_at":"2026-09-15T10:00:00+00:00","edited_by_device":"d2","deleted_at":null}},
                {"status":"rejected","key":"r","reason":"payload_too_large"}]""",
        )
        val prefs = java.util.UUID.randomUUID().toString()
        val backend = backend(prefs)
        backend.signIn("ana@example.test", "password")
        assertEquals(AccountSession("user-a", "ana@example.test"), withTimeout(2_000) { backend.session.first { it != null } })

        val results = backend.push(SyncEntity.PROJECT, listOf(
            OutgoingChange("p", null, buildJsonObject { put("name", "local") }, "2026-09-15T10:00:00Z", "d1"),
        ))
        assertEquals(PushResult.Applied("p", 1, 10), results[0])
        val conflict = results[1] as PushResult.Conflict
        assertEquals(2L, conflict.remote.revision)
        assertEquals("d2", conflict.remote.editedByDevice)
        assertEquals(PushResult.Rejected("r", "payload_too_large"), results[2])

        val token = requests.first { it.path!!.startsWith("/auth/v1/token") }
        assertEquals("sb_publishable_test", token.getHeader("apikey"))
        val push = requests.first { it.path == "/rest/v1/rpc/sync_push" }
        assertEquals("Bearer jwt-token", push.getHeader("Authorization"))
        for (request in requests) assertNull("Requests to the account never carry the Immich key", request.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(push.body.readUtf8()).jsonObject
        assertEquals("PROJECT", body["p_entity"]!!.jsonPrimitive.content)
        val change = body["p_changes"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, change["baseRevision"])
        assertEquals("d1", change["deviceId"]!!.jsonPrimitive.content)

        // The session stays saved and comes back with the app.
        val reopened = backend(prefs)
        assertEquals("user-a", withTimeout(2_000) { reopened.session.first { it != null } }!!.userId)
    }

    private fun tokenResponds(token: String, expiresIn: Int, refresh: String) {
        routes["/auth/v1/token"] = json(
            """{"access_token":"$token","token_type":"bearer","expires_in":$expiresIn,"refresh_token":"$refresh",
               "user":{"id":"user-a","aud":"authenticated","email":"ana@example.test"}}""",
        )
    }

    private val emptyPage = """{"rows":[],"cursor":0}"""

    /** Coming back to the app after an hour: the token about to expire is refreshed before the call. */
    @Test fun aTokenAboutToExpireIsRefreshedBeforeTheCall() = runBlocking {
        tokenResponds("jwt-old", expiresIn = 30, refresh = "refresh-1")
        val backend = backend()
        backend.signIn("ana@example.test", "password")
        tokenResponds("jwt-new", expiresIn = 3600, refresh = "refresh-2")
        routes["/rest/v1/rpc/sync_pull"] = json(emptyPage)

        backend.pull(SyncEntity.RECIPE, 0, 10)

        assertEquals("Bearer jwt-new", requests.last { it.path == "/rest/v1/rpc/sync_pull" }.getHeader("Authorization"))
    }

    /** The token can still expire between the check and the request: a 401 refreshes it and tries once more. */
    @Test fun aCallRefusedForAnExpiredTokenIsRetriedWithANewOne() = runBlocking {
        tokenResponds("jwt-old", expiresIn = 3600, refresh = "refresh-1")
        val backend = backend()
        backend.signIn("ana@example.test", "password")
        tokenResponds("jwt-new", expiresIn = 3600, refresh = "refresh-2")
        handlers["/rest/v1/rpc/sync_pull"] = { request ->
            if (request.getHeader("Authorization") == "Bearer jwt-old") {
                MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                    .setBody("""{"code":"PGRST303","message":"JWT expired","details":null,"hint":null}""")
            } else {
                json(emptyPage)
            }
        }

        val page = backend.pull(SyncEntity.RECIPE, 0, 10)

        assertEquals(0L, page.cursor)
        val pulls = requests.filter { it.path == "/rest/v1/rpc/sync_pull" }.map { it.getHeader("Authorization") }
        assertEquals(listOf("Bearer jwt-old", "Bearer jwt-new"), pulls)
    }

    @Test fun pullReadsTheCursorAndTheRows() = runBlocking {
        signInResponds()
        routes["/rest/v1/rpc/sync_pull"] = json(
            """{"rows":[{"key":"a9993e364706816aba3e25717850c26c9cd0d89d","revision":3,"seq":42,"payload":{"schemaVersion":1},
                 "hints":{"fileName":"PXL.jpg"},"edited_at":"2026-09-15T10:00:00+00:00","edited_by_device":null,
                 "deleted_at":"2026-09-15T11:00:00+00:00"}],"cursor":42}""",
        )
        val backend = backend()
        backend.signIn("ana@example.test", "password")
        val page = backend.pull(SyncEntity.RECIPE, 7, 100)
        assertEquals(42L, page.cursor)
        assertEquals("PXL.jpg", page.rows.single().hints!!["fileName"]!!.jsonPrimitive.content)
        assertEquals("2026-09-15T11:00:00+00:00", page.rows.single().deletedAt)
        val body = Json.parseToJsonElement(requests.last().body.readUtf8()).jsonObject
        assertEquals(7L, body["p_cursor"]!!.jsonPrimitive.long)
        assertEquals(100, body["p_limit"]!!.jsonPrimitive.int)
    }

    @Test fun devicesAndLibrariesGoThroughTheirFunctions() = runBlocking {
        signInResponds()
        routes["/rest/v1/rpc/sync_register_device"] = json("\"6f1c2a52-1111-4b5d-9a39-4f0f5d3c1a01\"")
        routes["/rest/v1/rpc/sync_link_library"] = json("\"0b7d1f2e-2222-4f4a-8c1e-9d8b7a6c5e02\"")
        routes["/rest/v1/rpc/sync_touch_device"] = MockResponse().setResponseCode(404)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"code":"P0002","message":"Unknown device in this account.","details":null,"hint":null}""")
        val backend = backend()
        backend.signIn("ana@example.test", "password")

        assertEquals("6f1c2a52-1111-4b5d-9a39-4f0f5d3c1a01", backend.registerDevice("Pixel 8", "android"))
        assertEquals("0b7d1f2e-2222-4f4a-8c1e-9d8b7a6c5e02", backend.linkLibrary("immich", "abc", "Casa", null))
        assertFalse(backend.touchDevice("gone"))
        val link = Json.parseToJsonElement(requests.first { it.path == "/rest/v1/rpc/sync_link_library" }.body.readUtf8()).jsonObject
        assertEquals(JsonNull, link["p_device"])
        assertEquals("abc", link["p_fingerprint"]!!.jsonPrimitive.content)
    }

    @Test fun authErrorsBecomeMessagesThePersonCanActOn() = runBlocking {
        routes["/auth/v1/token"] = MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("""{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
        val backend = backend()
        val wrong = runCatching { backend.signIn("ana@example.test", "errada") }.exceptionOrNull() as AccountException
        assertEquals(AccountException.Reason.INVALID_CREDENTIALS, wrong.reason)

        routes["/auth/v1/token"] = MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("""{"code":400,"error_code":"email_not_confirmed","msg":"Email not confirmed"}""")
        val unconfirmed = runCatching { backend.signIn("ana@example.test", "password") }.exceptionOrNull() as AccountException
        assertEquals(AccountException.Reason.EMAIL_NOT_CONFIRMED, unconfirmed.reason)

        server.shutdown()
        val offline = runCatching { backend.signIn("ana@example.test", "password") }.exceptionOrNull() as AccountException
        assertEquals(AccountException.Reason.OFFLINE, offline.reason)
    }

    @Test fun signingOutWithoutNetworkStillEndsTheSessionHere() = runBlocking {
        signInResponds()
        val backend = backend()
        backend.signIn("ana@example.test", "password")
        withTimeout(2_000) { backend.session.first { it != null } }
        server.shutdown()
        backend.signOut()
        assertNull(withTimeout(2_000) { backend.session.first { it == null } })
    }

    @Test fun onlyTheAppsOwnLinksAreHandled() {
        val backend = backend()
        assertNull(backend.handleAuthLink("https://example.test/auth/callback"))
        assertNull(backend.handleAuthLink("imago://other/callback"))
        assertEquals(AuthLink.CONFIRMATION, backend.handleAuthLink("imago://auth/callback?code=abc"))
        assertEquals(AuthLink.RECOVERY, backend.handleAuthLink("imago://auth/recovery?code=abc"))
    }
}
