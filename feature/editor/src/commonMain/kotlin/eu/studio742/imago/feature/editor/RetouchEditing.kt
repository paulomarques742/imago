package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Retouch
import eu.studio742.imago.core.model.RetouchMode
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.editor_remove_mode_clone
import eu.studio742.imago.feature.editor.resources.editor_remove_mode_heal
import eu.studio742.imago.feature.editor.resources.editor_remove_mode_remove
import org.jetbrains.compose.resources.StringResource

/** A slider of the Remove tool. Size and Feather are the brush's; Opacity belongs to the correction. */
enum class RetouchSetting(val neutral: Float, val range: ClosedFloatingPointRange<Float>) {
    SIZE(25f, 1f..100f),
    FEATHER(50f, 0f..100f),
    OPACITY(100f, 1f..100f),
}

/**
 * The Remove tool in the hand: the mode a new correction is made in and the brush it is painted with.
 * Not part of the recipe — each correction keeps what it was made with — but of the device, like the
 * masks' brush.
 */
data class RetouchTool(
    val mode: RetouchMode = RetouchMode.REMOVE,
    val size: Float = RetouchSetting.SIZE.neutral,
    val feather: Float = RetouchSetting.FEATHER.neutral,
    val opacity: Float = RetouchSetting.OPACITY.neutral,
) {
    fun value(setting: RetouchSetting): Float = when (setting) {
        RetouchSetting.SIZE -> size
        RetouchSetting.FEATHER -> feather
        RetouchSetting.OPACITY -> opacity
    }

    fun with(setting: RetouchSetting, value: Float): RetouchTool {
        val clamped = value.coerceIn(setting.range.start, setting.range.endInclusive)
        return when (setting) {
            RetouchSetting.SIZE -> copy(size = clamped)
            RetouchSetting.FEATHER -> copy(feather = clamped)
            RetouchSetting.OPACITY -> copy(opacity = clamped)
        }
    }

    /** `mode,size,feather,opacity` — read back by [decode]. */
    fun encode(): String = "${mode.name},$size,$feather,$opacity"

    companion object {
        /** What was stored, or the defaults; a value this version cannot read is forgotten, not an error. */
        fun decode(stored: String?): RetouchTool {
            val parts = stored.orEmpty().split(',')
            if (parts.size != 4) return RetouchTool()
            fun number(index: Int, setting: RetouchSetting) =
                (parts[index].toFloatOrNull() ?: setting.neutral).coerceIn(setting.range.start, setting.range.endInclusive)
            return RetouchTool(
                mode = RetouchMode.entries.firstOrNull { it.name == parts[0] } ?: RetouchMode.REMOVE,
                size = number(1, RetouchSetting.SIZE),
                feather = number(2, RetouchSetting.FEATHER),
                opacity = number(3, RetouchSetting.OPACITY),
            )
        }
    }
}

internal val RetouchMode.labelRes: StringResource
    get() = when (this) {
        RetouchMode.REMOVE -> Res.string.editor_remove_mode_remove
        RetouchMode.HEAL -> Res.string.editor_remove_mode_heal
        RetouchMode.CLONE -> Res.string.editor_remove_mode_clone
    }

fun EditRecipe.retouch(id: String): Retouch? = retouches.firstOrNull { it.id == id }

/** A new correction at the end of the list: drawn last, over what the others left. */
fun EditRecipe.withRetouch(retouch: Retouch): EditRecipe = copy(retouches = retouches + retouch)

fun EditRecipe.withoutRetouch(id: String): EditRecipe = copy(retouches = retouches.filterNot { it.id == id })

fun EditRecipe.withRetouchChanged(id: String, transform: (Retouch) -> Retouch): EditRecipe =
    copy(retouches = retouches.map { if (it.id == id) transform(it) else it })

/** One more point on the stroke being painted, kept to the brush's four decimals. */
fun EditRecipe.withRetouchStrokeExtended(id: String, x: Float, y: Float): EditRecipe = withRetouchChanged(id) { retouch ->
    val last = retouch.strokes.lastOrNull() ?: return@withRetouchChanged retouch
    val extended = last.copy(points = last.points + roundedBrushCoordinate(x) + roundedBrushCoordinate(y))
    retouch.copy(strokes = retouch.strokes.dropLast(1) + extended)
}

/** The first point of a correction's area: where its pin sits, as in Lightroom. */
fun Retouch.pin(): Pair<Float, Float>? = strokes.firstOrNull()?.points?.takeIf { it.size >= 2 }?.let { it[0] to it[1] }

/** Where a Heal's or a Clone's content comes from: the pin moved by the source offset. A Remove has none. */
fun Retouch.sourcePin(): Pair<Float, Float>? =
    if (mode == RetouchMode.REMOVE) null else pin()?.let { (x, y) -> x + sourceX to y + sourceY }

/**
 * A correction's area moved by [dx], [dy] in image units. Its content stays where it was taken from,
 * so a Heal's or a Clone's offset shrinks by as much; a Remove's fill no longer fits and goes, to be
 * solved again for the new place.
 */
fun Retouch.movedBy(dx: Float, dy: Float): Retouch = copy(
    strokes = strokes.map { stroke ->
        stroke.copy(points = stroke.points.mapIndexed { i, value -> roundedBrushCoordinate(value + if (i % 2 == 0) dx else dy) })
    },
    sourceX = if (mode == RetouchMode.REMOVE) 0f else sourceX - dx,
    sourceY = if (mode == RetouchMode.REMOVE) 0f else sourceY - dy,
    fill = null,
)

/** The stroke a new correction starts with, painted with the tool in the hand. */
internal fun RetouchTool.firstStroke(x: Float, y: Float, radius: Float) = BrushStroke(
    points = listOf(roundedBrushCoordinate(x), roundedBrushCoordinate(y)),
    radius = radius,
    feather = feather,
)
