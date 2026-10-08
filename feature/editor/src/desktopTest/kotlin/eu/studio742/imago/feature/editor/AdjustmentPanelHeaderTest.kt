package eu.studio742.imago.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top of the adjustments panel: all five categories have to fit, whatever precision tools the
 * selected one brings along.
 *
 * Testers found Masks missing in Colour: its two tools shared the categories' row and pushed the last
 * one off the screen. The narrowest widths are a small phone and the narrowest side rail.
 */
@OptIn(ExperimentalTestApi::class)
class AdjustmentPanelHeaderTest {
    private fun assertEverythingFits(panel: EditorPanel, width: Dp) = runComposeUiTest {
        var selectedSheet: EditorSheet? = null
        setContent {
            Box(Modifier.width(width)) {
                AdjustmentPanelHeader(
                    panel = panel,
                    onSelectPanel = {},
                    onSelectSheet = { selectedSheet = it },
                )
            }
        }
        val rootWidth = with(density) { width.toPx() }
        // A row out of width does not push its last child off the screen: it squeezes it into what is
        // left, down to nothing. That is how Masks disappeared, so every category must keep a whole
        // touch target.
        val touchTarget = with(density) { 48.dp.toPx() }
        val tabs = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        assertEquals("$panel at $width: the five categories", EditorPanel.entries.size, tabs.size)
        tabs.forEachIndexed { index, tab ->
            val bounds = tab.boundsInRoot
            assertTrue(
                "$panel at $width: category $index ends at ${bounds.right}, past $rootWidth",
                bounds.left >= 0f && bounds.right <= rootWidth + 0.5f,
            )
            assertTrue(
                "$panel at $width: category $index is ${bounds.width} wide, under a touch target",
                bounds.width >= touchTarget - 0.5f,
            )
        }
        val chips = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("$panel: its precision tools", panel.subTools().size, chips.fetchSemanticsNodes().size)
        panel.subTools().forEachIndexed { index, sheet ->
            chips[index].assertIsDisplayed().performClick()
            assertEquals(sheet, selectedSheet)
        }
    }

    @Test
    fun `every category fits on a small phone`() {
        EditorPanel.entries.forEach { assertEverythingFits(it, 360.dp) }
    }

    @Test
    fun `every category fits in the narrowest side rail`() {
        EditorPanel.entries.forEach { assertEverythingFits(it, 320.dp) }
    }
}
