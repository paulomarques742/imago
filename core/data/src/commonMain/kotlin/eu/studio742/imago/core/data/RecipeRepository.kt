package eu.studio742.imago.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RecipeConflictVersion

interface RecipeRepository {
    suspend fun get(assetId: String): EditRecipe?
    suspend fun save(recipe: EditRecipe)

    /** The versions of this photo that lost conflicts with other devices. */
    fun conflicts(assetId: String): Flow<List<RecipeConflictVersion>> = flowOf(emptyList())
}

