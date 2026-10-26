package eu.studio742.imago.core.model.diagnostics

import eu.studio742.imago.core.model.ServerVersion
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

enum class DiagnosticArea { APP, IMMICH, IMAGE, VIDEO, INTERFACE, EXPORT, SYNC, COMMUNITY }
enum class DiagnosticOutcome { START, SUCCESS, HTTP_ERROR, DNS, TIMEOUT, TLS, CONNECTION, PERMISSION, INVALID_RESPONSE, CANCELLED, FAILURE, CRASH }
enum class DiagnosticTransport { UNKNOWN, OFFLINE, WIFI, CELLULAR, ETHERNET, VPN, OTHER }
enum class DiagnosticAccess { UNKNOWN, LOCAL_NETWORK, DIRECT_REMOTE, REVERSE_PROXY, VPN, TUNNEL }
enum class DiagnosticProtocol { UNKNOWN, HTTP, HTTPS }
enum class DiagnosticAddress { UNKNOWN, LOCAL_ADDRESS, IP_ADDRESS, HOSTNAME }

/** What stood between the app and Immich, when a request failed because of it. A closed set: no text. */
enum class DiagnosticProtection { PROXY_AUTH, CLIENT_CERTIFICATE_REQUIRED, UNTRUSTED_CERTIFICATE }

data class DiagnosticConnection(val protocol: DiagnosticProtocol, val address: DiagnosticAddress) {
    companion object {
        /** Classification never resolves a hostname, contacts a server, or keeps the input. */
        fun from(url: String): DiagnosticConnection = runCatching {
            val uri = URI(url)
            val host = uri.host.orEmpty().lowercase().removeSurrounding("[", "]")
            val local = host == "localhost" || host.endsWith(".local") || host.startsWith("127.") ||
                host.startsWith("10.") || host.startsWith("192.168.") || host == "::1" ||
                host.startsWith("fc") && ':' in host || host.startsWith("fd") && ':' in host ||
                host.startsWith("fe80:") || host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))
            DiagnosticConnection(
                when (uri.scheme?.lowercase()) { "http" -> DiagnosticProtocol.HTTP; "https" -> DiagnosticProtocol.HTTPS; else -> DiagnosticProtocol.UNKNOWN },
                when { host.isEmpty() -> DiagnosticAddress.UNKNOWN; local -> DiagnosticAddress.LOCAL_ADDRESS
                    ':' in host || host.matches(Regex("[0-9.]+")) -> DiagnosticAddress.IP_ADDRESS; else -> DiagnosticAddress.HOSTNAME },
            )
        }.getOrDefault(DiagnosticConnection(DiagnosticProtocol.UNKNOWN, DiagnosticAddress.UNKNOWN))
    }
}

/** Only typed fields cross this boundary: exception messages, URLs and payloads have no log API. */
data class DiagnosticEvent(
    val area: DiagnosticArea,
    val outcome: DiagnosticOutcome,
    val operation: DiagnosticOperation = DiagnosticOperation.OTHER,
    val status: Int? = null,
    val durationMs: Long? = null,
    val connection: DiagnosticConnection? = null,
    val version: ServerVersion? = null,
    val transport: DiagnosticTransport = DiagnosticTransport.UNKNOWN,
    val failure: DiagnosticFailure? = null,
    val protection: DiagnosticProtection? = null,
    /** The address has headers of its own configured: the fact only, never their names or values. */
    val customHeaders: Boolean = false,
    /** The address has a client certificate configured: the fact only. */
    val customCertificate: Boolean = false,
)

class DiagnosticFailure private constructor(private val kind: DiagnosticOutcome, private val frames: List<String>) {
    override fun toString(): String = "$kind ${frames.joinToString(" <- ")}"

    companion object {
        fun from(error: Throwable): DiagnosticFailure {
            val frames = error.stackTrace.filter { it.className.startsWith("eu.studio742.imago.") }
                .take(12).map { frame ->
                    // Only code symbols and line numbers, never exception text or filesystem paths.
                    val symbol = "${frame.className}.${frame.methodName}"
                    "${symbol.replace(Regex("[^a-zA-Z0-9_.$<>-]"), "_").take(180)}:${frame.lineNumber}"
                }
            return DiagnosticFailure(diagnosticOutcome(error), frames)
        }
    }
}

enum class DiagnosticOperation {
    OTHER, SERVER_VERSION, PING, CURRENT_USER, SEARCH, ALBUMS, TIME_BUCKETS, TIME_BUCKET,
    THUMBNAIL, VIDEO, DOWNLOAD, UPLOAD, STACK, ASSET, FAVORITE, DELETE, RESTORE, TRASH,
    SERVER_CONFIG, SMART_SEARCH, SERVER_FEATURES, KEY_PERMISSIONS, EDITS, PEOPLE, MAP, MEMORIES,
}

fun diagnosticOutcome(error: Throwable): DiagnosticOutcome {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        when (current) {
            is CancellationException -> return DiagnosticOutcome.CANCELLED
            is UnknownHostException -> return DiagnosticOutcome.DNS
            is SocketTimeoutException -> return DiagnosticOutcome.TIMEOUT
            is java.io.InterruptedIOException -> return DiagnosticOutcome.TIMEOUT
            is SSLException -> return DiagnosticOutcome.TLS
            is ConnectException -> return DiagnosticOutcome.CONNECTION
            is SecurityException -> return DiagnosticOutcome.PERMISSION
        }
        current = current.cause
    }
    return if (error is IOException) DiagnosticOutcome.CONNECTION else DiagnosticOutcome.FAILURE
}

/**
 * Whether a failure was the server's protection and not the server: a certificate it does not
 * trust or one it asks for. The text of the exception is read here and nowhere else, and only to
 * choose between these values; it is never kept.
 */
fun diagnosticProtection(error: Throwable): DiagnosticProtection? {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var untrusted = false
    // OkHttp tries each address of a host and throws the last failure, keeping the earlier ones —
    // the TLS error among them — as suppressed.
    val pending = ArrayDeque<Throwable>().apply { add(error) }
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        val text = current.message.orEmpty().lowercase()
        if (current is SSLException && CLIENT_CERTIFICATE_ALERTS.any { it in text }) return DiagnosticProtection.CLIENT_CERTIFICATE_REQUIRED
        if (current is java.security.cert.CertificateException || current is java.security.cert.CertPathValidatorException ||
            current is java.security.cert.CertPathBuilderException || current is javax.net.ssl.SSLPeerUnverifiedException) untrusted = true
        current.cause?.let(pending::add)
        pending.addAll(current.suppressed)
    }
    return if (untrusted) DiagnosticProtection.UNTRUSTED_CERTIFICATE else null
}

// What the JDK, Conscrypt and BoringSSL call the alert a server sends to a client without a certificate.
private val CLIENT_CERTIFICATE_ALERTS = listOf("certificate_required", "certificate required", "bad_certificate", "bad certificate", "no_certificate")

/** A private, bounded journal. Nothing in this class can upload or send a report. */
class DiagnosticStore(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = 2L * 1024 * 1024,
    private val reportsDirectory: File = directory.resolve("reports"),
) {
    private val events get() = directory.resolve("events")
    private val reports get() = reportsDirectory

    @Synchronized fun append(event: DiagnosticEvent) {
        cleanup()
        check(events.isDirectory || events.mkdirs())
        val timestamp = now()
        val line = buildString {
            append(Instant.ofEpochMilli(timestamp)).append(' ').append(event.area).append(' ').append(event.operation)
            append(' ').append(event.outcome).append(" transport=").append(event.transport)
            event.status?.takeIf { it in 100..599 }?.let { append(" http=").append(it) }
            event.durationMs?.let { append(" ms=").append(it.coerceAtLeast(0)) }
            event.connection?.let { append(" protocol=").append(it.protocol).append(" address=").append(it.address) }
            event.version?.let { append(" immich=").append(it.major).append('.').append(it.minor).append('.').append(it.patch) }
            event.protection?.let { append(" protection=").append(it) }
            if (event.customHeaders) append(" headers=custom")
            if (event.customCertificate) append(" certificate=custom")
            event.failure?.let { append(" failure=").append(it) }
            append('\n')
        }
        // One minute per file bounds expiry granularity without rewriting the whole journal per event.
        events.resolve("${timestamp / MINUTE}.log").appendText(line, Charsets.UTF_8)
        trim(events, maxBytes)
    }

    @Synchronized fun cleanup() {
        val cutoff = now() - RETENTION_MS
        events.listFiles()?.forEach { file ->
            val start = file.nameWithoutExtension.toLongOrNull()?.times(MINUTE)
            if (start == null || start <= cutoff || start > now()) check(file.delete())
        }
        reports.listFiles()?.forEach { file ->
            if (file.lastModified() <= cutoff || file.lastModified() > now()) check(file.delete())
        }
        trim(events, maxBytes)
        trim(reports, maxBytes * 2, 4)
    }

    /** Metadata must come from the OS/build, never from account, server or user preferences. */
    @Synchronized fun report(metadata: Map<String, String>, description: String, access: DiagnosticAccess, secrets: Collection<String>): File {
        cleanup()
        check(reports.isDirectory || reports.mkdirs())
        val file = reports.resolve("IMAGO-support-${now()}.txt")
        val text = buildString {
            appendLine("IMAGO local diagnostics (format 1)")
            appendLine("Created: ${Instant.ofEpochMilli(now())}")
            appendLine("Retention: up to 24 hours; oldest events may be removed at the storage limit.")
            metadata.toSortedMap().forEach { (key, value) ->
                appendLine("${key.take(40)}: ${value.replace(Regex("[\\r\\n\\p{Cntrl}]"), " ").take(160)}")
            }
            appendLine("Connection method (reported by user): $access")
            appendLine("Immich versions below are observed versions; absent means unknown. Proxy/tunnel cannot be inferred.")
            appendLine("\nProblem description:\n${redactSupportDescription(description, secrets)}")
            appendLine("\nEvents (no URLs, credentials, headers, bodies, photo names or identifiers):")
            events.listFiles().orEmpty().sortedBy { it.nameWithoutExtension.toLongOrNull() }.forEach {
                append(it.readText(Charsets.UTF_8))
            }
        }
        file.writeText(redactSupportDescription(text, secrets, Int.MAX_VALUE), Charsets.UTF_8)
        file.setLastModified(now())
        trim(reports, maxBytes * 2, 4)
        return file
    }

    private fun trim(folder: File, limit: Long, count: Int = Int.MAX_VALUE) {
        val files = folder.listFiles().orEmpty().sortedBy { it.lastModified() }.toMutableList()
        var size = files.sumOf { it.length() }
        while (files.isNotEmpty() && (size > limit || files.size > count)) {
            val oldest = files.removeAt(0)
            size -= oldest.length()
            check(oldest.delete())
        }
    }

    companion object {
        const val RETENTION_MS = 24L * 60 * 60 * 1000
        private const val MINUTE = 60_000L
    }
}

/** Defense in depth for the only free text: the description explicitly written for support. */
fun redactSupportDescription(text: String, secrets: Collection<String>, limit: Int = 2_000): String {
    var clean = text
    secrets.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }.forEach {
        clean = clean.replace(it, "[redacted]", ignoreCase = true)
    }
    return clean.replace(Regex("(?i)\\b(?:https?|wss?)://[^\\s<>]+"), "[address removed]")
        .replace(Regex("(?i)\\b(?:authorization\\s*[:=]?\\s*)?bearer\\s+[^\\s,;]+"), "[credential removed]")
        .replace(Regex("(?i)\\b(?:x-api-key|api[_ -]?key|authorization|bearer|token|password)\\s*[:=]?\\s*[^\\s,;]+"), "[credential removed]")
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ")
        .take(limit)
}

object LocalDiagnostics {
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var installedHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile var store: DiagnosticStore? = null
        private set
    @Volatile var transport: () -> DiagnosticTransport = { DiagnosticTransport.UNKNOWN }
    @Volatile var metadata: Map<String, String> = emptyMap()
        private set
    @Volatile var storageFailed: Boolean = false
        private set

    @Synchronized fun start(store: DiagnosticStore, metadata: Map<String, String>) {
        stop()
        this.store = store
        this.metadata = metadata.toMap()
        storageFailed = false
        record(DiagnosticEvent(DiagnosticArea.APP, DiagnosticOutcome.START))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = previous
        installedHandler = Thread.UncaughtExceptionHandler { thread, error ->
            record { DiagnosticEvent(DiagnosticArea.APP, DiagnosticOutcome.CRASH, failure = DiagnosticFailure.from(error)) }
            if (previous != null) previous.uncaughtException(thread, error) else error.printStackTrace()
        }
        Thread.setDefaultUncaughtExceptionHandler(installedHandler)
    }

    @Synchronized fun stop() {
        if (installedHandler != null && Thread.getDefaultUncaughtExceptionHandler() === installedHandler) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        installedHandler = null
        previousHandler = null
        store = null
        metadata = emptyMap()
    }

    fun record(event: DiagnosticEvent) {
        try { store?.append(event.copy(transport = transport())) }
        catch (_: Exception) { storageFailed = true }
    }

    /** Event preparation is diagnostic work too; it must never interrupt the operation being observed. */
    fun record(event: () -> DiagnosticEvent) {
        try { record(event()) }
        catch (_: Exception) { storageFailed = true }
    }

    fun failure(area: DiagnosticArea, error: Throwable, operation: DiagnosticOperation = DiagnosticOperation.OTHER) {
        val outcome = diagnosticOutcome(error)
        if (outcome != DiagnosticOutcome.CANCELLED) record {
            DiagnosticEvent(area, outcome, operation, failure = DiagnosticFailure.from(error), protection = diagnosticProtection(error))
        }
    }
}
