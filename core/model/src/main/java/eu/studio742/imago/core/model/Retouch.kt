package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

/** The process from which a recipe's [EditRecipe.retouches] are drawn. */
const val RETOUCH_PROCESS_VERSION = 18

/** Lightroom's Remove tool, in its three modes that need no generative model. */
@Serializable
enum class RetouchMode {
    /** Fills the area with content synthesised from the rest of the photo. */
    REMOVE,

    /** Copies another area and takes the colour and light of the destination's surroundings. */
    HEAL,

    /** Copies another area as it is. */
    CLONE,
}

/**
 * One correction of the Remove tool: an area of the photo and where its content comes from.
 *
 * Like the masks, everything is in normalised coordinates of the **original image**, before geometry:
 * a correction is put over a speck of dust, and it has to stay on it when the photo is cropped or
 * straightened. Corrections are applied in list order, each over the photo as the previous ones left
 * it, so one can take from an area another already healed.
 *
 * [strokes] are the area, painted with the brush of the masks — [BrushStroke.radius] and
 * [BrushStroke.feather] are the tool's Size and Feather; flow, density and the eraser are not used.
 * [sourceX] and [sourceY] are the offset from the area to where its content is taken from, in units of
 * the image's width and height; they are what dragging the source moves. [opacity] goes from 0 to 100.
 */
@Serializable
data class Retouch(
    val id: String,
    val mode: RetouchMode,
    val strokes: List<BrushStroke>,
    val sourceX: Float = 0f,
    val sourceY: Float = 0f,
    val opacity: Float = 100f,
)
