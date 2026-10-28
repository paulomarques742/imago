package eu.studio742.imago.feature.editor

/**
 * The last few RAWs the editor developed, kept in memory for a while.
 *
 * Developing one is seconds on a tablet, and opening a photo, leaving and coming back to it a minute
 * later is what editing mostly is. A few entries only — each is a 2048 px float image and its bitmap, tens
 * of megabytes — and none older than [lifetimeMillis]: a source nobody asks for goes, so that the memory
 * is not held for a photo that is not coming back.
 */
internal class RecentSources<T : Any>(
    private val capacity: Int = 2,
    private val lifetimeMillis: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry<T>(val value: T, val at: Long)

    // Access order, so that the one dropped for room is the one not used for longest.
    private val entries = object : LinkedHashMap<String, Entry<T>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<T>>) = size > capacity
    }

    @Synchronized
    fun get(key: String): T? {
        val now = clock()
        entries.entries.removeAll { now - it.value.at > lifetimeMillis }
        return entries[key]?.also { entries[key] = Entry(it.value, now) }?.value
    }

    @Synchronized
    fun put(key: String, value: T) {
        entries[key] = Entry(value, clock())
    }

    @Synchronized
    fun clear() = entries.clear()
}
