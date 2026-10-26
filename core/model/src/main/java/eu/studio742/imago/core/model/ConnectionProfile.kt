package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionHeader(val name: String, val value: String) {
    // A header is a secret: it must not reach a log or a crash report through a stray `toString`.
    override fun toString() = "ConnectionHeader(redacted)"
}

/** A PKCS#12 file as the person picked it, kept as it came, with the password that opens it. */
@Serializable
data class ClientCertificate(val pkcs12: String, val password: String) {
    override fun toString() = "ClientCertificate(redacted)"
}

/**
 * What one server address needs besides the API key to be reached: headers a proxy in front of the
 * server checks (Cloudflare Access service tokens, a shared secret) and the client certificate a
 * server asks for (mTLS). It belongs to the address, not
 * to the library: the home address usually has none, and a secret only goes to the host that asks
 * for it.
 */
/** What the settings may show about a certificate: never the key, never the password. */
data class ClientCertificateInfo(val subject: String, val issuer: String, val notAfterMillis: Long, val expired: Boolean) {
    /** Whole days left; negative once it has expired. */
    fun daysLeft(now: Long = System.currentTimeMillis()): Long = Math.floorDiv(notAfterMillis - now, 86_400_000L)
    fun expiresSoon(now: Long = System.currentTimeMillis()): Boolean = daysLeft(now) <= EXPIRY_WARNING_DAYS

    companion object {
        /** Soon enough to renew before the server starts refusing without a word. */
        const val EXPIRY_WARNING_DAYS = 30
    }
}

@Serializable
data class ConnectionProfile(
    val headers: List<ConnectionHeader> = emptyList(),
    val certificate: ClientCertificate? = null,
) {
    val isEmpty: Boolean get() = headers.isEmpty() && certificate == null

    /** Names and values as they will be sent, or the first reason one cannot be. */
    fun problem(serverUrl: String): UserMessage? =
        if (isEmpty) null
        else if (!serverUrl.trimStart().startsWith("https://", ignoreCase = true)) UserMessage.CONNECTION_NEEDS_HTTPS
        else headers.firstNotNullOfOrNull(ConnectionHeaders::problem)
            ?: if (headers.map { it.name.lowercase() }.distinct().size != headers.size) UserMessage.CONNECTION_HEADER_REPEATED else null

    companion object {
        val None = ConnectionProfile()
    }
}

object ConnectionHeaders {
    /** The Cloudflare Access service token, the one a person is most likely to set up. */
    val CLOUDFLARE_ACCESS = listOf("CF-Access-Client-Id", "CF-Access-Client-Secret")

    /** Headers the app or the HTTP stack owns: letting one be overridden breaks the request or leaks the key. */
    private val reserved = setOf(
        "x-api-key", "host", "content-length", "content-type", "transfer-encoding", "connection", "cookie",
        "accept-encoding", "expect", "upgrade", "te", "trailer", "range", "user-agent",
    )

    // RFC 9110 "token": what a header name may be made of.
    private val token = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun problem(header: ConnectionHeader): UserMessage? = when {
        header.name.isBlank() || !token.matches(header.name) -> UserMessage.CONNECTION_HEADER_NAME_INVALID
        header.name.lowercase() in reserved -> UserMessage.CONNECTION_HEADER_NAME_RESERVED
        header.value.isBlank() || header.value.any { it == '\r' || it == '\n' || it == '\u0000' } -> UserMessage.CONNECTION_HEADER_VALUE_INVALID
        else -> null
    }
}
