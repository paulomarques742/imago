package eu.studio742.imago.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One request to the server answering every caller that asks for the same thing at about the same time.
 *
 * Opening the library asks for the same list from several places at once — the navigation, the catalogue
 * sync, a month being fetched — and each used to make its own trip. A caller arriving while the request
 * is out waits for it; one arriving within [freshForMillis] of an answer gets that answer. A failure is
 * never reused: the next caller tries again.
 *
 * The request runs in [scope], not in the caller's: a caller that goes away does not cancel the answer
 * the others are waiting for.
 */
class RecentRequest<K, T>(
    private val scope: CoroutineScope,
    private val freshForMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (K) -> T,
) {
    private class Entry<K, T>(val key: K, val answer: Deferred<Result<T>>) {
        @Volatile var answeredAt: Long? = null
    }

    private val lock = Mutex()
    private var latest: Entry<K, T>? = null

    suspend fun get(key: K): T {
        val entry = lock.withLock {
            latest?.takeIf { it.key == key && it.isUsable() } ?: newEntry(key).also { latest = it }
        }
        return entry.answer.await().getOrThrow()
    }

    private fun Entry<K, T>.isUsable(): Boolean {
        if (answer.isActive) return true
        val at = answeredAt ?: return false
        return now() - at <= freshForMillis
    }

    private fun newEntry(key: K): Entry<K, T> {
        lateinit var entry: Entry<K, T>
        val answer = scope.async(start = CoroutineStart.LAZY) {
            runCatching { fetch(key) }.onSuccess { entry.answeredAt = now() }
        }
        entry = Entry(key, answer)
        answer.start()
        return entry
    }
}
