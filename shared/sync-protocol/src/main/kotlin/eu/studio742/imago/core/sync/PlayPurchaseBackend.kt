package eu.studio742.imago.core.sync

import java.security.MessageDigest

/**
 * Support bought in the app through Google Play billing: the purchase token goes to the server, which
 * checks it with Google Play and records the paid periods for the account that sent it.
 */
interface PlayPurchaseBackend {
    suspend fun record(purchaseToken: String)
}

/** Carries no text of the server's, and never the purchase token: it can end up in a diagnostic. */
class PlayPurchaseException(val reason: Reason, val status: Int? = null) : Exception(reason.name) {
    enum class Reason { NO_ACCOUNT, OTHER_ACCOUNT, NOT_PAID, UNAVAILABLE, OFFLINE, FAILED }
}

/**
 * What a purchase carries as its account (Play's obfuscated account id): the SHA-256 of the IMAGO account
 * id, in hex. The server refuses a purchase made under another account, and Google never sees the id itself.
 */
fun playAccountHash(userId: String): String =
    MessageDigest.getInstance("SHA-256").digest(userId.encodeToByteArray()).joinToString("") { "%02x".format(it) }
