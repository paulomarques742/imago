package eu.studio742.imago.core.immich

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun normalizeServerUrl(value: String): HttpUrl {
    val parsed = value.trim().trimEnd('/').toHttpUrlOrNull() ?: throw ImmichApiException.InvalidUrl()
    if (parsed.scheme != "http" && parsed.scheme != "https") throw ImmichApiException.InvalidUrl()

    val segments = parsed.pathSegments.filter(String::isNotBlank).toMutableList()
    if (segments.lastOrNull().equals("api", ignoreCase = true)) segments.removeAt(segments.lastIndex)

    return parsed.newBuilder()
        .encodedPath(if (segments.isEmpty()) "/" else "/${segments.joinToString("/")}/")
        .query(null)
        .fragment(null)
        .build()
}

fun canonicalServerUrl(value: String): String = normalizeServerUrl(value).toString().trimEnd('/')
