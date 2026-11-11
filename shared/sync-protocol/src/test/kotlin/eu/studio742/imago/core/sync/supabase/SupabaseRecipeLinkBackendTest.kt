package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.RecipeLink
import eu.studio742.imago.core.sync.RecipeLinkBackendException
import eu.studio742.imago.core.sync.RecipeLinkBackendException.Reason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SupabaseRecipeLinkBackendTest {
    private val server = MockWebServer().apply { start() }
    private val id = "abcdefghijklmnopqrstuvwxyz0"
    private val file = """{"format":"imago","kind":"recipes","recipes":[{"name":"Warm"}]}"""

    private fun backend(token: String? = "session") = SupabaseRecipeLinkBackend(
        SupabaseSettings(server.url("/").toString(), "sb_publishable_fixture"),
        OkHttpClient(),
        "https://site.example",
    ) { token }

    private fun answer(body: String, status: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun failure(reasonOf: suspend () -> Unit): RecipeLinkBackendException =
        assertThrows(RecipeLinkBackendException::class.java) { runBlocking { reasonOf() } }

    @After fun close() = server.shutdown()

    @Test fun creatingSendsTheAccountAndTheFileAsJson() = runBlocking {
        answer("\"$id\"")
        assertEquals(id, backend().create("recipe-1", file))

        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/shared_recipe_create", request.path)
        assertEquals("Bearer session", request.getHeader("Authorization"))
        val fields = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("recipe-1"), fields["p_recipe_id"])
        assertEquals(Json.parseToJsonElement(file), fields["p_content"])
    }

    @Test fun openingANewLinkSendsNoAccount() = runBlocking {
        answer(file)
        assertEquals(Json.parseToJsonElement(file), Json.parseToJsonElement(backend().open(id)!!))

        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/shared_recipe_get", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun aLinkThatIsGoneIsNull() = runBlocking {
        answer("null")
        assertNull(backend().open(id))
    }

    @Test fun aMalformedIdentifierNeverReachesTheServer() = runBlocking {
        assertNull(backend().open("../../etc/passwd"))
        assertEquals(0, server.requestCount)
    }

    @Test fun withoutAnAccountNothingIsSent() {
        assertEquals(Reason.NO_ACCOUNT, failure { backend(token = null).create("r", file) }.reason)
        assertEquals(Reason.NO_ACCOUNT, failure { backend(token = null).mine() }.reason)
        assertEquals(0, server.requestCount)
    }

    @Test fun theListHasTheRecipeOfEachLink() = runBlocking {
        answer("""[{"id":"$id","recipe_id":"recipe-1","updated_at":"2026-11-09T10:00:00Z"}]""")
        assertEquals(listOf(RecipeLink(id, "recipe-1")), backend().mine())
    }

    @Test fun theServersHintsBecomeReasons() {
        fun hint(value: String) { answer("""{"code":"P0001","message":"x","hint":"$value"}""", 400) }
        hint("shared_recipe_limit")
        assertEquals(Reason.LIMIT, failure { backend().create("r", file) }.reason)
        hint("shared_recipe_too_large")
        assertEquals(Reason.TOO_LARGE, failure { backend().create("r", file) }.reason)
        hint("shared_recipe_not_found")
        assertEquals(Reason.NOT_FOUND, failure { backend().update(id, file) }.reason)
        hint("something_else")
        assertEquals(Reason.FAILED, failure { backend().revoke(id) }.reason)
    }

    @Test fun anIdentifierThatDoesNotLookLikeOneIsRefused() {
        answer("\"short\"")
        assertEquals(Reason.FAILED, failure { backend().create("r", file) }.reason)
    }

    @Test fun anExpiredSessionAsksForTheAccount() {
        answer("""{"message":"JWT expired"}""", 401)
        assertEquals(Reason.NO_ACCOUNT, failure { backend().mine() }.reason)
    }

    @Test fun aServerThatCannotBeReachedIsOffline() {
        server.shutdown()
        assertEquals(Reason.OFFLINE, failure { backend().mine() }.reason)
    }
}
