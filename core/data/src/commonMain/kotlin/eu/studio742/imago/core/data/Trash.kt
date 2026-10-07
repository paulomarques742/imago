package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.ImmichAsset
import java.time.Duration
import java.time.Instant

/** A photo or video in a library's trash; [expiresAt] is when it goes for good, when the library says. */
data class TrashedAsset(val asset: ImmichAsset, val expiresAt: String? = null)

/**
 * What a library's trash holds. [keptDays] is how long the library keeps it, when it says so for the
 * whole trash rather than per photo — a server does; the phone dates each one.
 */
data class TrashContents(val items: List<TrashedAsset>, val keptDays: Int? = null)

/** Whole days left before [expiresAt], rounded up: a photo leaving tonight still has one. Never negative. */
fun daysLeft(expiresAt: String, now: Instant = Instant.now()): Int? {
    val end = runCatching { Instant.parse(expiresAt) }.getOrNull() ?: return null
    val seconds = Duration.between(now, end).seconds
    return if (seconds <= 0) 0 else ((seconds + 86_399) / 86_400).toInt()
}
