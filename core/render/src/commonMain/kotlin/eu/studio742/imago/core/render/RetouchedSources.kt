package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.Retouch

/**
 * Photos with their corrections applied, kept while the corrections stay the same.
 *
 * The editor renders on every slider change and the corrections only change when one is drawn or
 * moved, so working them out again each frame would be the whole cost of step 1a for nothing. A few
 * entries, because one editor holds the same photo at more than one size — the draft and the full
 * preview — and a RAW in two precisions.
 */
class RetouchedSources(private val capacity: Int = 3) {
    private class Entry(val source: Any, val retouches: List<Retouch>, val corrected: Any)

    private val entries = ArrayDeque<Entry>()

    fun of(source: PixelBuffer, retouches: List<Retouch>): PixelBuffer =
        if (retouches.isEmpty()) source else lookup(source, retouches) { Retouching.apply(source, retouches) }

    fun of(source: FloatPixelBuffer, retouches: List<Retouch>): FloatPixelBuffer =
        if (retouches.isEmpty()) source else lookup(source, retouches) { Retouching.apply(source, retouches) }

    @Synchronized
    private fun <T : Any> lookup(source: T, retouches: List<Retouch>, apply: () -> T): T {
        val found = entries.firstOrNull { it.source === source && it.retouches == retouches }
        @Suppress("UNCHECKED_CAST")
        if (found != null) return found.corrected as T
        // A photo is held once, with its latest corrections: going back to an older list (an undo) works
        // it out again, which is cheaper than a second copy of the photo held for the chance.
        entries.removeAll { it.source === source }
        val corrected = apply()
        entries.addFirst(Entry(source, retouches, corrected))
        while (entries.size > capacity) entries.removeLast()
        return corrected
    }
}
