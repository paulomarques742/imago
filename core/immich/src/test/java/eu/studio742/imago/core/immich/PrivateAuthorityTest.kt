package eu.studio742.imago.core.immich

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.cert.CertificateException

/**
 * A server with a certificate from the person's own authority: on Windows that authority is in the
 * system's store, which the JVM does not read by itself.
 */
class PrivateAuthorityTest {
    private val authority = HeldCertificate.Builder().commonName("Home lab CA").certificateAuthority(0).build()
    private val server = HeldCertificate.Builder().commonName("photos").addSubjectAlternativeName("photos.example.test").signedBy(authority).build()
    private val stranger = HeldCertificate.Builder().commonName("stranger").build()

    private fun trusting(vararg certificates: HeldCertificate) =
        HandshakeCertificates.Builder().apply { certificates.forEach { addTrustedCertificate(it.certificate) } }.build().trustManager

    private val chain get() = arrayOf(server.certificate, authority.certificate)
    private val authType = "ECDHE_ECDSA"

    @Test
    fun aCertificateIsTrustedWhenAnyOfTheSourcesTrustsItsAuthority() {
        val manager = AnyOfTrustManager(listOf(trusting(stranger), trusting(authority)))
        manager.checkServerTrusted(chain, authType)
    }

    @Test
    fun aCertificateNoSourceKnowsIsStillRefused() {
        val manager = AnyOfTrustManager(listOf(trusting(stranger), trusting(stranger)))
        val failure = runCatching { manager.checkServerTrusted(chain, authType) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is CertificateException)
    }

    @Test
    fun whatTheSourcesAcceptAsAuthoritiesIsTheirUnion() {
        val manager = AnyOfTrustManager(listOf(trusting(stranger), trusting(authority)))
        val names = manager.acceptedIssuers.map { it.subjectX500Principal.name }.toSet()
        assertEquals(setOf(stranger.certificate.subjectX500Principal.name, authority.certificate.subjectX500Principal.name), names)
    }

    @Test
    fun onWindowsTheSystemStoresAreRead() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        // The JVM's bundle plus Windows' stores: never fewer authorities than the bundle alone.
        val bundled = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
        val platform = platformTrustManager()
        assertTrue(platform is AnyOfTrustManager)
        assertTrue(platform.acceptedIssuers.size >= bundled.acceptedIssuers.size)
    }
}
