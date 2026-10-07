package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeEditorToolsTest {
    @Test
    fun photoHasEveryToolButTheRecipeSave() {
        val tools = editorTools(recipeMode = false)
        assertEquals(EditorTool.entries - EditorTool.SAVE_EDITS, tools)
    }

    @Test
    fun recipeHasNoCropAndSaveStandsWhereExportWas() {
        val photo = editorTools(recipeMode = false)
        val recipe = editorTools(recipeMode = true)

        assertFalse(EditorTool.CROP in recipe)
        assertFalse(EditorTool.EXPORT in recipe)
        assertFalse("Save and Duplicate already cover it", EditorTool.SAVE_RECIPE in recipe)
        assertTrue(EditorTool.SAVE_EDITS in recipe)
        // Same place in the bar: the tools before Export are still before Save.
        val beforeExport = photo.takeWhile { it != EditorTool.EXPORT } - EditorTool.CROP
        assertEquals(beforeExport, recipe.takeWhile { it != EditorTool.SAVE_EDITS })
    }
}
