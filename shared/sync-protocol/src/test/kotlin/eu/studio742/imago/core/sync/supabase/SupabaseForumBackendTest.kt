package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SupabaseForumBackendTest {
    private val server = MockWebServer().apply { start() }
    private val id = "0f215197-027b-4d94-94b8-40f45d7e60fe"
    private val slot = "db2d3f57-19b0-4e78-8613-3fa390654e62"
    private val owner = "a".repeat(64)
    private val draft = ForumDraft(ForumCategory.QUESTION, "A question", "A message", "Public name")
    private fun backend(token: String? = null) = SupabaseForumBackend(
        SupabaseSettings(server.url("/").toString(), "sb_publishable_fixture"), OkHttpClient(), { token })
    private fun answer(body: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body))
    private fun created() = answer("""{"id":"$id","token":"$owner"}""")
    @After fun close() = server.shutdown()

    @Test fun guestsDoNotContactTheServerUntilTheyPublishAndNeverSendAuthorization() = runBlocking {
        val backend = backend()
        assertEquals(ForumProfile(false, null), backend.profile())
        assertEquals(0, server.requestCount)
        created()
        val post = backend.publish(draft, emptyList())
        assertEquals(id, post.id)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/forum_create_topic", request.path)
        assertEquals("POST", request.method)
        assertNull(request.getHeader("Authorization"))
        assertEquals("sb_publishable_fixture", request.getHeader("apikey"))
        val fields = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("p_category", "p_title", "p_body", "p_author_name", "p_app_version", "p_device", "p_website", "p_images"), fields.keys)
        assertEquals(JsonNull, fields["p_app_version"])
        assertEquals(JsonNull, fields["p_device"])
        assertEquals(JsonPrimitive("question"), fields["p_category"])
        assertEquals(JsonPrimitive("Public name"), fields["p_author_name"])
    }

    @Test fun signedInProfileAndEveryPublicationStageUseTheSameAccount() = runBlocking {
        answer("""{"name":"Forum name","posts":[],"admin":false}""")
        val backend = backend("account-access-token")
        assertEquals(ForumProfile(true, "Forum name"), backend.profile())
        assertEquals("/rest/v1/rpc/forum_me", server.takeRequest().path)
        answer("""["$slot"]"""); answer("{}"); created()
        val bytes = byteArrayOf(1, 2, 3)
        backend.publish(draft.copy(category = ForumCategory.BUG, appVersion = "Alpha", device = "Test device"), listOf(ForumImage(bytes)))
        val reserve = server.takeRequest()
        assertEquals("/rest/v1/rpc/forum_request_uploads", reserve.path)
        val upload = server.takeRequest()
        assertEquals("/storage/v1/object/forum-images/$slot", upload.path)
        assertEquals("image/jpeg", upload.getHeader("Content-Type"))
        assertArrayEquals(bytes, upload.body.readByteArray())
        val publish = server.takeRequest()
        val fields = Json.parseToJsonElement(publish.body.readUtf8()).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(slot))), fields["p_images"])
        assertEquals(JsonPrimitive("Alpha"), fields["p_app_version"])
        assertEquals(JsonPrimitive("Test device"), fields["p_device"])
        listOf(reserve, upload, publish).forEach { assertEquals("Bearer account-access-token", it.getHeader("Authorization")) }
    }

    @Test fun rejectedUploadsNeverCreateATopic() = runBlocking {
        answer("""["$slot"]"""); answer("{}", 413)
        val failure = runCatching { backend().publish(draft, listOf(ForumImage(byteArrayOf(1)))) }.exceptionOrNull() as ForumException
        assertEquals(ForumException.Reason.IMAGE_UPLOAD, failure.reason)
        assertEquals(2, server.requestCount)
    }

    @Test fun serverHintsAreClosedValuesAndResponsesNeverBecomeExceptionMessages() = runBlocking {
        answer("""{"hint":"forum_rate_limited","message":"secret URL and credential"}""", 400)
        val error = runCatching { backend().publish(draft, emptyList()) }.exceptionOrNull() as ForumException
        assertEquals(ForumException.Reason.RATE_LIMITED, error.reason)
        assertFalse(error.toString().contains("secret"))
        assertNull(error.cause)
    }

    @Test fun expiredAccountIsNotRetriedAsAGuest() = runBlocking {
        answer("{}", 401)
        val error = runCatching { backend("expired").publish(draft, emptyList()) }.exceptionOrNull() as ForumException
        assertEquals(ForumException.Reason.SESSION_EXPIRED, error.reason)
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidDraftAndTooManyImagesMakeNoRequests() = runBlocking {
        assertFalse(draft.copy(title = "ab").isValid())
        assertFalse(draft.copy(name = " ").isValid())
        assertFalse(draft.copy(body = "x".repeat(5001)).isValid())
        assertTrue(runCatching { backend().publish(draft.copy(title = "ab"), emptyList()) }.isFailure)
        assertTrue(runCatching { backend().publish(draft, List(5) { ForumImage(byteArrayOf(1)) }) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun deleteUsesOwnershipTokenOnlyInTheRequestBodyAndCleansFreedImages() = runBlocking {
        answer("""["$slot"]"""); answer("[]")
        backend().delete(ForumPost(id, owner, draft.title))
        val deletion = server.takeRequest()
        assertEquals("/rest/v1/rpc/forum_delete", deletion.path)
        assertFalse(deletion.path!!.contains(owner))
        assertEquals(JsonPrimitive(owner), Json.parseToJsonElement(deletion.body.readUtf8()).jsonObject["p_token"])
        val cleanup = server.takeRequest()
        assertEquals("DELETE", cleanup.method)
        assertEquals("/storage/v1/object/forum-images", cleanup.path)
    }

    @Test fun redirectsNeverForwardThePostOrAccountToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirected")).setBody("{}"))
        created()
        assertTrue(runCatching { backend("account-token").publish(draft, emptyList()) }.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun replyChecksReadOnlyMetadataForLocalTopicsWithoutAccountOrOwnershipCredentials() = runBlocking {
        answer("""[{"id":"$id","reply_count":2,"last_activity_at":"2026-10-28T10:30:00+00:00"}]""")
        val activity = backend("account-access-token").activity(listOf(id, id)).single()
        assertEquals(ForumActivity(id, 2, "2026-10-28T10:30:00Z"), activity)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/rest/v1/forum_topics", request.requestUrl!!.encodedPath)
        assertEquals("id,reply_count,last_activity_at", request.requestUrl!!.queryParameter("select"))
        assertEquals("in.($id)", request.requestUrl!!.queryParameter("id"))
        assertEquals("1", request.requestUrl!!.queryParameter("limit"))
        assertNull(request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test fun emptyAndInvalidTopicListsNeverReachTheServer() = runBlocking {
        assertTrue(backend().activity(emptyList()).isEmpty())
        assertTrue(runCatching { backend().activity(listOf("invalid&select=body")) }.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun replyChecksBatchLongListsWithoutSilentlyTruncatingOwnedTopics() = runBlocking {
        val ids = List(51) { java.util.UUID.randomUUID().toString() }
        answer("[]"); answer("[]")
        assertTrue(backend().activity(ids).isEmpty())
        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        assertEquals("50", first.queryParameter("limit"))
        assertEquals("1", second.queryParameter("limit"))
        assertEquals("in.(${ids.last()})", second.queryParameter("id"))
    }

    @Test fun malformedMetadataAndHttpFailuresCannotLookLikeTopicsWithNoReplies() = runBlocking {
        for (body in listOf("{}", """[{"id":"$id","reply_count":-1,"last_activity_at":"2026-10-28T10:30:00Z"}]""",
            """[{"id":"$slot","reply_count":1,"last_activity_at":"2026-10-28T10:30:00Z"}]""",
            """[{"id":"$id","reply_count":1,"last_activity_at":"bad timestamp"}]""")) {
            answer(body)
            assertTrue(runCatching { backend().activity(listOf(id)) }.exceptionOrNull() is ForumException)
        }
        answer("""{"message":"private server text"}""", 503)
        val failure = runCatching { backend().activity(listOf(id)) }.exceptionOrNull() as ForumException
        assertEquals(503, failure.status)
        assertFalse(failure.toString().contains("private server text"))
    }
}
