package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun ComposerGridDividers(
    layout: CompositionLayout, pageAspect: Float, origin: Offset, pageWidthPx: Float, pageHeightPx: Float,
    onBegin: () -> Unit, onEnd: () -> Unit, onAdjust: (String, Int, Float) -> Unit,
) {
    val colors = ImagoUi.colors
    val radius = with(LocalDensity.current) { 22.dp.toPx() }
    val visualRadius = with(LocalDensity.current) { 6.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        layout.geometry(pageAspect).dividers.forEachIndexed { ordinal, divider ->
            val centre = dividerPoint(layout, divider, origin, pageWidthPx, pageHeightPx)
            val description = stringResource(Res.string.composer_grid_divider_n, ordinal + 1)
            val decrease = stringResource(Res.string.composer_grid_decrease_area)
            val increase = stringResource(Res.string.composer_grid_increase_area)
            Canvas(Modifier.offset { IntOffset((centre.x - radius).roundToInt(), (centre.y - radius).roundToInt()) }
                .size(44.dp).testTag("grid-divider-${divider.splitId}-${divider.index}").semantics {
                    contentDescription = description
                    customActions = listOf(CustomAccessibilityAction(decrease) { onBegin(); onAdjust(divider.splitId, divider.index, -.05f); onEnd(); true },
                        CustomAccessibilityAction(increase) { onBegin(); onAdjust(divider.splitId, divider.index, .05f); onEnd(); true })
                }) {
                drawCircle(colors.surface, visualRadius)
                drawCircle(colors.accent, visualRadius, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private fun dividerPoint(layout: CompositionLayout, divider: LayoutDivider, origin: Offset, width: Float, height: Float): Offset {
    val b = layout.frame.bounds
    val x = ((divider.x1 + divider.x2) / 2f - .5f) * b.width * width
    val y = ((divider.y1 + divider.y2) / 2f - .5f) * b.height * height
    val (rx, ry) = rotateVector(x, y, layout.frame.rotationDegrees)
    return origin + Offset((b.x + b.width / 2f) * width + rx, (b.y + b.height / 2f) * height + ry)
}

/** The stage ancestor owns input; a full-stage sibling would hide the layers below from hit testing. */
@Composable
internal fun Modifier.composerGridDividerGestures(
    layout: CompositionLayout?, pageAspect: Float, origin: Offset, pageWidthPx: Float, pageHeightPx: Float,
    onBegin: () -> Unit, onEnd: () -> Unit, onAdjust: (String, Int, Float) -> Unit,
): Modifier {
    val liveLayout by rememberUpdatedState(layout); val liveOrigin by rememberUpdatedState(origin)
    val liveAdjust by rememberUpdatedState(onAdjust)
    val liveBegin by rememberUpdatedState(onBegin); val liveEnd by rememberUpdatedState(onEnd)
    val radius = with(LocalDensity.current) { 22.dp.toPx() }
    return pointerInput(layout?.groupId, pageWidthPx, pageHeightPx) {
        if (layout == null) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
            val initial = liveLayout ?: return@awaitEachGesture
            fun point(current: CompositionLayout, divider: LayoutDivider) = dividerPoint(current, divider, liveOrigin, pageWidthPx, pageHeightPx)
            val divider = initial.geometry(pageAspect).dividers.minByOrNull { (point(initial, it) - down.position).getDistance() }
                ?.takeIf { (point(initial, it) - down.position).getDistance() <= radius } ?: return@awaitEachGesture
            down.consume(); liveBegin()
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val current = liveLayout ?: break
                    val (dx, dy) = rotateVector(change.positionChange().x, change.positionChange().y, -current.frame.rotationDegrees)
                    val delta = if (divider.axis == LayoutAxis.HORIZONTAL) dx / (pageWidthPx * current.frame.bounds.width * divider.parent.width)
                        else dy / (pageHeightPx * current.frame.bounds.height * divider.parent.height)
                    liveAdjust(divider.splitId, divider.index, delta)
                    event.changes.forEach { it.consume() }
                }
            } finally { liveEnd() }
        }
    }
}
