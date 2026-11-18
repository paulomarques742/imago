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
    /** The format it was imported from; null in one made in IMAGO. Sharing such a recipe is asked about. */
    val origin: RecipeOrigin? = null,
) {
    /**
     * A recipe with none of [RecipePart.FINISHING] was saved before those were parts, or has nothing in
     * them. It carries the ones it holds a value for, and leaves the others to the photo: one saved
     * with the default sharpening must not take away the sharpening of the RAW it is put on. Before
     * process 21 those values were stored and ignored, so nothing in them was ever chosen.
     */
    val appliedParts: Set<RecipePart>
        get() {
            val chosen = parts ?: RecipePart.LEGACY
            if (chosen.any { it in RecipePart.FINISHING } || recipe.processVersion < SHARPENING_PROCESS_VERSION) return chosen
            val neutral = EditRecipe(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "")
            return chosen + RecipePart.FINISHING.filter { neutral.withPartsFrom(recipe, setOf(it)) != neutral }
        }
}

/** The file format a recipe came into IMAGO from. Many of these are paid packs under a licence. */
enum class RecipeOrigin {
    XMP,
    DNG,
    LRTEMPLATE,
    CUBE,


    /** A format only a newer app knows, or a value nobody wrote. The mark must survive either. */
    OTHER,
    ;

    companion object {
        fun parse(name: String?): RecipeOrigin? =
            if (name.isNullOrBlank()) null else entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/**
 * The recipe as it is stored for [chosen]. Choosing none of [RecipePart.FINISHING] clears their values,
 * since a recipe that held values and named none of those parts would read as one saved before them.
 */
fun EditRecipe.storedForParts(chosen: Set<RecipePart>?): EditRecipe =
    if (chosen == null || chosen.any { it in RecipePart.FINISHING }) {
        this
    } else {
        copy(detail = Detail(), calibration = Calibration(), lens = LensCorrection())
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
