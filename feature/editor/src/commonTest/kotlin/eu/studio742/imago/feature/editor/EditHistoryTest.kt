package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.asUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe

class EditHistoryTest {
    private val base = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-08-06T12:00:00Z",
        updatedAt = "2026-08-06T12:00:00Z",
    )

    private fun entry(id: String, exposure: Float) = HistoryEntry(
        id = id,
        label = "Exposure".asUiText(),
        detail = "Ajuste manual".asUiText(),
        valueText = exposureText(exposure).asUiText(),
        icon = HistoryIcon.EXPOSURE,
        timestamp = "2026-08-06T12:00:0${id}Z",
        recipe = base.copy(tone = base.tone.copy(exposure = exposure)),
    )

    /** O ponto zero, como o `open()` do ViewModel o cria. */
    private fun origin(exposure: Float = 0f) = entry("0", exposure).copy(
        id = "original",
        label = "Original".asUiText(),
        detail = "No adjustments".asUiText(),
        valueText = null,
        icon = HistoryIcon.ORIGINAL,
    )

    // The decimal separator follows the system locale; building the expected value with the same
    // formatting keeps the test from passing only on machines with a comma.
    private fun exposureText(value: Float) = "%+.2f EV".format(value)

    private fun seeded(vararg exposures: Float) = EditHistory().apply {
        reset(origin())
        exposures.forEachIndexed { index, value -> push(entry("${index + 1}", value)) }
    }

    @Test
    fun freshHistoryHasOnlyTheOriginalAndNowhereToGo() {
        val history = EditHistory().apply { reset(origin()) }

        assertEquals(1, history.size)
        assertEquals(0, history.index)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    /** N adjustments produce N labelled entries, plus the original. */
    @Test
    fun eachChangeAddsOneLabelledEntry() {
        val history = seeded(0.1f, 0.2f, 0.3f)

        assertEquals(4, history.size)
        assertEquals(3, history.index)
        assertEquals("Original".asUiText(), history.all.first().label)
        assertEquals(
            listOf(exposureText(0.1f), exposureText(0.2f), exposureText(0.3f)).map { it.asUiText() },
            history.all.drop(1).map { it.valueText },
        )
    }

    /** Repor a entrada k devolve exactamente a receita k. */
    @Test
    fun restoringAnEntryReturnsItsExactRecipe() {
        val history = seeded(0.1f, 0.2f, 0.3f)

        val restored = history.restore(1)

        assertEquals(0.1f, restored?.tone?.exposure)
        assertEquals(1, history.index)
    }

    @Test
    fun undoAndRedoWalkThePathBothWays() {
        val history = seeded(0.1f, 0.2f)

        assertEquals(0.1f, history.undo()?.tone?.exposure)
        assertEquals(0f, history.undo()?.tone?.exposure)
        assertFalse(history.canUndo)
        assertNull(history.undo())

        assertEquals(0.1f, history.redo()?.tone?.exposure)
        assertEquals(0.2f, history.redo()?.tone?.exposure)
        assertFalse(history.canRedo)
        assertNull(history.redo())
    }

    /** Editing from a point stepped back to abandons what came after. */
    @Test
    fun editingAfterUndoDiscardsTheAbandonedFuture() {
        val history = seeded(0.1f, 0.2f, 0.3f)
        history.restore(1)

        history.push(entry("9", 0.9f))

        assertEquals(3, history.size)
        assertEquals(2, history.index)
        assertEquals(listOf(0f, 0.1f, 0.9f), history.all.map { it.recipe.tone.exposure })
        assertFalse(history.canRedo)
    }

    /** Stepping back deletes nothing: what is ahead can still be redone. */
    @Test
    fun restoringKeepsTheEntriesAhead() {
        val history = seeded(0.1f, 0.2f, 0.3f)

        history.restore(0)

        assertEquals(4, history.size)
        assertTrue(history.canRedo)
    }

    @Test
    fun clearingReturnsToTheOriginalAndForgetsThePath() {
        val history = seeded(0.1f, 0.2f)

        val restored = history.clear()

        assertEquals(0f, restored?.tone?.exposure)
        assertEquals(1, history.size)
        assertEquals(0, history.index)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun restoringOutsideThePathDoesNothing() {
        val history = seeded(0.1f)

        assertNull(history.restore(7))
        assertNull(history.restore(-1))
        // We are already there: there is nothing to restore nor state to change.
        assertNull(history.restore(history.index))
        assertEquals(1, history.index)
    }

    @Test
    fun resettingStartsANewPhotographFromScratch() {
        val history = seeded(0.1f, 0.2f)

        history.reset(origin(0.5f))

        assertEquals(1, history.size)
        assertEquals(0, history.index)
        assertEquals(0.5f, history.current?.recipe?.tone?.exposure)
    }
}
