package eu.studio742.imago.core.model.diagnostics

import eu.studio742.imago.core.model.ServerVersion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class DiagnosticStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var clock = 1_800_000_000_000L
    private val event = DiagnosticEvent(DiagnosticArea.IMMICH, DiagnosticOutcome.HTTP_ERROR,
        DiagnosticOperation.ALBUMS, status = 403, connection = DiagnosticConnection.from("https://192.168.1.42/private"), version = ServerVersion(3, 3, 5))

    @Test fun expiresEventsAndReportsWithoutNewTrafficAndAfterRestart() {
        val directory = temporary.newFolder()
        val store = DiagnosticStore(directory, { clock })
        store.append(event)
        val report = store.report(emptyMap(), "Album does not open", DiagnosticAccess.VPN, emptyList())
        assertTrue(report.exists())
        clock += DiagnosticStore.RETENTION_MS + 1
        val restarted = DiagnosticStore(directory, { clock })
        restarted.cleanup()
        assertTrue(directory.resolve("events").listFiles()!!.isEmpty())
        assertFalse(report.exists())
    }

    @Test fun reportIncludesOnlyFreshEventsAndSafeContext() {
        val directory = temporary.newFolder()
        val store = DiagnosticStore(directory, { clock })
        store.append(event)
        clock += DiagnosticStore.RETENTION_MS
        store.append(event.copy(operation = DiagnosticOperation.SEARCH, outcome = DiagnosticOutcome.TIMEOUT))
        val report = store.report(mapOf("Model" to "SM-S918B"), "Album does not open", DiagnosticAccess.REVERSE_PROXY, emptyList()).readText()
        assertFalse(report.contains("ALBUMS"))
        assertTrue(report.contains("SEARCH TIMEOUT"))
        assertTrue(report.contains("immich=3.3.5"))
        assertTrue(report.contains("REVERSE_PROXY"))
        assertTrue(report.contains("protocol=HTTPS address=LOCAL_ADDRESS"))
        assertTrue(report.contains("SM-S918B"))
        assertFalse(report.contains("192.168.1.42"))
    }

    @Test fun boundsDiskUsageEvenDuringAnErrorStorm() {
        val directory = temporary.newFolder()
        val store = DiagnosticStore(directory, { clock }, maxBytes = 4096)
        repeat(300) { clock += 60_000; store.append(event) }
        assertTrue(directory.resolve("events").listFiles()!!.sumOf { it.length() } <= 4096)
        repeat(10) { clock += 1; store.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()) }
        val reports = directory.resolve("reports").listFiles()!!
        assertTrue(reports.size <= 4)
        assertTrue(reports.sumOf { it.length() } <= 8192)
    }

    @Test fun removesKnownCredentialsAndAddressesFromDescriptionAndExport() {
        val directory = temporary.newFolder()
        val store = DiagnosticStore(directory, { clock })
        val text = "Broken https://photos.example.invalid/private?key=private-key-987 photos.example.invalid private-key-987 API key: different-secret"
        val report = store.report(emptyMap(), text, DiagnosticAccess.TUNNEL,
            listOf("https://photos.example.invalid/private", "photos.example.invalid", "private-key-987")).readText()
        listOf("https://", "photos.example.invalid", "private-key-987", "different-secret").forEach { assertFalse(it, report.contains(it)) }
        assertTrue(report.contains("Broken"))
    }

    @Test fun neverPersistsExceptionMessagesOrPaths() {
        val directory = temporary.newFolder()
        val store = DiagnosticStore(directory, { clock })
        val error = IOException("https://private.example.invalid API-key=secret /storage/user/photo.jpg")
        store.append(event.copy(failure = DiagnosticFailure.from(error)))
        val contents = directory.walkTopDown().filter(File::isFile).joinToString { it.readText() }
        listOf("private.example", "secret", "photo.jpg", "/storage").forEach { assertFalse(contents.contains(it)) }
        assertTrue(contents.contains("DiagnosticStoreTest.neverPersistsExceptionMessagesOrPaths"))
    }

    @Test fun concurrentRequestsAndReportCreationKeepJournalReadable() {
        val store = DiagnosticStore(temporary.newFolder(), { clock })
        val pool = Executors.newFixedThreadPool(4)
        val futures = (0..99).map { pool.submit { store.append(event) } }
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        val report = store.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()).readText()
        assertEquals(100, report.lineSequence().count { it.contains("IMMICH ALBUMS HTTP_ERROR") })
    }

    @Test fun classifiesWrappedNetworkFailuresWithoutTheirMessages() {
        assertEquals(DiagnosticOutcome.DNS, diagnosticOutcome(IOException("private", UnknownHostException("private"))))
        assertEquals(DiagnosticOutcome.TIMEOUT, diagnosticOutcome(SocketTimeoutException("private")))
        assertEquals(DiagnosticOutcome.TLS, diagnosticOutcome(SSLHandshakeException("private")))
        assertEquals(DiagnosticOutcome.CANCELLED, diagnosticOutcome(java.util.concurrent.CancellationException("private")))
        assertEquals(DiagnosticTransport.UNKNOWN, DiagnosticEvent(DiagnosticArea.APP, DiagnosticOutcome.START).transport)
    }

    @Test fun classifiesAddressesWithoutRetainingTheirValues() {
        listOf("http://10.1.2.3", "https://172.20.1.3", "https://[fd00::1234]", "http://server.local").forEach {
            assertEquals(it, DiagnosticAddress.LOCAL_ADDRESS, DiagnosticConnection.from(it).address)
        }
        assertEquals(DiagnosticAddress.HOSTNAME, DiagnosticConnection.from("https://photos.example.invalid").address)
        assertEquals(DiagnosticProtocol.HTTP, DiagnosticConnection.from("http://10.1.2.3").protocol)
        assertEquals(DiagnosticProtocol.UNKNOWN, DiagnosticConnection.from("not an address").protocol)
    }

    @Test fun redactsBeforeTruncatingAndRemovesBearerCredentials() {
        val secret = "a-long-private-api-key"
        val cleaned = redactSupportDescription("prefix $secret Authorization: Bearer another-private-token", listOf(secret), 14)
        assertEquals("prefix [redact", cleaned)
        assertFalse(redactSupportDescription("Authorization: Bearer another-private-token", emptyList()).contains("another-private-token"))
    }

    @Test fun storageFailureDoesNotCrashTheAppAndIsVisibleToSupport() {
        val blocked = temporary.newFile()
        LocalDiagnostics.start(DiagnosticStore(blocked), emptyMap())
        try {
            LocalDiagnostics.record(event)
            assertTrue(LocalDiagnostics.storageFailed)
        } finally { LocalDiagnostics.stop() }
    }

    @Test fun eventPreparationFailureNeverEscapesIntoTheOperationBeingLogged() {
        LocalDiagnostics.start(DiagnosticStore(temporary.newFolder(), { clock }), emptyMap())
        try {
            LocalDiagnostics.record { throw java.util.regex.PatternSyntaxException("Simulated Android ICU syntax failure", "\\{[^}]+}", 8) }
            assertTrue(LocalDiagnostics.storageFailed)
            LocalDiagnostics.record(event)
            val report = LocalDiagnostics.store!!.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()).readText()
            assertTrue(report.contains("IMMICH ALBUMS HTTP_ERROR"))
            assertFalse(report.contains("Simulated Android ICU syntax failure"))
        } finally { LocalDiagnostics.stop() }
    }
}
