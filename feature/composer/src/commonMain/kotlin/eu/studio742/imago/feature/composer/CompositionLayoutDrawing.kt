package eu.studio742.imago.feature.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import eu.studio742.imago.core.composition.CompositionElement

internal fun DrawScope.drawCompositionLayoutStroke(element: CompositionElement.Drawing, unit: Float, opacity: Float = 1f) {
    if (element.points.size < 2) return
    val path = Path().apply {
        moveTo(element.points.first().x * size.width, element.points.first().y * size.height)
        element.points.drop(1).forEach { point ->
            if (point.startsStroke) moveTo(point.x * size.width, point.y * size.height)
            else lineTo(point.x * size.width, point.y * size.height)
        }
    }
    if (element.id.endsWith(":separators")) {
        clipRect {
            drawPath(path, Color(element.colorArgb), alpha = opacity,
                style = Stroke(element.strokeWidth * unit, cap = StrokeCap.Butt, join = StrokeJoin.Miter))
        }
    } else drawPath(path, Color(element.colorArgb), alpha = opacity,
        style = Stroke(element.strokeWidth * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
