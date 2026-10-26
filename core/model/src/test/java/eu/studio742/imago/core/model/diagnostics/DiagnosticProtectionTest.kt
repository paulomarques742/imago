package eu.studio742.imago.core.model.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class DiagnosticProtectionTest {
    @Test fun aServerThatWantsAClientCertificateIsRecognisedInEveryStackSAlertText() {
        listOf(
            "Received fatal alert: bad_certificate",                                   // JDK, Windows
            "Received fatal alert: certificate_required",                              // JDK, TLS 1.3
            "SSL handshake aborted: ssl=0x1: Failure in SSL library, TLSV13_ALERT_CERTIFICATE_REQUIRED", // Android
            "SSL handshake aborted: ssl=0x1: SSLV3_ALERT_BAD_CERTIFICATE",             // Android, TLS 1.2
        ).forEach { text ->
            assertEquals(text, DiagnosticProtection.CLIENT_CERTIFICATE_REQUIRED, diagnosticProtection(SSLHandshakeException(text)))
        }
    }

    @Test fun anUntrustedServerCertificateIsRecognisedWherever() {
        val validation = CertPathValidatorException("Trust anchor for certification path not found.")
        assertEquals(DiagnosticProtection.UNTRUSTED_CERTIFICATE, diagnosticProtection(SSLHandshakeException("x").apply { initCause(validation) }))
        assertEquals(DiagnosticProtection.UNTRUSTED_CERTIFICATE, diagnosticProtection(SSLPeerUnverifiedException("Hostname does not match")))
        assertEquals(DiagnosticProtection.UNTRUSTED_CERTIFICATE, diagnosticProtection(CertificateException("expired")))
    }

    @Test fun theFailureThatOkHttpKeepsAsSuppressedIsFound() {
        // Several addresses of a host: the last connection failure is thrown, the TLS one is kept aside.
        val thrown = ConnectException("Failed to connect").apply { addSuppressed(SSLHandshakeException("Received fatal alert: bad_certificate")) }
        assertEquals(DiagnosticProtection.CLIENT_CERTIFICATE_REQUIRED, diagnosticProtection(thrown))
    }

    @Test fun ordinaryNetworkFailuresAreNotProtections() {
        assertNull(diagnosticProtection(SocketTimeoutException("timeout")))
        assertNull(diagnosticProtection(ConnectException("Connection refused")))
        assertNull(diagnosticProtection(SSLHandshakeException("Remote host terminated the handshake")))
    }

    @Test fun aCircularCauseChainEnds() {
        val a = RuntimeException("a"); val b = RuntimeException("b", a)
        a.initCause(b)
        assertNull(diagnosticProtection(a))
    }

    @Test fun theJournalLineHoldsTheValueAndNothingTheExceptionSaid() {
        val dir = java.nio.file.Files.createTempDirectory("diagnostics").toFile()
        val secret = "private-host.example.test"
        val error = SSLHandshakeException("Received fatal alert: bad_certificate for $secret")
        DiagnosticStore(dir).append(DiagnosticEvent(
            DiagnosticArea.IMMICH, DiagnosticOutcome.TLS, DiagnosticOperation.SERVER_VERSION,
            failure = DiagnosticFailure.from(error), protection = diagnosticProtection(error), customHeaders = true))
        val log = dir.walkTopDown().filter { it.isFile }.joinToString { it.readText() }
        assertEquals(true, log.contains("protection=CLIENT_CERTIFICATE_REQUIRED"))
        assertEquals(true, log.contains("headers=custom"))
        assertFalse(log.contains(secret))
        dir.deleteRecursively()
    }
}
