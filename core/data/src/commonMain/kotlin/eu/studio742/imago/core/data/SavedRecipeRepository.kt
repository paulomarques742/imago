package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.SavedRecipe

interface SavedRecipeRepository {
    suspend fun list(): List<SavedRecipe>
    suspend fun save(recipe: SavedRecipe)
    suspend fun delete(id: String)

    /** The heart and last use on the app's presets. A preset without a mark is neither. */
    suspend fun builtInMarks(): List<BuiltInRecipeMark>
    suspend fun saveBuiltInMark(mark: BuiltInRecipeMark)
}
