package eu.studio742.imago.core.data

import java.security.MessageDigest

/**
 * The key that separates local data by Immich server.
 *
 * It has to be computed the same way in every repository: assets, recipes and derived assets are
 * read with joins between them, and a different key silently breaks those joins.
 */
fun libraryKeyOf(serverUrl: String): String = MessageDigest.getInstance("SHA-256")
    .digest(serverUrl.toByteArray())
    .joinToString("") { "%02x".format(it) }
