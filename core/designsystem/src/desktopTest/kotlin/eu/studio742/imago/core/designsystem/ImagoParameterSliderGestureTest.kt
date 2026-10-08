package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an adjustment row answers a finger and a mouse.
 *
 * Testers found that scrolling the list of adjustments kept setting values: the slider took the value
 * to wherever the finger landed, before a scroll could be told apart. And the double tap that resets
 * only worked on the name, not on the track.
 */
@OptIn(ExperimentalTestApi::class)
class ImagoParameterSliderGestureTest {
    private class Row {
        var value by mutableStateOf(0f)
        val changes = mutableListOf<Float>()
        var finished = 0
        var resets = 0
    }

    /** At density 1 a dp is a pixel: the row is 300 px wide and the ring travels 282 of them. */
    private val width = 300f
    private val inset = 9f
    private val usable = width - inset * 2

    @Composable
    private fun Slider(row: Row, label: String = "Exposure", enabled: Boolean = true) {
        ImagoParameterSlider(
            label = label,
            value = row.value,
            neutral = 0f,
            range = -100f..100f,
            pointerKey = label,
            editingKey = null,
            onEditing = {},
            valueText = { "%+.0f".format(it) },
            onValueChange = {
                row.value = it
                row.changes += it
            },
            onValueChangeFinished = { row.finished++ },
            onReset = {
                row.resets++
                row.value = 0f
            },
            enabled = enabled,
        )
    }

    private fun ComposeUiTest.show(row: Row, enabled: Boolean = true) {
        setContent { Box(Modifier.width(width.dp)) { Slider(row, enabled = enabled) } }
    }

    private val track = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun `a touch on the track leaves the value alone`() = runComposeUiTest {
        val row = Row()
        show(row)
        onNode(track).performTouchInput { click(Offset(inset + usable * 0.1f, centerY)) }
        waitForIdle()
        assertTrue("a touch moved the value: ${row.changes}", row.changes.isEmpty())
    }

    @Test
    fun `a drag up or down over the track leaves the value alone`() = runComposeUiTest {
        val row = Row()
        show(row)
        // A finger never goes perfectly straight. This one drifts sideways past the touch slop, but
        // far less than it goes up: it is a vertical gesture.
        onNode(track).performTouchInput { swipe(Offset(centerX, centerY), Offset(centerX + 30f, centerY - 150f)) }
        waitForIdle()
        assertTrue("a vertical drag moved the value: ${row.changes}", row.changes.isEmpty())
    }

    @Test
    fun `a sideways drag moves the value by how far the finger went, not to where it landed`() = runComposeUiTest {
        val row = Row()
        show(row)
        // It lands near the left end, far from the ring in the middle, and travels a quarter of the
        // track: about +50 on a range of 200, never the -80 of where it landed.
        val from = Offset(inset + usable * 0.1f, 24f)
        onNode(track).performTouchInput { swipe(from, from + Offset(usable / 4f, 0f), durationMillis = 300) }
        waitForIdle()
        assertTrue("the value went to ${row.value}", row.value in 35f..50.5f)
        assertTrue("it jumped first: ${row.changes.first()}", row.changes.first() > 0f)
        assertEquals("one change for the history", 1, row.finished)
    }

    @Test
    fun `a double tap on the track resets the value`() = runComposeUiTest {
        val row = Row().apply { value = 40f }
        show(row)
        onNode(track).performTouchInput { doubleClick(Offset(inset + usable * 0.2f, centerY)) }
        waitForIdle()
        assertEquals(1, row.resets)
        assertEquals(0f, row.value)
    }

    @Test
    fun `a double tap on the name still resets the value`() = runComposeUiTest {
        val row = Row().apply { value = 40f }
        show(row)
        onNodeWithText("Exposure").performTouchInput { doubleClick() }
        waitForIdle()
        assertEquals(1, row.resets)
    }

    @Test
    fun `a mouse click on the track takes the value to it`() = runComposeUiTest {
        val row = Row()
        show(row)
        onNode(track).performMouseInput { click(Offset(inset + usable * 0.75f, centerY)) }
        waitForIdle()
        assertEquals(50f, row.value, 1f)
        assertEquals(1, row.finished)
    }

    @Test
    fun `a disabled row does not move`() = runComposeUiTest {
        val row = Row()
        show(row, enabled = false)
        onNode(track).performTouchInput { swipe(Offset(inset, centerY), Offset(inset + usable / 2f, centerY)) }
        waitForIdle()
        assertTrue(row.changes.isEmpty())
    }

    @Test
    fun `scrolling a list of adjustments scrolls it and touches no value`() = runComposeUiTest {
        val rows = List(8) { Row() }
        lateinit var scroll: ScrollState
        setContent {
            scroll = rememberScrollState()
            Column(Modifier.width(width.dp).height(240.dp).verticalScroll(scroll)) {
                rows.forEachIndexed { index, row -> Slider(row, label = "Adjustment $index") }
            }
        }
        onNodeWithText("Adjustment 1").performTouchInput {
            // Starting on the name and sweeping up over the tracks below it, with the usual drift.
            swipe(Offset(width * 0.3f, centerY), Offset(width * 0.3f + 15f, centerY - 200f), durationMillis = 300)
        }
        waitForIdle()
        assertTrue("the list did not scroll", scroll.value > 0)
        rows.forEachIndexed { index, row ->
            assertTrue("adjustment $index moved: ${row.changes}", row.changes.isEmpty())
        }
    }

    @Test
    fun `accessibility services can still set the value`() = runComposeUiTest {
        val row = Row()
        show(row)
        onNode(track).performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        waitForIdle()
        assertEquals(30f, row.value)
        assertEquals(1, row.finished)
    }
}
