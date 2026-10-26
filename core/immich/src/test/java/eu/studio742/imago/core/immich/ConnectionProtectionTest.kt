package eu.studio742.imago.core.immich

import eu.studio742.imago.core.model.ConnectionHeader
import eu.studio742.imago.core.model.ConnectionProfile
import eu.studio742.imago.core.model.ImmichConnection
import eu.studio742.imago.core.model.LibrarySource
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.diagnostics.DiagnosticAccess
import eu.studio742.imago.core.model.diagnostics.DiagnosticStore
import eu.studio742.imago.core.model.diagnostics.DiagnosticTransport
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A proxy or a certificate between the app and Immich has to read as that, and not as "wrong key"
 * or "unreachable": the person has a different thing to fix. And what the support report says about
 * it is a closed set of values, never an address, a key or a header.
 */
class ConnectionProtectionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val registry = ConnectionProfileRegistry()
    private val key = "private-api-key-never-write-this"
    private val headerValue = "private-header-value-never-write-this"

    private lateinit var serverTls: HandshakeCertificates
    private lateinit var trustingClient: OkHttpClient
    private lateinit var front: MockWebServer
    private lateinit var elsewhere: MockWebServer
    private lateinit var store: DiagnosticStore

    @Before
    fun setUp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        serverTls = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        trustingClient = OkHttpClient.Builder()
            .withConnectionProfiles(registry, clientTls.trustManager)
            .build()
        front = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        elsewhere = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        LocalDiagnostics.transport = { DiagnosticTransport.UNKNOWN }
        store = DiagnosticStore(temporary.newFolder())
        LocalDiagnostics.start(store, emptyMap())
    }

    @After
    fun tearDown() {
        LocalDiagnostics.stop()
        ImmichHttp.profiles.replaceAll(emptyList())
        front.shutdown(); elsewhere.shutdown()
    }

    private fun connection() = ImmichConnection(front.url("/").toString(), key)
    private fun report() = store.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()).readText()

    private suspend fun failureOf(client: OkHttpClient = trustingClient, call: suspend OkHttpImmichApi.() -> Unit): UserMessage? =
        (runCatching { OkHttpImmichApi(client).call() }.exceptionOrNull() as? ImmichApiException)?.userMessage

    private fun assertNoSecrets(log: String) =
        listOf(key, headerValue, "localhost", "127.0.0.1", "x-api-key", "CF-Access", "private-body").forEach {
            assertFalse("The report must not contain \"$it\"", log.contains(it, ignoreCase = true))
        }

    @Test
    fun aLoginPageOnAnotherHostIsAProtectionNotAnInvalidResponse() = runTest {
        front.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/login").toString()))
        elsewhere.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>private-body sign in</html>"))
        assertEquals(UserMessage.IMMICH_PROTECTED, failureOf { validateConnection(connection()) })
        val log = report()
        assertTrue(log, log.contains("protection=PROXY_AUTH"))
        assertNoSecrets(log)
    }

    @Test
    fun aPageWhereTheApiSpeaksJsonIsAProtection() = runTest {
        front.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html>private-body</html>"))
        assertEquals(UserMessage.IMMICH_PROTECTED, failureOf { validateConnection(connection()) })
    }

    @Test
    fun aRefusalThatIsNotImmichsIsAProtectionButImmichsOwnIsNot() = runTest {
        front.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody("<html>private-body Forbidden</html>"))
        assertEquals(UserMessage.IMMICH_PROTECTED, failureOf { currentUserId(connection()) })

        front.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"private\""))
        assertEquals(UserMessage.IMMICH_PROTECTED, failureOf { currentUserId(connection()) })

        front.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"message":"Invalid API key","statusCode":401}"""))
        assertEquals(UserMessage.IMMICH_KEY_REJECTED, failureOf { currentUserId(connection()) })
    }

    @Test
    fun aGatewayErrorIsNotAProtection() = runTest {
        front.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>Bad Gateway</html>"))
        assertEquals(UserMessage.IMMICH_SERVER_ERROR, failureOf { currentUserId(connection()) })
        assertFalse(report().contains("protection="))
    }

    @Test
    fun theProbeSeesALoginPageAsNoAnswerAndSaysWhy() = runTest {
        front.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>sign in</html>"))
        assertFalse(OkHttpImmichApi(trustingClient).ping(front.url("/").toString()))
        assertTrue(report().contains("PING INVALID_RESPONSE"))
        assertTrue(report().contains("protection=PROXY_AUTH"))
    }

    @Test
    fun aProxyThatAnswersWithAPageAboutTheCertificateIsNamedAsSuch() = runTest {
        // What nginx sends when `ssl_verify_client on` finds no certificate, and its 496.
        front.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "text/html")
            .setBody("<html><center><h1>400 Bad Request</h1></center>400 No required SSL certificate was sent</html>"))
        assertEquals(UserMessage.IMMICH_CERTIFICATE_REQUIRED, failureOf { validateConnection(connection()) })
        front.enqueue(MockResponse().setResponseCode(496).setHeader("Content-Type", "text/html").setBody("<html>private-body</html>"))
        assertEquals(UserMessage.IMMICH_CERTIFICATE_REQUIRED, failureOf { validateConnection(connection()) })
        val log = report()
        assertTrue(log, log.contains("protection=CLIENT_CERTIFICATE_REQUIRED"))
        assertNoSecrets(log)
    }

    @Test
    fun aCertificateTheDeviceDoesNotTrustIsNamedAsSuch() = runTest {
        // The default trust store does not know the test server's certificate.
        assertEquals(UserMessage.IMMICH_CERTIFICATE_UNTRUSTED, failureOf(OkHttpClient()) { validateConnection(connection()) })
        val log = report()
        assertTrue(log, log.contains("protection=UNTRUSTED_CERTIFICATE"))
        assertNoSecrets(log)
    }

    @Test
    fun theReportSaysThereAreHeadersButNotWhatTheyAre() = runTest {
        val address = front.url("/").toString()
        // The report reads the configuration the app's own client reads.
        ImmichHttp.profiles.replaceAll(listOf(LibrarySource("lib", "Home", listOf(address), "user", key, profiles = mapOf(
            canonicalServerUrl(address) to ConnectionProfile(listOf(ConnectionHeader("CF-Access-Client-Secret", headerValue)))))))
        front.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "text/html").setBody("<html>private-body</html>"))
        assertEquals(UserMessage.IMMICH_PROTECTED, failureOf { currentUserId(connection()) })
        val log = report()
        assertTrue(log, log.contains("headers=custom"))
        assertNoSecrets(log)
    }

    @Test
    fun anAddressWithoutHeadersSaysNothingAboutThem() = runTest {
        front.enqueue(MockResponse().setBody("""{"major":3,"minor":3,"patch":5}"""))
        front.enqueue(MockResponse().setBody("{}"))
        runCatching { OkHttpImmichApi(trustingClient).validateConnection(connection()) }
        assertFalse(report().contains("headers="))
    }
}
