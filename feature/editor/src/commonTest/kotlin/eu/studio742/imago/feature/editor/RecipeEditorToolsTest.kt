package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeEditorToolsTest {
    /**
     * The bar holds only what opens a panel, and the recipes. Compare, copy, paste, save and export
     * have their places elsewhere — the top bar, its menu and the recipe library.
     */
    @Test
    fun theBarHoldsOnlyTheTools() {
        assertEquals(
            listOf(EditorTool.ADJUSTMENTS, EditorTool.CROP, EditorTool.RECIPES, EditorTool.HISTORY),
            editorTools(recipeMode = false),
        )
    }

    @Test
    fun aRecipeHasNoCrop() {
        assertEquals(
            listOf(EditorTool.ADJUSTMENTS, EditorTool.RECIPES, EditorTool.HISTORY),
            editorTools(recipeMode = true),
        )
    }
}
