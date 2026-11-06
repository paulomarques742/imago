package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.CompositionGuide
import eu.studio742.imago.core.composition.GuideAxis
import eu.studio742.imago.core.designsystem.ImagoUi

internal val ComposerSnapDistance = 6.dp

/** Drawn in stage space, outside artwork layers, so strokes remain readable at any zoom. */
@Composable
internal fun ComposerMovementGuides(
    guides: List<CompositionGuide>,
    origin: Offset,
    pageWidthPx: Float,
    pageHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    if (guides.isEmpty()) return
    val palette = ImagoUi.colors
    Canvas(modifier.fillMaxSize().testTag("composer_movement_guides")) {
        val stroke = 1.dp.toPx()
        val tip = 4.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        fun point(x: Float, y: Float) = origin + Offset(x * pageWidthPx, y * pageHeightPx)
        guides.forEach { guide ->
            when (guide) {
                is CompositionGuide.Alignment -> {
                    val from = if (guide.axis == GuideAxis.X) point(guide.coordinate, guide.from) else point(guide.from, guide.coordinate)
                    val to = if (guide.axis == GuideAxis.X) point(guide.coordinate, guide.to) else point(guide.to, guide.coordinate)
                    drawLine(palette.alignmentGuide, from, to, strokeWidth = stroke, pathEffect = dash)
                }
                is CompositionGuide.Spacing -> {
                    val horizontal = guide.axis == GuideAxis.X
                    val from = if (horizontal) point(guide.from, guide.cross) else point(guide.cross, guide.from)
                    val to = if (horizontal) point(guide.to, guide.cross) else point(guide.cross, guide.to)
                    val along = if (horizontal) Offset(1f, 0f) else Offset(0f, 1f)
                    val across = Offset(-along.y, along.x)
                    drawLine(palette.spacingGuide, from, to, strokeWidth = stroke)
                    // End ticks and inward arrowheads make matching gaps visible without a label.
                    listOf(from to 1f, to to -1f).forEach { (end, direction) ->
                        drawLine(palette.spacingGuide, end - across * tip, end + across * tip, strokeWidth = stroke)
                        if ((to - from).getDistance() >= tip * 4f) {
                            drawLine(palette.spacingGuide, end, end + along * tip * direction + across * tip, strokeWidth = stroke)
                            drawLine(palette.spacingGuide, end, end + along * tip * direction - across * tip, strokeWidth = stroke)
                        }
                    }
                }
            }
        }
    }
}
