package eu.studio742.imago.core.sync.supabase

import eu.studio742.imago.core.sync.GallerySort
import eu.studio742.imago.core.sync.GalleryBackendException
import eu.studio742.imago.core.sync.GalleryBackendException.Reason
import eu.studio742.imago.core.sync.GalleryImage
import eu.studio742.imago.core.sync.LinkImages
import eu.studio742.imago.core.sync.SupporterState
import eu.studio742.imago.core.sync.galleryUrl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseGalleryBackendTest {
    private val server = MockWebServer().apply { start() }
    private val link = "abcdefghijklmnopqrstuvwxyz0"
    private val user = "11111111-1111-4111-8111-111111111111"
    private val photo = GalleryImage(byteArrayOf(1, 2, 3))

    private fun backend(signedIn: Boolean = true) = SupabaseGalleryBackend(
        SupabaseSettings(server.url("/").toString(), "sb_publishable_fixture"),
        OkHttpClient(),
        "https://site.example",
    ) { if (signedIn) GallerySession("session", user) else null }

    private fun answer(body: String, status: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    private fun failure(block: suspend () -> Unit): GalleryBackendException =
        assertThrows(GalleryBackendException::class.java) { runBlocking { block() } }

    @After fun close() = server.shutdown()

    @Test fun theSupportStateComesFromTheServer() = runBlocking {
        answer("""{"active":true,"until":"2026-12-11T10:00:00+00:00"}""")
        assertEquals(SupporterState(true, "2026-12-11T10:00:00+00:00"), backend().supporter())
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/supporter_me", request.path)
        assertEquals("Bearer session", request.getHeader("Authorization"))
    }

    @Test fun neverSupportingIsNotActiveAndHasNoEnd() = runBlocking {
        answer("""{"active":false,"until":null}""")
        assertEquals(SupporterState(false, null), backend().supporter())
    }

    @Test fun aServerWithoutTheProgrammeIsLikeNeverSupporting() = runBlocking {
        answer("""{"code":"PGRST202","message":"Could not find the function"}""", status = 404)
        assertEquals(SupporterState(false, null), backend().supporter())
        answer("""{"code":"PGRST202","message":"Could not find the function"}""", status = 404)
        assertEquals(emptySet<String>(), backend().publishedLinks())
    }

    @Test fun theAccountsPublishedLinksComeFromTheServer() = runBlocking {
        answer("""[{"post_id":"$user","link_id":"$link","hidden":false},{"post_id":"$user","link_id":"not a link","hidden":true}]""")
        assertEquals(setOf(link), backend().publishedLinks())
        assertEquals("/rest/v1/rpc/gallery_mine", server.takeRequest().path)
    }

    @Test fun withoutAnAccountNothingIsAsked() {
        assertEquals(Reason.NO_ACCOUNT, failure { backend(signedIn = false).supporter() }.reason)
        assertEquals(0, server.requestCount)
    }

    @Test fun publishingUploadsBothPhotosIntoTheAccountsFolderThenPublishes() = runBlocking {
        answer("{}")
        answer("{}")
        answer("\"post-1\"")
        assertEquals("post-1", backend().publish(link, "Golden", "Ana", photo, photo))

        val before = server.takeRequest()
        val after = server.takeRequest()
        for (upload in listOf(before, after)) {
            assertTrue(upload.path!!.matches(Regex("/storage/v1/object/gallery-images/$user/[0-9a-f-]{36}\\.jpg")))
            assertEquals("image/jpeg", upload.getHeader("Content-Type"))
            assertEquals("Bearer session", upload.getHeader("Authorization"))
        }
        val publish = server.takeRequest()
        assertEquals("/rest/v1/rpc/gallery_publish", publish.path)
        val fields = Json.parseToJsonElement(publish.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(link), fields["p_link"])
        assertEquals(JsonPrimitive(before.path!!.removePrefix("/storage/v1/object/gallery-images/")), fields["p_before"])
        assertEquals(JsonPrimitive(after.path!!.removePrefix("/storage/v1/object/gallery-images/")), fields["p_after"])
    }

    @Test fun aRefusedPostDeletesThePhotosItSent() {
        answer("{}")
        answer("{}")
        answer("""{"hint":"gallery_imported"}""", status = 403)
        answer("[]")
        assertEquals(Reason.IMPORTED, failure { backend().publish(link, "Golden", "Ana", photo, photo) }.reason)

        repeat(3) { server.takeRequest() }
        val cleanup = server.takeRequest()
        assertEquals("DELETE", cleanup.method)
        assertEquals("/storage/v1/object/gallery-images", cleanup.path)
        assertTrue(cleanup.body.readUtf8().contains(user))
    }

    @Test fun theBucketRefusingSomeoneWhoIsNotASupporterSaysSo() {
        answer("""{"error":"Unauthorized"}""", status = 403)
        assertEquals(Reason.NOT_SUPPORTER, failure { backend().publish(link, "Golden", "Ana", photo, photo) }.reason)
    }

    @Test fun theServersRefusalsAreNamed() {
        val cases = mapOf(
            "gallery_not_supporter" to Reason.NOT_SUPPORTER,
            "gallery_link_not_found" to Reason.LINK_NOT_FOUND,
            "gallery_already_published" to Reason.ALREADY_PUBLISHED,
            "gallery_invalid" to Reason.INVALID,
            "gallery_limit" to Reason.LIMIT,
            "something_new" to Reason.FAILED,
        )
        for ((hint, reason) in cases) {
            answer("{}")
            answer("{}")
            answer("""{"hint":"$hint"}""", status = 400)
            answer("[]")
            assertEquals(hint, reason, failure { backend().publish(link, "Golden", "Ana", photo, photo) }.reason)
        }
    }

    @Test fun aLinkThatIsNotOneIsRefusedBeforeAnyRequest() {
        assertEquals(Reason.LINK_NOT_FOUND, failure { backend().publish("../etc", "Golden", "Ana", photo, photo) }.reason)
        assertEquals(0, server.requestCount)
    }

    @Test fun noConnectionIsOffline() {
        server.shutdown()
        assertEquals(Reason.OFFLINE, failure { backend().supporter() }.reason)
    }

    @Test fun anExpiredSessionIsNoAccount() {
        answer("{}", status = 401)
        assertEquals(Reason.NO_ACCOUNT, failure { backend().supporter() }.reason)
    }

    @Test fun theGalleryAddressFollowsTheLanguage() {
        assertEquals("https://site.example/gallery/", galleryUrl("https://site.example/"))
        assertEquals("https://site.example/pt/galeria/", galleryUrl("https://site.example", portuguese = true))
    }

    @Test fun aPhotoOverTheBucketsLimitIsRefusedBeforeSending() {
        assertThrows(IllegalArgumentException::class.java) { GalleryImage(ByteArray(GalleryImage.MAX_BYTES + 1)) }
    }

    @Test fun theImagesOnALinksPageAreNoneShownOrHidden() = runBlocking {
        answer("[]")
        assertEquals(LinkImages.NONE, backend().linkImages(link))
        answer("""[{"before_image":"$user/a.jpg","after_image":"$user/b.jpg","hidden":false}]""")
        assertEquals(LinkImages.SHOWN, backend().linkImages(link))
        answer("""[{"before_image":null,"after_image":null,"hidden":true}]""")
        assertEquals(LinkImages.HIDDEN, backend().linkImages(link))
        assertEquals("/rest/v1/rpc/shared_recipe_images", server.takeRequest().path)
    }

    @Test fun settingTheImagesUploadsBothThenDeletesTheOnesReplaced() = runBlocking {
        answer("{}")
        answer("{}")
        answer("""["$user/old-before.jpg","$user/old-after.jpg"]""")
        answer("[]")
        backend().setLinkImages(link, photo, photo)

        val before = server.takeRequest()
        val after = server.takeRequest()
        for (upload in listOf(before, after)) assertTrue(upload.path!!.matches(Regex("/storage/v1/object/gallery-images/$user/[0-9a-f-]{36}\\.jpg")))
        val set = server.takeRequest()
        assertEquals("/rest/v1/rpc/shared_recipe_set_images", set.path)
        val fields = Json.parseToJsonElement(set.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive(link), fields["p_id"])
        assertEquals(JsonPrimitive(before.path!!.removePrefix("/storage/v1/object/gallery-images/")), fields["p_before"])
        val cleanup = server.takeRequest()
        assertEquals("DELETE", cleanup.method)
        assertTrue(cleanup.body.readUtf8().contains("old-before.jpg"))
    }

    @Test fun aRefusedSetDeletesTheImagesItSent() {
        answer("{}")
        answer("{}")
        answer("""{"hint":"shared_recipe_images_blocked"}""", status = 403)
        answer("[]")
        assertEquals(Reason.IMAGES_BLOCKED, failure { backend().setLinkImages(link, photo, photo) }.reason)
        repeat(3) { server.takeRequest() }
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun clearingTheImagesSendsNullsAndDeletesWhatCameBack() = runBlocking {
        answer("""["$user/a.jpg","$user/b.jpg"]""")
        answer("[]")
        backend().clearLinkImages(link)
        val clear = server.takeRequest()
        assertEquals("/rest/v1/rpc/shared_recipe_set_images", clear.path)
        val fields = Json.parseToJsonElement(clear.body.readUtf8()).jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, fields["p_before"])
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun anyoneReadsTheGalleryAndOnlyItsVisiblePostsComeBack() = runBlocking {
        val post = "22222222-2222-4222-8222-222222222222"
        answer("""[
            {"id":"$post","title":"Golden","author_name":"Ana","link_id":"$link","before_image":"$user/a.jpg","after_image":"$user/b.webp","likes":3,"liked":false,"mine":false,"hidden":false},
            {"id":"$post","title":"Own hidden","author_name":"Ana","link_id":"$link","before_image":"$user/a.jpg","after_image":"$user/b.jpg","likes":0,"liked":false,"mine":true,"hidden":true},
            {"id":"$post","title":"Odd path","author_name":"Ana","link_id":"$link","before_image":"../x.jpg","after_image":"$user/b.jpg","likes":0,"liked":false,"mine":false,"hidden":false}
        ]""")
        val posts = backend(signedIn = false).posts(GallerySort.NEW, offset = 30, limit = 30)
        assertEquals(listOf("Golden"), posts.map { it.title })
        assertEquals(server.url("/").toString().trimEnd('/') + "/storage/v1/object/public/gallery-images/$user/b.webp", posts.single().afterUrl)
        assertEquals(3, posts.single().likes)
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/gallery_list", request.path)
        assertEquals(null, request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(JsonPrimitive("new"), body["p_sort"])
        assertEquals(JsonPrimitive(30), body["p_offset"])
    }

    @Test fun aLikeGoesAsTheAccountAndReturnsTheCount() = runBlocking {
        answer("4")
        assertEquals(4, backend().like("22222222-2222-4222-8222-222222222222", like = true))
        val request = server.takeRequest()
        assertEquals("/rest/v1/rpc/gallery_like", request.path)
        assertEquals("Bearer session", request.getHeader("Authorization"))
        assertEquals(Reason.NO_ACCOUNT, failure { backend(signedIn = false).like("22222222-2222-4222-8222-222222222222", like = true) }.reason)
    }
}
