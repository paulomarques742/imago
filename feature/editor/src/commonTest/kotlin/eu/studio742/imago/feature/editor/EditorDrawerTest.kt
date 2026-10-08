package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bar under the photo and the drawer above it.
 *
 * Testers opened a photo and saw no controls, then found that leaving a tool took the whole bar with
 * it. The bar is now always there; its buttons open and close their tool, and the drawer's handle
 * moves it between closed, its usual height and expanded.
 */
class EditorDrawerTest {
    @Test
    fun aClosedToolOpensAndTheOpenOneClosesAgain() {
        assertEquals(
            ToolTap.Open(EditorSheet.ADJUSTMENTS),
            toolTap(EditorTool.ADJUSTMENTS, openTool = EditorTool.ADJUSTMENTS, drawer = DrawerDetent.CLOSED, rail = false),
        )
        assertEquals(
            ToolTap.Close,
            toolTap(EditorTool.ADJUSTMENTS, openTool = EditorTool.ADJUSTMENTS, drawer = DrawerDetent.NORMAL, rail = false),
        )
        assertEquals(
            ToolTap.Close,
            toolTap(EditorTool.CROP, openTool = EditorTool.CROP, drawer = DrawerDetent.EXPANDED, rail = false),
        )
    }

    @Test
    fun anotherToolTakesThePanelOver() {
        assertEquals(
            ToolTap.Open(EditorSheet.HISTORY),
            toolTap(EditorTool.HISTORY, openTool = EditorTool.ADJUSTMENTS, drawer = DrawerDetent.NORMAL, rail = false),
        )
    }

    @Test
    fun inARailNothingCloses() {
        // There is no drawer there: tapping the open tool goes back to its first panel.
        assertEquals(
            ToolTap.Open(EditorSheet.ADJUSTMENTS),
            toolTap(EditorTool.ADJUSTMENTS, openTool = EditorTool.ADJUSTMENTS, drawer = DrawerDetent.CLOSED, rail = true),
        )
    }

    @Test
    fun recipesOpenTheirLibraryWhateverIsOpen() {
        assertEquals(
            ToolTap.OpenLibrary,
            toolTap(EditorTool.RECIPES, openTool = EditorTool.ADJUSTMENTS, drawer = DrawerDetent.NORMAL, rail = false),
        )
    }

    @Test
    fun aReleasedHandleLandsOnTheNearestDetent() {
        val normal = 300f
        val expanded = 600f
        assertEquals(DrawerDetent.CLOSED, settleDetent(100f, velocity = 0f, normal = normal, expanded = expanded))
        assertEquals(DrawerDetent.NORMAL, settleDetent(380f, velocity = 0f, normal = normal, expanded = expanded))
        assertEquals(DrawerDetent.EXPANDED, settleDetent(520f, velocity = 0f, normal = normal, expanded = expanded))
    }

    @Test
    fun aFlickGoesOnToTheNextDetentThatWay() {
        val normal = 300f
        val expanded = 600f
        // Up is negative. A short flick up from just over the usual height still expands.
        assertEquals(DrawerDetent.EXPANDED, settleDetent(320f, velocity = -900f, normal = normal, expanded = expanded))
        // Down from just under the expanded height goes to the usual one, not all the way closed.
        assertEquals(DrawerDetent.NORMAL, settleDetent(560f, velocity = 900f, normal = normal, expanded = expanded))
        assertEquals(DrawerDetent.CLOSED, settleDetent(280f, velocity = 900f, normal = normal, expanded = expanded))
    }

    @Test
    fun aPanelThatDoesNotGrowHasNoExpandedDetent() {
        // The curve or the crop: their height is what their control needs.
        assertEquals(DrawerDetent.NORMAL, settleDetent(310f, velocity = -900f, normal = 300f, expanded = 300f))
    }
}
