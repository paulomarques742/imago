package eu.studio742.imago.core.immich

import okhttp3.OkHttpClient

/**
 * The one HTTP client everything that talks to the Immich server goes through: the API, thumbnails,
 * video playback and downloads. Whatever the connection needs — extra headers, a client certificate,
 * a trusted authority — is configured on it once, so no path to the server can be left out.
 *
 * It is set at startup by the platform's data graph. The default only serves tests and previews.
 */
object ImmichHttp {
    /** What each address needs besides the API key; the client built with [withConnectionProfiles] reads it. */
    val profiles = ConnectionProfileRegistry()

    @Volatile
    var client: OkHttpClient = OkHttpClient()
        private set

    fun install(client: OkHttpClient): OkHttpClient {
        this.client = client
        return client
    }
}
