package eu.studio742.imago.core.model

/**
 * The version of a recipe that lost a conflict between devices.
 *
 * It shows at the top of the editor's history, apart from the undo path; applying it is a new step
 * in the history.
 */
data class RecipeConflictVersion(
    val id: Long,
    val recipe: EditRecipe,
    val deviceName: String,
    val editedAt: String,
)
