package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

/** The process from which a recipe's [EditRecipe.retouches] are drawn. */
const val RETOUCH_PROCESS_VERSION = 22

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
 * [fill] is where a [RetouchMode.REMOVE] takes each part of its content from; the other modes have none.
 */
@Serializable
data class Retouch(
    val id: String,
    val mode: RetouchMode,
    val strokes: List<BrushStroke>,
    val sourceX: Float = 0f,
    val sourceY: Float = 0f,
    val opacity: Float = 100f,
    val fill: RemoveFill? = null,
)

/**
 * The content a Remove synthesised, kept as where it came from and not as pixels: pixels never leave
 * the device, and synthesising again on another device would not give the same result, because the
 * photo does not decode to the same pixels everywhere.
 *
 * It is a grid of square cells over the brush raster — the same raster the area is painted on, a fixed
 * size for the photo's aspect ratio — and each cell holds the offset, in raster texels, to where its
 * content is taken from. [left] and [top] are the first cell's corner and [cell] the side of a cell,
 * both in texels; [columns] by [rows] cells, row by row. [runs] are the offsets run-length encoded as
 * `count, dx, dy, count, dx, dy, …`: the synthesis copies whole regions from the same place, so long
 * runs of one offset are the rule and the JSON stays small.
 */
@Serializable
data class RemoveFill(
    val cell: Int,
    val left: Int,
    val top: Int,
    val columns: Int,
    val rows: Int,
    val runs: List<Int>,
) {
    /** The offsets as `dx, dy` per cell, row by row; null when [runs] do not cover the grid exactly. */
    fun offsets(): IntArray? {
        if (cell <= 0 || columns <= 0 || rows <= 0 || runs.size % 3 != 0) return null
        val cells = columns * rows
        val out = IntArray(cells * 2)
        var at = 0
        for (run in runs.indices step 3) {
            val count = runs[run]
            if (count <= 0 || at + count > cells) return null
            repeat(count) {
                out[(at + it) * 2] = runs[run + 1]
                out[(at + it) * 2 + 1] = runs[run + 2]
            }
            at += count
        }
        return if (at == cells) out else null
    }

    companion object {
        /** A fill from offsets as `dx, dy` per cell, row by row. */
        fun of(cell: Int, left: Int, top: Int, columns: Int, rows: Int, offsets: IntArray): RemoveFill {
            require(offsets.size == columns * rows * 2)
            val runs = ArrayList<Int>()
            var index = 0
            while (index < columns * rows) {
                val dx = offsets[index * 2]
                val dy = offsets[index * 2 + 1]
                var count = 1
                while (index + count < columns * rows && offsets[(index + count) * 2] == dx && offsets[(index + count) * 2 + 1] == dy) count++
                runs += count
                runs += dx
                runs += dy
                index += count
            }
            return RemoveFill(cell, left, top, columns, rows, runs)
        }
    }
}
