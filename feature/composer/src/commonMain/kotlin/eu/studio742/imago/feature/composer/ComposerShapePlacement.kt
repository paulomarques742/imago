package eu.studio742.imago.feature.composer

import androidx.compose.ui.geometry.Offset
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.ShapeKind
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** Where a shape lands on the stage, in the model's units, and how far it is turned. */
internal data class ShapePlacement(val bounds: NormalizedRect, val rotationDegrees: Float)

/** A tap drops a shape of this fraction of the page's width; the same size the drag starts from. */
private const val TAP_SHAPE_WIDTH = .3f
private const val TAP_LINE_WIDTH = .4f

/**
 * Turns a touch on the stage into a shape.
 *
 * A tap puts a default shape centred on the finger. A drag draws it where the finger went: rectangles
 * and ellipses come out perfect — a square and a circle, the side being the longer of the two moves,
 * growing from where the finger came down — and the inspector's sizes make them rectangles and
 * ellipses afterwards. Lines and arrows run from the first point to the last, turned to fit.
 *
 * Positions are in stage pixels, the stage being [pageCount] pages side by side.
 */
internal fun shapePlacement(
    kind: ShapeKind,
    start: Offset,
    end: Offset,
    pageWidthPx: Float,
    pageHeightPx: Float,
    pageCount: Int,
    tapSlopPx: Float,
): ShapePlacement {
    val dragged = (end - start).getDistance() > tapSlopPx
    val stageWidthPx = pageWidthPx * pageCount
    return when (kind) {
        ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> {
            val side = min(
                if (dragged) maxOf(abs(end.x - start.x), abs(end.y - start.y)) else pageWidthPx * TAP_SHAPE_WIDTH,
                min(pageHeightPx, stageWidthPx),
            ).coerceAtLeast(1f)
            val left = when {
                !dragged -> start.x - side / 2f
                end.x < start.x -> start.x - side
                else -> start.x
            }
            val top = when {
                !dragged -> start.y - side / 2f
                end.y < start.y -> start.y - side
                else -> start.y
            }
            // Pushed back inside the stage rather than cut: a shape started at the edge keeps its size.
            val x = left.coerceIn(0f, stageWidthPx - side)
            val y = top.coerceIn(0f, pageHeightPx - side)
            ShapePlacement(NormalizedRect(x / pageWidthPx, y / pageHeightPx, side / pageWidthPx, side / pageHeightPx), 0f)
        }
        ShapeKind.LINE, ShapeKind.ARROW -> {
            val length = (if (dragged) hypot(end.x - start.x, end.y - start.y) else pageWidthPx * TAP_LINE_WIDTH).coerceAtLeast(1f)
            val angle = if (dragged) Math.toDegrees(atan2(end.y - start.y, end.x - start.x).toDouble()).toFloat() else 0f
            val centre = if (dragged) Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f) else start
            // The arrow's head is a fraction of the box's shorter side, so it needs a taller box than the line.
            val thickness = if (kind == ShapeKind.ARROW) length * .4f else maxOf(length * .08f, 16f)
            val width = length / pageWidthPx
            val height = thickness / pageHeightPx
            ShapePlacement(
                NormalizedRect(centre.x / pageWidthPx - width / 2f, centre.y / pageHeightPx - height / 2f, width, height),
                angle,
            )
        }
    }
}
