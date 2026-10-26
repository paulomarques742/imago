package eu.studio742.imago.core.immich

import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.model.diagnostics.*
import eu.studio742.imago.core.immich.generated.ImmichContract
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.UnknownHostException

class ImmichDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var store: DiagnosticStore
    private val secret = "private-api-key-never-write-this"
    @Before fun setUp() {
        LocalDiagnostics.transport = { DiagnosticTransport.UNKNOWN }
        server = MockWebServer().apply { start() }
        store = DiagnosticStore(temporary.newFolder())
        LocalDiagnostics.start(store, emptyMap())
    }
    @After fun tearDown() {
        LocalDiagnostics.stop()
        LocalDiagnostics.transport = { DiagnosticTransport.UNKNOWN }
        server.shutdown()
    }
    private fun report() = store.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()).readText()
    private fun connection() = ImmichConnection(server.url("/private-server-prefix/").toString(), secret)

    @Test fun recordsHttpFailuresAndVersionsWithoutServerOrKeyOrResponseBody() = runTest {
        val api = OkHttpImmichApi()
        server.enqueue(MockResponse().setBody("""{"major":3,"minor":3,"patch":5}"""))
        server.enqueue(MockResponse().setBody("{}"))
        api.validateConnection(connection())
        server.enqueue(MockResponse().setResponseCode(403).setBody("https://server.private.invalid $secret private-album-name"))
        val failure = runCatching { api.getAlbums(connection()) }.exceptionOrNull()
        assertNotNull(failure)
        val log = report()
        assertTrue(log.contains("immich=3.3.5"))
        assertTrue(log.contains("ALBUMS HTTP_ERROR"))
        assertTrue(log.contains("http=403"))
        listOf(secret, "private-album-name", "server.private.invalid", "localhost", "private-server-prefix", "x-api-key").forEach {
            assertFalse(it, log.contains(it))
        }
    }

    @Test fun separatesMalformedResponsesFromHttpSuccessWithoutPayloadOrQuery() = runTest {
        server.enqueue(MockResponse().setBody("""{"private-photo":"$secret"}"""))
        val failure = runCatching { OkHttpImmichApi().searchAssets(connection(), 1, 50, LibraryFilter.ALL, null, "private-album-id", "private-filename.jpg") }.exceptionOrNull()
        assertNotNull(failure)
        val log = report()
        assertTrue(log.contains("SEARCH SUCCESS"))
        assertTrue(log.contains("SEARCH INVALID_RESPONSE"))
        listOf(secret, "private-photo", "private-album-id", "private-filename.jpg").forEach { assertFalse(log.contains(it)) }
    }

    @Test fun recordsDnsFailureWithoutExceptionTextOrHost() = runTest {
        val client = OkHttpClient.Builder().dns { throw UnknownHostException("private-dns-name.invalid $secret") }.build()
        runCatching { OkHttpImmichApi(client).getAlbums(ImmichConnection("https://private-dns-name.invalid", secret)) }
        val log = report()
        assertTrue(log.contains("ALBUMS DNS"))
        assertFalse(log.contains("private-dns-name"))
        assertFalse(log.contains(secret))
    }

    @Test fun recordsFailedEndpointProbesAndTimeoutsBeforeExportingOffline() = runTest {
        val api = OkHttpImmichApi()
        server.enqueue(MockResponse().setResponseCode(502).setBody("private-proxy-body"))
        assertFalse(api.ping(connection().serverUrl, 500))
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        assertFalse(api.ping(connection().serverUrl, 100))
        val requests = server.requestCount
        val log = report()
        assertEquals(requests, server.requestCount)
        assertTrue(log.contains("PING HTTP_ERROR"))
        assertTrue(log.contains("http=502"))
        assertTrue(log.contains("PING TIMEOUT"))
        assertFalse(log.contains("private-proxy-body"))
        assertFalse(log.contains("private-server-prefix"))
    }

    @Test fun classifiesContractPathsWithoutRegexIncludingPrivatePrefixesAndEncodedIdentifiers() {
        val prefix = "/private/base/${ImmichContract.API_BASE.trim('/')}"
        assertEquals(DiagnosticOperation.SERVER_VERSION, OkHttpImmichApi.diagnosticOperationForPath(prefix + ImmichContract.GET_SERVER_VERSION))
        assertEquals(DiagnosticOperation.CURRENT_USER, OkHttpImmichApi.diagnosticOperationForPath(prefix + ImmichContract.GET_CURRENT_USER))
        assertEquals(DiagnosticOperation.THUMBNAIL, OkHttpImmichApi.diagnosticOperationForPath(prefix + ImmichContract.VIEW_ASSET.replace("{id}", "private%2Fidentifier")))
        assertEquals(DiagnosticOperation.DOWNLOAD, OkHttpImmichApi.diagnosticOperationForPath(prefix + ImmichContract.DOWNLOAD_ASSET.replace("{id}", "private-id")))
        assertEquals(DiagnosticOperation.OTHER, OkHttpImmichApi.diagnosticOperationForPath("/not-an-immich-operation"))
    }

    @Test fun brokenDiagnosticContextCannotPreventConnectionOrChangeAuthenticationErrors() = runTest {
        LocalDiagnostics.transport = { throw IllegalStateException("Private diagnostic context failure") }
        val api = OkHttpImmichApi()
        server.enqueue(MockResponse().setBody("""{"major":3,"minor":3,"patch":5}"""))
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals("3.3.5", api.validateConnection(connection()).toString())
        assertEquals(2, server.requestCount)
        assertTrue(LocalDiagnostics.storageFailed)
        server.enqueue(MockResponse().setResponseCode(401).setBody("private-authentication-response"))
        val failure = runCatching { api.getAlbums(connection()) }.exceptionOrNull()
        assertTrue(failure is ImmichApiException.Authentication)
        assertEquals(3, server.requestCount)
    }
}
