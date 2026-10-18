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
    /**
     * What applying it writes over a photo. [recipe] is kept whole, so that a part switched on later
     * still has its values. Null in a recipe saved before the choice existed.
     */
    val parts: Set<RecipePart>? = null,
) {
    val appliedParts: Set<RecipePart> get() = parts ?: RecipePart.LEGACY
}

/**
 * What the person marked on one of the app's presets. The preset itself is code and never changes;
 * only the heart and the last use are theirs, keyed by the preset's stable id.
 */
@Serializable
data class BuiltInRecipeMark(
    val id: String,
    val isFavorite: Boolean = false,
    /** When it was last applied. Null while it never has been. */
    val usedAt: String? = null,
)
