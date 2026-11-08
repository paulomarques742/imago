package eu.studio742.imago.feature.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.composition.hasVariablePressure
import eu.studio742.imago.core.composition.strokeWidthAt

/**
 * A drawing element in its box, as the stage shows it.
 *
 * The stage and the page miniatures share it: the miniatures used to paint a freehand stroke as a
 * rectangle of its colour, so a signature or a doodle showed up in the page strip and the project
 * list as a solid square.
 */
internal fun DrawScope.drawCompositionDrawing(element: CompositionElement.Drawing, unit: Float) {
    if (element.id.startsWith("layout-decoration:")) {
        drawCompositionLayoutStroke(element, unit)
        return
    }
    // The eraser has nothing of its own to show: its strokes only ever cut the ones underneath.
    if (element.points.size < 2 || element.kind == StrokeKind.ERASER) return
    val color = Color(element.colorArgb)
    val alpha = if (element.kind == StrokeKind.MARKER) .45f else 1f
    if (element.points.hasVariablePressure()) {
        // Without a single width for the whole stroke, no `Path` will do: it is drawn segment by
        // segment, each with the width of the average pressure of its ends. Only stylus strokes go
        // through here — the others stay in a single `Path`, which is cheaper and gives exactly the
        // same result.
        element.points.zipWithNext { from, to ->
            if (!to.startsStroke) drawLine(
                color = color,
                start = Offset(from.x * size.width, from.y * size.height),
                end = Offset(to.x * size.width, to.y * size.height),
                strokeWidth = strokeWidthAt(element.strokeWidth * unit, (from.pressure + to.pressure) / 2f),
                cap = StrokeCap.Round,
                alpha = alpha,
            )
        }
    } else {
        val path = Path().apply {
            moveTo(element.points.first().x * size.width, element.points.first().y * size.height)
            element.points.drop(1).forEach { if (it.startsStroke) moveTo(it.x * size.width, it.y * size.height) else lineTo(it.x * size.width, it.y * size.height) }
        }
        // Round caps and joins, as the exporter always drew them: square ones made a curved stroke
        // faceted on screen and smooth in the file.
        drawPath(path, color, alpha = alpha,
            style = Stroke(element.strokeWidth * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
