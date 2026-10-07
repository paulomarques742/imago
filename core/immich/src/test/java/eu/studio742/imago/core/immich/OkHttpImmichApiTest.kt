package eu.studio742.imago.core.immich

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.RECENT_FILTER_DAYS
import java.io.File
import java.time.LocalDate

class OkHttpImmichApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OkHttpImmichApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = OkHttpImmichApi(OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun readsAuthenticatedAccountIdentity() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"account-a"}"""))
        assertEquals("account-a", api.currentUserId(connection()))
        val request = server.takeRequest()
        assertEquals("/api/users/me", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
    }

    @Test
    fun pingsWithoutApiKeyAndTreatsSilenceAsUnavailable() = runTest {
        server.enqueue(MockResponse().setBody("""{"res":"pong"}"""))
        assertTrue(api.ping(server.url("/api/").toString()))
        val request = server.takeRequest()
        assertEquals("/api/server/ping", request.path)
        assertNull(request.getHeader("x-api-key"))

        server.enqueue(MockResponse().setResponseCode(502))
        assertFalse(api.ping(server.url("/").toString()))
        server.enqueue(MockResponse().setBody("""{"res":"pong"}""").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(api.ping(server.url("/").toString(), timeoutMillis = 200))
        assertFalse(api.ping("not a url"))
    }

    @Test
    fun acceptsImmich263AndValidatesAuthenticatedUser() = runTest {
        server.enqueue(MockResponse().setBody("""{"major":2,"minor":6,"patch":3}"""))
        server.enqueue(MockResponse().setBody("{}"))

        val version = api.validateConnection(connection())

        assertEquals("2.6.3", version.toString())
        assertEquals("/api/server/version", server.takeRequest().path)
        val userRequest = server.takeRequest()
        assertEquals("/api/users/me", userRequest.path)
        assertEquals("secret", userRequest.getHeader("x-api-key"))
    }

    @Test
    fun rejectsVersionsBefore260WithoutSendingApiKey() = runTest {
        server.enqueue(MockResponse().setBody("""{"major":2,"minor":5,"patch":6}"""))

        val error = runCatching { api.validateConnection(connection()) }.exceptionOrNull()

        assertTrue(error is ImmichApiException.UnsupportedVersion)
        assertNull(server.takeRequest().getHeader("x-api-key"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun searchUsesOfficialPaginationShape() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"albums":{"count":0,"facets":[],"items":[],"nextPage":null,"total":0},"assets":{"count":2,"facets":[],"items":[{"id":"a1","originalFileName":"photo.jpg","fileCreatedAt":"2026-01-01T10:00:00Z","localDateTime":"2026-01-01T10:00:00Z","width":4000,"height":3000,"isFavorite":false,"isEdited":false,"type":"IMAGE"},{"id":"v1","originalFileName":"video.mp4","fileCreatedAt":"2026-01-01T10:00:00Z","localDateTime":"2026-01-01T10:00:00Z","width":1920,"height":1080,"isFavorite":false,"isEdited":false,"type":"VIDEO"}],"nextPage":"2","total":2}}""",
            ),
        )

        val page = api.searchAssets(connection(), 1, 100, LibraryFilter.ALL)

        // The library shows photos and videos: both have to survive the search, and the request
        // cannot filter by type, or `nextPage` counts pages the grid does not see.
        assertEquals(listOf("a1", "v1"), page.items.map { it.id })
        assertEquals(listOf(AssetType.IMAGE, AssetType.VIDEO), page.items.map { it.type })
        assertEquals(2, page.nextPage)
        val request = server.takeRequest()
        assertEquals("/api/search/metadata", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
        assertFalse(request.body.readUtf8().contains("\"type\""))
    }

    @Test
    fun publicVersionRequestDoesNotLeakApiKey() = runTest {
        server.enqueue(MockResponse().setBody("""{"major":3,"minor":0,"patch":9,"prerelease":null}"""))

        runCatching { api.validateConnection(connection()) }

        assertNull(server.takeRequest().getHeader("x-api-key"))
    }

    @Test
    fun exportOnImmich26SendsLegacyFieldsAndStacksUnderOriginal() = runTest {
        server.enqueue(MockResponse().setBody("""{"major":2,"minor":6,"patch":3}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"derived-1","status":"created"}"""))
        server.enqueue(
            MockResponse().setResponseCode(201)
                .setBody("""{"id":"stack-1","primaryAssetId":"original-1","assets":[]}"""),
        )

        val result = api.exportEditedAsset(
            connection(),
            originalAssetId = "original-1",
            jpeg = jpegFile(),
            fileName = "photo_ImmichRoom.jpg",
            fileCreatedAt = "2026-01-01T10:00:00Z",
        )

        server.takeRequest()
        server.takeRequest()
        val upload = server.takeRequest()
        val multipart = upload.body.readUtf8()
        assertEquals("/api/assets", upload.path)
        assertEquals("secret", upload.getHeader("x-api-key"))
        assertTrue(multipart.contains("name=\"deviceAssetId\""))
        assertTrue(multipart.contains("name=\"deviceId\""))
        assertTrue(multipart.contains("filename=\"photo_ImmichRoom.jpg\""))
        val stack = server.takeRequest()
        assertEquals("/api/stacks", stack.path)
        assertEquals("""{"assetIds":["original-1","derived-1"]}""", stack.body.readUtf8())
        assertTrue(result.stackedWithOriginal)
        assertFalse(result.stackingFailed)
    }

    @Test
    fun exportOnImmich3OmitsLegacyFieldsAndDoesNotStackDuplicate() = runTest {
        server.enqueue(MockResponse().setBody("""{"major":3,"minor":1,"patch":0}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"id":"existing-1","status":"duplicate"}"""))

        val result = api.exportEditedAsset(
            connection(),
            originalAssetId = "original-1",
            jpeg = jpegFile(),
            fileName = "photo_ImmichRoom.jpg",
            fileCreatedAt = "2026-01-01T10:00:00Z",
        )

        server.takeRequest()
        server.takeRequest()
        val multipart = server.takeRequest().body.readUtf8()
        assertFalse(multipart.contains("name=\"deviceAssetId\""))
        assertFalse(multipart.contains("name=\"deviceId\""))
        assertEquals("duplicate", result.status)
        assertFalse(result.stackedWithOriginal)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun uploadSendsTheFileWithItsOwnTypeAndReportsDuplicates() = runTest {
        server.enqueue(MockResponse().setBody("""{"major":3,"minor":1,"patch":0}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"id":"existing-1","status":"duplicate"}"""))

        val result = api.uploadAsset(
            connection(),
            file = jpegFile(),
            fileName = "VID_20260101.mp4",
            mimeType = "video/mp4",
            fileCreatedAt = "2026-01-01T10:00:00Z",
        )

        server.takeRequest()
        server.takeRequest()
        val upload = server.takeRequest()
        val multipart = upload.body.readUtf8()
        assertEquals("/api/assets", upload.path)
        assertTrue(multipart.contains("filename=\"VID_20260101.mp4\""))
        assertTrue(multipart.contains("Content-Type: video/mp4"))
        assertTrue(multipart.contains("2026-01-01T10:00:00Z"))
        assertEquals("existing-1", result.assetId)
        assertEquals("duplicate", result.status)
        // Only exporting an edit stacks; a standalone upload asks for no stack.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun uploadToleratesAMalformedTypeFromAnotherApp() = runTest {
        server.enqueue(MockResponse().setBody("""{"major":3,"minor":1,"patch":0}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"new-1","status":"created"}"""))

        val result = api.uploadAsset(connection(), jpegFile(), "IMG_1.heic", "not a type", "2026-01-01T10:00:00Z")

        server.takeRequest()
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("Content-Type: application/octet-stream"))
        assertEquals("created", result.status)
    }

    @Test
    fun downloadsOriginalWithAuthentication() = runTest {
        server.enqueue(MockResponse().setBody("original-bytes"))
        val destination = File.createTempFile("imago-original", ".jpg").apply { deleteOnExit() }

        api.downloadOriginal(connection(), "asset-1", destination)

        val request = server.takeRequest()
        assertEquals("/api/assets/asset-1/original", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
        assertEquals("original-bytes", destination.readText())
    }

    @Test
    fun loadsOwnedAndSharedAlbumsWithoutDuplicates() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","albumName":"Owned","description":"","albumThumbnailAssetId":"t1","assetCount":3,"shared":false}]""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"a1","albumName":"Owned","description":"","albumThumbnailAssetId":"t1","assetCount":3,"shared":false},{"id":"a2","albumName":"Shared","description":"Trip","albumThumbnailAssetId":null,"assetCount":8,"shared":true}]""",
            ),
        )

        val albums = api.getAlbums(connection())

        assertEquals(listOf("a1", "a2"), albums.map { it.id })
        assertEquals("/api/albums", server.takeRequest().path)
        assertEquals("/api/albums?shared=true", server.takeRequest().path)
    }

    @Test
    fun loadsTimelineMonthsWithOfficialQuery() = runTest {
        server.enqueue(MockResponse().setBody("""[{"timeBucket":"2026-08-01","count":42}]"""))

        val months = api.getTimeBuckets(connection())

        assertEquals("2026-08-01", months.single().month)
        assertEquals(42, months.single().assetCount)
        // Lowercase because that is how the contract's `AssetOrder` writes them, and the server
        // rejects anything else with 400. While this said "DESC", reading the months always failed
        // and nobody noticed: the repository silently fell back to the local catalogue's months.
        assertEquals("/api/timeline/buckets?order=desc&withStacked=true", server.takeRequest().path)
    }

    @Test
    fun loadsOneMonthOfAssetsInColumns() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":["a1","a2"],"fileCreatedAt":["2026-08-02T10:00:00.000Z","2026-08-01T09:00:00.000Z"],
                   "isFavorite":[true,false],"isImage":[true,false],"isTrashed":[false,true],
                   "duration":[null,15187],"ratio":[1.5,1.0],"localOffsetHours":[1.0,1.0],
                   "visibility":["timeline","timeline"]}""".trimIndent(),
            ),
        )

        val assets = api.getTimeBucketAssets(connection(), "2026-08-01")

        // The duration comes in raw milliseconds — declaring it as text made every month with a
        // video fail, and only those.

        // The second one is in the trash: the bucket brings it, the grid does not show it.
        assertEquals(listOf("a1"), assets.map { it.id })
        assertEquals(true, assets.single().isFavorite)
        // The local time comes from the UTC instant plus the offset the bucket declares.
        assertEquals("2026-08-02T11:00", assets.single().localDateTime)
        assertEquals(
            "/api/timeline/bucket?timeBucket=2026-08-01&order=desc&withStacked=true",
            server.takeRequest().path,
        )
    }

    @Test
    fun searchCanFilterByMonth() = runTest {
        server.enqueue(MockResponse().setBody(EMPTY_SEARCH_RESPONSE))

        api.searchAssets(connection(), 1, 100, LibraryFilter.ALL, month = "2026-08-01")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"takenAfter\":\"2026-08-01T00:00:00.000Z\""))
        assertTrue(body.contains("\"takenBefore\":\"2026-09-01T00:00:00.000Z\""))
    }

    @Test
    fun searchCanFilterByAlbum() = runTest {
        server.enqueue(MockResponse().setBody(EMPTY_SEARCH_RESPONSE))

        api.searchAssets(connection(), 1, 100, LibraryFilter.ALL, albumId = "album-1")

        assertTrue(server.takeRequest().body.readUtf8().contains("\"albumIds\":[\"album-1\"]"))
    }

    @Test
    fun searchCanFilterByFileName() = runTest {
        server.enqueue(MockResponse().setBody(EMPTY_SEARCH_RESPONSE))

        api.searchAssets(connection(), 1, 100, LibraryFilter.ALL, query = "praia")

        assertTrue(server.takeRequest().body.readUtf8().contains("\"originalFileName\":\"praia\""))
    }

    /** The "Recent" chip has to become a window in the request, not just a local filter. */
    @Test
    fun recentFilterAsksTheServerForAWindow() = runTest {
        server.enqueue(MockResponse().setBody(EMPTY_SEARCH_RESPONSE))

        api.searchAssets(connection(), 1, 100, LibraryFilter.RECENT)

        val body = server.takeRequest().body.readUtf8()
        val expected = LocalDate.now().minusDays(RECENT_FILTER_DAYS)
        assertTrue(body.contains("\"takenAfter\":\"${expected}T00:00:00.000Z\""))
        assertFalse(body.contains("takenBefore"))
    }

    /** A chosen month is more specific than "recent" and has to win. */
    @Test
    fun monthWinsOverRecentWindow() = runTest {
        server.enqueue(MockResponse().setBody(EMPTY_SEARCH_RESPONSE))

        api.searchAssets(connection(), 1, 100, LibraryFilter.RECENT, month = "2026-08-01")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"takenAfter\":\"2026-08-01T00:00:00.000Z\""))
        assertTrue(body.contains("\"takenBefore\":\"2026-09-01T00:00:00.000Z\""))
    }

    @Test
    fun readsExifFromAssetDetail() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"a1","originalFileName":"praia.jpg","fileCreatedAt":"2024-05-16T18:42:00Z","localDateTime":"2024-05-16T18:42:00Z","width":4000,"height":3000,"isFavorite":true,"isEdited":false,"type":"IMAGE","exifInfo":{"fNumber":8.0,"exposureTime":"1/125","iso":100,"focalLength":24.0,"make":"Apple","model":"iPhone 15 Pro","lensModel":"","description":"Praia do Amado"}}""",
            ),
        )

        val detail = api.getAssetDetail(connection(), "a1")

        assertEquals("/api/assets/a1", server.takeRequest().path)
        assertEquals("praia.jpg", detail.asset.originalFileName)
        assertTrue(detail.asset.isFavorite)
        assertEquals(8.0f, detail.exif.fNumber)
        assertEquals("1/125", detail.exif.exposureTime)
        assertEquals(100, detail.exif.iso)
        assertEquals("iPhone 15 Pro", detail.exif.model)
        assertEquals("Praia do Amado", detail.exif.description)
        // A blank EXIF is not a value: it would be an empty bar in the detail screen's strip.
        assertNull(detail.exif.lensModel)
    }

    /** A photo without metadata is normal — it must not crash the detail screen. */
    @Test
    fun assetDetailToleratesMissingExif() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"a1","originalFileName":"scan.jpg","fileCreatedAt":"2024-05-16T18:42:00Z","type":"IMAGE"}""",
            ),
        )

        val detail = api.getAssetDetail(connection(), "a1")

        assertNull(detail.exif.fNumber)
        assertNull(detail.exif.model)
        assertEquals("scan.jpg", detail.asset.originalFileName)
    }

    @Test
    fun setsFavoriteWithPut() = runTest {
        server.enqueue(MockResponse().setBody("{}"))

        api.setFavorite(connection(), "a1", true)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/assets/a1", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
        // The body carries only the field that changes: nothing else in the asset can be touched by mistake.
        assertEquals("""{"isFavorite":true}""", request.body.readUtf8())
    }

    @Test
    fun deleteMovesToTrashByDefault() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.deleteAsset(connection(), "a1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/assets", request.path)
        assertEquals("""{"ids":["a1"],"force":false}""", request.body.readUtf8())
    }

    @Test
    fun rejectedApiKeyOnWriteSurfacesAsAuthenticationError() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid API key"}"""))

        val error = runCatching { api.setFavorite(connection(), "a1", true) }.exceptionOrNull()

        assertTrue(error is ImmichApiException.Authentication)
    }

    @Test
    fun forbiddenWriteNamesTheMissingPermissionInsteadOfRejectingTheKey() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"message":"Missing required permission: asset.delete"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(403))

        val delete = runCatching { api.deleteAsset(connection(), "a1") }.exceptionOrNull()
        val favorite = runCatching { api.setFavorite(connection(), "a1", true) }.exceptionOrNull()

        assertEquals(listOf("asset.delete"), (delete as ImmichApiException.MissingPermission).permissions)
        assertEquals(listOf("asset.update"), (favorite as ImmichApiException.MissingPermission).permissions)
        assertEquals(eu.studio742.imago.core.model.UserMessage.IMMICH_PERMISSION_MISSING, favorite.userMessage)
        assertEquals(listOf("asset.update"), favorite.args)
    }

    @Test
    fun forbiddenAlbumsNameAlbumRead() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val error = runCatching { api.getAlbums(connection()) }.exceptionOrNull()

        assertEquals(listOf("album.read"), (error as ImmichApiException.MissingPermission).permissions)
    }

    @Test
    fun readsTheKeysOwnPermissions() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"k","name":"IMAGO","createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-01T00:00:00.000Z","permissions":["asset.read","asset.view"]}""",
            ),
        )

        assertEquals(setOf("asset.read", "asset.view"), api.keyPermissions(connection()))
        val request = server.takeRequest()
        assertEquals("/api/api-keys/me", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
    }

    @Test
    fun requirePermissionFailsBeforeTheRequestOnlyWhenTheKeyLacksIt() = runTest {
        fun keyWith(vararg permissions: String) = MockResponse().setBody(
            """{"permissions":[${permissions.joinToString { "\"$it\"" }}]}""",
        )
        server.enqueue(keyWith("asset.read", "asset.update"))
        val missing = runCatching { api.requirePermission(connection(), "asset.delete") }.exceptionOrNull()
        assertEquals(listOf("asset.delete"), (missing as ImmichApiException.MissingPermission).permissions)

        server.enqueue(keyWith("asset.delete"))
        api.requirePermission(connection(), "asset.delete")

        server.enqueue(keyWith("all"))
        api.requirePermission(connection(), "asset.delete")

        // Without knowing, it lets the request itself answer.
        server.enqueue(MockResponse().setResponseCode(500))
        api.requirePermission(connection(), "asset.delete")
    }

    @Test
    fun serverErrorOnDeleteSurfacesWithStatus() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = runCatching { api.deleteAsset(connection(), "a1") }.exceptionOrNull()

        assertEquals(500, (error as ImmichApiException.Server).status)
    }

    private fun connection() = ImmichConnection(
        serverUrl = server.url("/").toString(),
        apiKey = "secret",
    )

    private fun jpegFile() = File.createTempFile("imago-test", ".jpg").apply {
        writeBytes(byteArrayOf(1, 2, 3))
        deleteOnExit()
    }

    private companion object {
        const val EMPTY_SEARCH_RESPONSE =
            """{"albums":{"count":0,"facets":[],"items":[],"nextPage":null,"total":0},"assets":{"count":0,"facets":[],"items":[],"nextPage":null,"total":0}}"""
    }
}
