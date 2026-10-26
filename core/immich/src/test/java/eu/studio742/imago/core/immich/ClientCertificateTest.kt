package eu.studio742.imago.core.immich

import eu.studio742.imago.core.model.ClientCertificate
import eu.studio742.imago.core.model.ConnectionProfile
import eu.studio742.imago.core.model.LibrarySource
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import kotlinx.coroutines.test.runTest
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.Base64

/**
 * mTLS: a server that asks for a certificate gets the one imported for its address, and nothing
 * else gets it. The servers here are real TLS servers that refuse a client without one.
 */
class ClientCertificateTest {
    private val password = "certificate-password-never-write-this"
    private val registry = ConnectionProfileRegistry()

    private lateinit var clientCertificate: HeldCertificate
    private lateinit var serverCertificate: HeldCertificate
    private lateinit var client: OkHttpClient
    private lateinit var mtls: MockWebServer
    private lateinit var other: MockWebServer

    @Before
    fun setUp() {
        serverCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        clientCertificate = HeldCertificate.Builder().commonName("Paulo's phone").duration(400, java.util.concurrent.TimeUnit.DAYS).build()
        val serverSide = HandshakeCertificates.Builder().heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCertificate.certificate).build()
        val clientSide = HandshakeCertificates.Builder().addTrustedCertificate(serverCertificate.certificate).build()
        // TLS 1.2: the server's refusal of a client without a certificate arrives during the handshake.
        client = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build()))
            .withConnectionProfiles(registry, clientSide.trustManager)
            .build()
        mtls = MockWebServer().apply { useHttps(serverSide.sslSocketFactory(), false); requireClientAuth(); start() }
        other = MockWebServer().apply { useHttps(serverSide.sslSocketFactory(), false); requireClientAuth(); start() }
    }

    @After
    fun tearDown() { mtls.shutdown(); other.shutdown() }

    private fun p12(certificate: HeldCertificate = clientCertificate, withKey: Boolean = true, password: String = this.password): ByteArray {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        if (withKey) store.setKeyEntry("client", certificate.keyPair.private, password.toCharArray(), arrayOf(certificate.certificate))
        else store.setCertificateEntry("client", certificate.certificate)
        return ByteArrayOutputStream().also { store.store(it, password.toCharArray()) }.toByteArray()
    }

    private fun imported() = ClientCertificates.import(p12(), password)
    private fun profile() = ConnectionProfile(certificate = imported())

    private fun library(server: MockWebServer, profile: ConnectionProfile) = server.url("/").toString().let { address ->
        LibrarySource("lib", "Home", listOf(address), "user", "key", profiles = mapOf(canonicalServerUrl(address) to profile))
    }

    private fun pong() = MockResponse().setBody("""{"res":"pong"}""")

    @Test
    fun theServerRefusesUsWithoutTheCertificateAndAcceptsWithIt() = runTest {
        val address = mtls.url("/").toString()
        assertFalse("Without a certificate the handshake has to fail", OkHttpImmichApi(client).ping(address))

        registry.replaceAll(listOf(library(mtls, profile())))
        mtls.enqueue(pong())
        assertTrue(OkHttpImmichApi(client).ping(address))
        assertEquals("/api/server/ping", mtls.takeRequest().path)
    }

    @Test
    fun theCertificateIsOfferedToItsOwnAddressOnly() = runTest {
        // Same host, another port: another address, which did not ask for this certificate.
        registry.replaceAll(listOf(library(mtls, profile())))
        assertFalse(OkHttpImmichApi(client).ping(other.url("/").toString()))
        assertNull(registry.clientKeyFor("localhost", other.port))
        assertNotNull(registry.clientKeyFor("localhost", mtls.port))
    }

    @Test
    fun anAddressIsTriedWithItsCertificateBeforeItIsSaved() = runTest {
        val address = mtls.url("/").toString()
        mtls.enqueue(pong())
        registry.staged(address, profile()) { assertTrue(OkHttpImmichApi(client).ping(address)) }
        assertNull("Nothing of the attempt stays behind", registry.clientKeyFor("localhost", mtls.port))
    }

    @Test
    fun theFileIsCheckedWhenItIsImported() {
        fun reason(block: () -> Unit) = (runCatching(block).exceptionOrNull() as? UserMessageException)?.userMessage

        assertEquals(UserMessage.CERTIFICATE_BAD_PASSWORD, reason { ClientCertificates.import(p12(), "another-password") })
        assertEquals(UserMessage.CERTIFICATE_UNREADABLE, reason { ClientCertificates.import("not a certificate".toByteArray(), password) })
        assertEquals(UserMessage.CERTIFICATE_NO_KEY, reason { ClientCertificates.import(p12(withKey = false), password) })
        val expired = HeldCertificate.Builder().commonName("Old").validityInterval(1_000L, 2_000L).build()
        assertEquals(UserMessage.CERTIFICATE_EXPIRED, reason { ClientCertificates.import(p12(expired), password) })
        assertNull(reason { ClientCertificates.import(p12(), password) })
    }

    @Test
    fun anExpiredCertificateIsNotAccepted() = runTest {
        val expired = HeldCertificate.Builder().commonName("Old").validityInterval(1_000L, 2_000L).build()
        val stored = ClientCertificate(Base64.getEncoder().encodeToString(p12(expired)), password)
        val failure = runCatching { registry.staged(mtls.url("/").toString(), ConnectionProfile(certificate = stored)) { } }.exceptionOrNull()
        assertEquals(UserMessage.CERTIFICATE_EXPIRED, (failure as? UserMessageException)?.userMessage)
    }

    @Test
    fun theSettingsCanShowWhoItIsForAndUntilWhen() {
        val info = ClientCertificates.inspect(imported())
        assertEquals("Paulo's phone", info.subject)
        assertFalse(info.expired)
        assertTrue(info.daysLeft() > 300)
        assertFalse(info.expiresSoon())
        assertTrue(info.daysLeft(now = info.notAfterMillis + 3 * 86_400_000L) < 0)
        assertTrue(info.expiresSoon(now = info.notAfterMillis - 5 * 86_400_000L))
    }

    @Test
    fun theSecretsDoNotSurviveAStrayToString() {
        val text = listOf(imported().toString(), ConnectionProfile(certificate = imported()).toString()).joinToString()
        assertFalse(text.contains(password))
        assertFalse(text.contains(Base64.getEncoder().encodeToString(p12()).take(40)))
    }
}
