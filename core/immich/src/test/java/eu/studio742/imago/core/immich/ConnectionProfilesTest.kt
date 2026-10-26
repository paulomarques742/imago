package eu.studio742.imago.core.immich

import eu.studio742.imago.core.model.ConnectionHeader
import eu.studio742.imago.core.model.ConnectionProfile
import eu.studio742.imago.core.model.LibrarySource
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionProfilesTest {
    private val registry = ConnectionProfileRegistry()
    private val cloudflare = ConnectionProfile(listOf(
        ConnectionHeader("CF-Access-Client-Id", "id.access"), ConnectionHeader("CF-Access-Client-Secret", "s3cret")))

    private lateinit var client: OkHttpClient
    private lateinit var protectedServer: MockWebServer
    private lateinit var otherServer: MockWebServer

    @Before
    fun setUp() {
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(localhost).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        client = OkHttpClient.Builder()
            .withConnectionProfiles(registry, clientTls.trustManager)
            .build()
        protectedServer = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        otherServer = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
    }

    @After
    fun tearDown() { protectedServer.shutdown(); otherServer.shutdown() }

    private fun library(address: String, profile: ConnectionProfile) =
        LibrarySource("lib", "Home", listOf(address), "user", "key", profiles = mapOf(canonicalServerUrl(address) to profile))

    private fun pong() = MockResponse().setBody("""{"res":"pong"}""")

    @Test
    fun headersReachTheAddressThatNeedsThem() = runTest {
        registry.replaceAll(listOf(library(protectedServer.url("/").toString(), cloudflare)))
        protectedServer.enqueue(pong())
        assertTrue(OkHttpImmichApi(client).ping(protectedServer.url("/").toString()))
        val request = protectedServer.takeRequest()
        assertEquals("id.access", request.getHeader("CF-Access-Client-Id"))
        assertEquals("s3cret", request.getHeader("CF-Access-Client-Secret"))
    }

    @Test
    fun anotherAddressDoesNotGetThem() = runTest {
        registry.replaceAll(listOf(library(protectedServer.url("/").toString(), cloudflare)))
        otherServer.enqueue(pong())
        assertTrue(OkHttpImmichApi(client).ping(otherServer.url("/").toString()))
        assertNull(otherServer.takeRequest().getHeader("CF-Access-Client-Id"))
    }

    @Test
    fun aRedirectToAnotherHostDoesNotCarryThem() = runTest {
        registry.replaceAll(listOf(library(protectedServer.url("/").toString(), cloudflare)))
        protectedServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherServer.url("/api/server/ping").toString()))
        otherServer.enqueue(pong())
        assertTrue(OkHttpImmichApi(client).ping(protectedServer.url("/").toString()))
        assertEquals("id.access", protectedServer.takeRequest().getHeader("CF-Access-Client-Id"))
        assertNull("A login page on another host must not receive the secret", otherServer.takeRequest().getHeader("CF-Access-Client-Id"))
    }

    @Test
    fun anAddressIsTestedWithItsHeadersBeforeItIsSaved() = runTest {
        protectedServer.enqueue(pong())
        registry.staged(protectedServer.url("/").toString(), cloudflare) {
            assertTrue(OkHttpImmichApi(client).ping(protectedServer.url("/").toString()))
        }
        assertEquals("id.access", protectedServer.takeRequest().getHeader("CF-Access-Client-Id"))
        // Once the attempt is over, nothing of it is left behind.
        protectedServer.enqueue(pong())
        OkHttpImmichApi(client).ping(protectedServer.url("/").toString())
        assertNull(protectedServer.takeRequest().getHeader("CF-Access-Client-Id"))
    }

    @Test
    fun theLongestPathPrefixWins() {
        val root = ConnectionProfile(listOf(ConnectionHeader("X-Who", "root")))
        val photos = ConnectionProfile(listOf(ConnectionHeader("X-Who", "photos")))
        registry.replaceAll(listOf(
            LibrarySource("a", "A", listOf("https://h.test"), profiles = mapOf("https://h.test" to root)),
            LibrarySource("b", "B", listOf("https://h.test/photos"), profiles = mapOf("https://h.test/photos" to photos)),
        ))
        fun who(path: String) =
            registry.headersFor(HttpUrl.Builder().scheme("https").host("h.test").encodedPath(path).build()).single().value
        assertEquals("photos", who("/photos/api/users/me"))
        assertEquals("root", who("/api/users/me"))
        assertEquals("root", who("/photoshop/x"))
    }

    @Test
    fun plainHttpNeverGetsHeaders() = runTest {
        val plain = MockWebServer().apply { start() }
        try {
            val address = plain.url("/").toString()
            registry.replaceAll(listOf(library(address, cloudflare)))
            plain.enqueue(pong())
            assertTrue(OkHttpImmichApi(client).ping(address))
            assertNull(plain.takeRequest().getHeader("CF-Access-Client-Id"))
        } finally { plain.shutdown() }
    }
}
