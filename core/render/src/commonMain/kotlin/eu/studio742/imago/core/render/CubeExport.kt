package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RAW_PROFILE_COLOR
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.withPartsFrom
import java.util.Locale

/** What a LUT cannot hold of a recipe, said before the file is written. */
enum class CubeLeftOut {
    /** Texture, Clarity, Dehaze, Sharpening, noise reduction, vignette and grain look at the neighbourhood or add detail. */
    DETAIL_AND_EFFECTS,

    /** Highlights and Shadows decide from the neighbourhood of each pixel, and a table has no neighbours. */
    HIGHLIGHTS_AND_SHADOWS,

    MASKS,
    REMOVE,
    GEOMETRY,
}

/** [leftOut] is empty when the table says everything the recipe does to a colour. */
class CubeExport(val fileName: String, val bytes: ByteArray, val leftOut: Set<CubeLeftOut>)

/** Points per side of the table written: the usual size of a photographic LUT. */
const val CUBE_EXPORT_SIZE = 33

/** What a colour goes through on its own, with nothing of its neighbours: the only parts a table can hold. */
private val POINT_PARTS: Set<RecipePart> = setOf(
    RecipePart.EXPOSURE, RecipePart.CONTRAST, RecipePart.WHITES, RecipePart.BLACKS, RecipePart.TONE_CURVE,
    RecipePart.WHITE_BALANCE, RecipePart.VIBRANCE, RecipePart.SATURATION, RecipePart.COLOR_MIXER, RecipePart.COLOR_GRADING,
    RecipePart.LUT,
)

private val DETAIL_PARTS = setOf(
    RecipePart.TEXTURE, RecipePart.CLARITY, RecipePart.DEHAZE, RecipePart.SHARPENING,
    RecipePart.LUMINANCE_NOISE_REDUCTION, RecipePart.COLOR_NOISE_REDUCTION, RecipePart.VIGNETTE, RecipePart.GRAIN,
)

private fun blank() = EditRecipe(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "")

/**
 * The recipe's colour as a `.cube` file: what it does to each colour on its own, which is all a table can say.
 *
 * The table is made by running a grid of colours through the same CPU pipeline the export uses, so that
 * what another program shows with this file is what the recipe draws here. Only the parts the recipe
 * applies count, and what a table cannot hold is named in [CubeExport.leftOut].
 */
fun encodeCubeFile(saved: SavedRecipe, fallbackName: String): CubeExport {
    val parts = saved.appliedParts
    val recipe = saved.recipe
    val size = CUBE_EXPORT_SIZE
    val grid = FloatArray(size * size * size * 3)
    var at = 0
    for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
        grid[at++] = r / (size - 1f)
        grid[at++] = g / (size - 1f)
        grid[at++] = b / (size - 1f)
    }
    // Display-referred and not linear: the grid holds colours as a screen shows them, which is how a LUT is read.
    // Row y is blue, the column is green and then red: the order of the file, so that it is read straight.
    val surface = FloatPixelBuffer(size * size, size, grid, linear = false)
    val pointwise = blank().copy(processVersion = recipe.processVersion).withPartsFrom(recipe, parts intersect POINT_PARTS)
    val parameters = pointwise.copy(profile = RAW_PROFILE_COLOR).toRenderParameters().copy(rawProfile = RawProfile.NONE, rawExposure = 0f)
    BitmapPhotoProcessor.renderInPlace(surface, parameters)

    val title = saved.name.filterNot(Char::isISOControl).replace('"', '\'').trim().ifEmpty { fallbackName }
    val text = buildString {
        append("# Made by IMAGO\n")
        append("TITLE \"").append(title).append("\"\n")
        append("LUT_3D_SIZE ").append(size).append('\n')
        append("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        for (point in 0 until size * size * size) {
            for (channel in 0..2) {
                if (channel > 0) append(' ')
                append(String.format(Locale.ROOT, "%.6f", surface.rgb[point * 3 + channel].coerceIn(0f, 1f)))
            }
            append('\n')
        }
    }
    return CubeExport(
        fileName = cubeFileName(saved.name, fallbackName),
        bytes = text.encodeToByteArray(),
        leftOut = recipe.leftOutOfCube(parts),
    )
}

/** A file name that every system accepts, as the recipe files get theirs. */
private fun cubeFileName(recipeName: String, fallback: String): String {
    val stem = recipeName
        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("")
        .trim(' ', '.')
        .take(80)
        .trimEnd { it.isHighSurrogate() }
        .trim(' ', '.')
    return "${stem.ifEmpty { fallback }}.cube"
}

private fun EditRecipe.leftOutOfCube(parts: Set<RecipePart>): Set<CubeLeftOut> = buildSet {
    val none = blank()
    fun holds(part: RecipePart) = part in parts && none.withPartsFrom(this@leftOutOfCube, setOf(part)) != none
    if (DETAIL_PARTS.any(::holds)) add(CubeLeftOut.DETAIL_AND_EFFECTS)
    if (holds(RecipePart.HIGHLIGHTS) || holds(RecipePart.SHADOWS)) add(CubeLeftOut.HIGHLIGHTS_AND_SHADOWS)
    if (holds(RecipePart.MASKS)) add(CubeLeftOut.MASKS)
    if (holds(RecipePart.REMOVE)) add(CubeLeftOut.REMOVE)
    if (RecipePart.GEOMETRY.any(::holds)) add(CubeLeftOut.GEOMETRY)
}
