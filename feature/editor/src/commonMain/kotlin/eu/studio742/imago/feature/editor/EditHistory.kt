package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.model.EditRecipe

/**
 * A photo's edit path.
 *
 * A single path with an index, instead of two undo/redo stacks: undoing is stepping back one
 * position, redoing is stepping forward, and the panel's history is the whole list. With two stacks,
 * the timeline would have to be rebuilt from them — and would disagree with them at the first bug.
 *
 * It knows nothing about Compose or Android: this is where the semantics live, and this is where
 * they are tested.
 */
class EditHistory {
    private val entries = mutableListOf<HistoryEntry>()

    var index: Int = 0
        private set

    val all: List<HistoryEntry> get() = entries.toList()
    val size: Int get() = entries.size
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.lastIndex
    val current: HistoryEntry? get() = entries.getOrNull(index)

    /** Starts over on a new photo. [origin] becomes the zero point. */
    fun reset(origin: HistoryEntry) {
        entries.clear()
        entries += origin
        index = 0
    }

    /**
     * Records a change.
     *
     * Editing from a point stepped back to abandons what came after — it is the same as writing over a
     * future that stopped existing.
     */
    fun push(entry: HistoryEntry) {
        if (entries.isEmpty()) {
            entries += entry
            index = 0
            return
        }
        if (index < entries.lastIndex) entries.subList(index + 1, entries.size).clear()
        entries += entry
        index = entries.lastIndex
    }

    /**
     * Jumps to a position in the path, returning the recipe to restore.
     *
     * It returns `null` when there is nothing to do — an index outside the path, or we are already
     * there. The following entries are not deleted: redo can still bring them back.
     */
    fun restore(target: Int): EditRecipe? {
        if (target !in entries.indices || target == index) return null
        index = target
        return entries[target].recipe
    }

    fun undo(): EditRecipe? = restore(index - 1)

    fun redo(): EditRecipe? = restore(index + 1)

    /** "Delete all": goes back to the zero point and forgets the path. */
    fun clear(): EditRecipe? {
        val origin = entries.firstOrNull() ?: return null
        if (entries.size > 1) entries.subList(1, entries.size).clear()
        index = 0
        return origin.recipe
    }
}
