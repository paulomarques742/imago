package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedRecipe(
    val id: String,
    val name: String,
    val collection: String,
    val recipe: EditRecipe,
    val createdAt: String,
    val updatedAt: String,
    val isFavorite: Boolean = false,
    /** When it was last applied. Null while it never has been. */
    val usedAt: String? = null,
)
