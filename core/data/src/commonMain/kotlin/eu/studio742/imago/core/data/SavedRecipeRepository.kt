package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.SavedRecipe

interface SavedRecipeRepository {
    suspend fun list(): List<SavedRecipe>
    suspend fun save(recipe: SavedRecipe)
    suspend fun delete(id: String)
}
