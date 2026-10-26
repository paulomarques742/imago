package eu.studio742.imago.core.immich

import eu.studio742.imago.core.model.ConnectionHeader
import eu.studio742.imago.core.model.ConnectionProfile
import eu.studio742.imago.core.model.LibrarySource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * What each server address needs besides the API key, looked up by the address a request goes to.
 *
 * The match is by origin and path prefix, so a library's home address and its external one never
 * share a secret, and a request that lands on another host — a redirect to a login page — finds
 * nothing.
 */
class ConnectionProfileRegistry {
    private class Entry(val url: HttpUrl, val profile: ConnectionProfile, val key: ClientKey? = null)

    @Volatile private var saved = emptyList<Entry>()
    @Volatile private var staged = emptyList<Entry>()

    /** The saved libraries' profiles; called whenever the configuration changes. */
    fun replaceAll(sources: List<LibrarySource>) {
        saved = sources.flatMap { source ->
            source.profiles.mapNotNull { (address, profile) ->
                if (profile.isEmpty) null
                // A saved certificate that has since expired still loads: the server's refusal is what shows.
                else runCatching { Entry(normalizeServerUrl(address), profile, profile.certificate?.let { ClientCertificates.load(it, enforceValidity = false) }) }.getOrNull()
            }
        }
    }

    /**
     * Runs [block] with [profile] applied to [serverUrl] as if it were saved: an address is tested
     * with its headers before it is allowed to join a library.
     */
    suspend fun <T> staged(serverUrl: String, profile: ConnectionProfile, block: suspend () -> T): T {
        if (profile.isEmpty) return block()
        // An unusable certificate is refused here, before anything is sent with it.
        val entry = Entry(normalizeServerUrl(serverUrl), profile, profile.certificate?.let { ClientCertificates.load(it, enforceValidity = true) })
        synchronized(this) { staged = staged + entry }
        try { return block() } finally { synchronized(this) { staged = staged - entry } }
    }

    fun headersFor(url: HttpUrl): List<ConnectionHeader> =
        (staged + saved).filter { covers(it.url, url) }
            .maxByOrNull { it.url.pathSegments.count(String::isNotEmpty) }?.profile?.headers.orEmpty()

    /** The certificate for the server at [host]:[port], or null: it goes to the address that asked for it, not to others. */
    fun clientKeyFor(host: String, port: Int): ClientKey? =
        (staged + saved).firstOrNull { it.key != null && it.url.host.equals(host, ignoreCase = true) && it.url.port == port }?.key

    fun hasCertificate(url: HttpUrl): Boolean = (staged + saved).any { it.key != null && covers(it.url, url) }

    private fun covers(address: HttpUrl, request: HttpUrl): Boolean {
        if (address.scheme != request.scheme || !address.host.equals(request.host, ignoreCase = true) ||
            address.port != request.port) return false
        val prefix = address.pathSegments.filter(String::isNotEmpty)
        val path = request.pathSegments.filter(String::isNotEmpty)
        return path.size >= prefix.size && path.take(prefix.size) == prefix
    }
}

/**
 * A network interceptor, not an application one: it runs on every hop, including a redirect, with
 * that hop's own URL. Added at the application level it would see only the first request, and the
 * headers would follow a redirect to any host.
 */
internal class ConnectionProfileInterceptor(private val registry: ConnectionProfileRegistry) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Never in clear: a secret on a plain http request is a secret published.
        if (!request.url.isHttps) return chain.proceed(request)
        val headers = registry.headersFor(request.url)
        if (headers.isEmpty()) return chain.proceed(request)
        val builder = request.newBuilder()
        for (header in headers) runCatching { builder.header(header.name, header.value) }
        return chain.proceed(builder.build())
    }
}

/**
 * Presents the certificate configured for the server being reached — chosen by the host and port of
 * the connection, so the certificate for one address is never offered to another — and none
 * otherwise. The alias is the origin itself: the key manager is asked for the key by alias later.
 */
internal class ProfileKeyManager(private val registry: ConnectionProfileRegistry) : javax.net.ssl.X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? {
        val ssl = socket as? javax.net.ssl.SSLSocket
        val session = runCatching { ssl?.handshakeSession }.getOrNull()
        // The peer host as the app named it. Asking the socket's address for a host name could do a
        // reverse lookup, so only its literal address is used as a fallback.
        val host = session?.peerHost ?: socket?.inetAddress?.hostAddress ?: return null
        return aliasFor(host, session?.peerPort?.takeIf { it > 0 } ?: socket?.port ?: return null)
    }

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?): String? {
        val host = engine?.peerHost ?: return null
        return aliasFor(host, engine.peerPort.takeIf { it > 0 } ?: return null)
    }

    private fun aliasFor(host: String, port: Int): String? = "$host:$port".takeIf { registry.clientKeyFor(host, port) != null }

    private fun keyOf(alias: String?) = alias?.let { registry.clientKeyFor(it.substringBeforeLast(':'), it.substringAfterLast(':').toIntOrNull() ?: return null) }

    override fun getPrivateKey(alias: String?): java.security.PrivateKey? = keyOf(alias)?.privateKey
    override fun getCertificateChain(alias: String?): Array<java.security.cert.X509Certificate>? = keyOf(alias)?.chain?.toTypedArray()
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
}

/**
 * The platform's own trust. On Android it is the one the network security config shapes, which
 * includes the authorities the person installed. On Windows the JVM trusts only the bundle that
 * comes with it, so the certificate stores Windows keeps — where a home lab's authority is
 * installed — are added to it.
 */
internal fun platformTrustManager(): javax.net.ssl.X509TrustManager {
    val bundled = trustManagerOf(null)
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return bundled
    val windows = listOf("Windows-ROOT", "Windows-ROOT-LOCALMACHINE").mapNotNull { type ->
        runCatching { trustManagerOf(java.security.KeyStore.getInstance(type).apply { load(null, null) }) }.getOrNull()
    }
    return if (windows.isEmpty()) bundled else AnyOfTrustManager(listOf(bundled) + windows)
}

private fun trustManagerOf(store: java.security.KeyStore?): javax.net.ssl.X509TrustManager =
    javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }
        .trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()

/** Trusts what any of its managers trusts: the JVM's bundle and Windows' stores are each a source. */
internal class AnyOfTrustManager(private val managers: List<javax.net.ssl.X509TrustManager>) : javax.net.ssl.X509TrustManager {
    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) = anyOf { it.checkServerTrusted(chain, authType) }
    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>, authType: String) = anyOf { it.checkClientTrusted(chain, authType) }
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = managers.flatMap { it.acceptedIssuers.asList() }.toTypedArray()

    private fun anyOf(check: (javax.net.ssl.X509TrustManager) -> Unit) {
        var first: java.security.cert.CertificateException? = null
        for (manager in managers) {
            try { return check(manager) } catch (error: java.security.cert.CertificateException) { if (first == null) first = error }
        }
        throw first ?: java.security.cert.CertificateException("No trust manager")
    }
}

/**
 * Gives the client what each address needs besides the API key: its headers and its client
 * certificate. [trustManager] decides which servers are trusted, and is the platform's unless a test
 * brings its own.
 */
fun OkHttpClient.Builder.withConnectionProfiles(
    registry: ConnectionProfileRegistry = ImmichHttp.profiles,
    trustManager: javax.net.ssl.X509TrustManager = platformTrustManager(),
): OkHttpClient.Builder {
    val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(arrayOf(ProfileKeyManager(registry)), arrayOf(trustManager), null) }
    return sslSocketFactory(context.socketFactory, trustManager).addNetworkInterceptor(ConnectionProfileInterceptor(registry))
}
