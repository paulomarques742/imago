package eu.studio742.imago.feature.editor

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import kotlin.math.abs
import kotlin.math.roundToInt

/** How many finger pixels are worth a degree. Tuned so ±45° fit in about three sweeps. */
private const val PIXELS_PER_DEGREE = 9f

/** Every how many degrees there is a tall tick and a number. */
private const val LABEL_STEP = 15

/**
 * The value a drag of the ruler reaches.
 *
 * It lives outside the Composable because it is the only part that can be tested without a screen,
 * and it is where the two decisions noticed in use are: how far it moves per pixel, and what happens
 * when passing zero.
 *
 * The detent exists because a straight horizon is the destination of almost every drag and hitting
 * it to a tenth of a degree with the thumb is not possible. But it only holds up to [detentDegrees]:
 * a decided drag crosses it and continues to the other side, or it would go from help to obstacle for
 * whoever really wants −0.2°.
 */
internal fun straightenFromDrag(
    current: Float,
    deltaPx: Float,
    pixelsPerDegree: Float = PIXELS_PER_DEGREE,
    range: ClosedFloatingPointRange<Float> = -45f..45f,
    detentDegrees: Float = 0.4f,
): Float {
    val raw = current + deltaPx / pixelsPerDegree.coerceAtLeast(0.0001f)
    val snapped = if (abs(raw) <= detentDegrees) 0f else raw
    return snapped.coerceIn(range.start, range.endInclusive)
}

/**
 * The degree ruler, over the photo.
 *
 * Degrees are not chosen on a generic slider. What gives the sense of "straight" is seeing the ticks
 * pass under a still cursor while the image rotates — which is why the value comes from the distance
 * the finger travelled, and not from its absolute position within a bar. Dragging left rotates left,
 * which is the direction the ruler moves.
 */
@Composable
internal fun StraightenRuler(
    degrees: Float,
    onDegrees: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(degrees)
    val density = LocalDensity.current
    val pixelsPerDegree = with(density) { PIXELS_PER_DEGREE.dp.toPx() }
    val tickWidth = with(density) { 1.5.dp.toPx() }
    val cursorWidth = with(density) { 2.dp.toPx() }

    val straighten = stringResource(Res.string.editor_straighten)
    val stateText = stringResource(Res.string.editor_straighten_state, "%+.1f".format(current))
    val halfRight = stringResource(Res.string.editor_rotate_half_right)
    val halfLeft = stringResource(Res.string.editor_rotate_half_left)
    val resetFine = stringResource(Res.string.editor_reset_fine_rotation)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.semantics {
            contentDescription = straighten
            stateDescription = stateText
            customActions = listOf(
                CustomAccessibilityAction(halfRight) {
                    onDegrees((current + 0.5f).coerceIn(-45f, 45f)); onDragEnd(); true
                },
                CustomAccessibilityAction(halfLeft) {
                    onDegrees((current - 0.5f).coerceIn(-45f, 45f)); onDragEnd(); true
                },
                CustomAccessibilityAction(resetFine) { onReset(); true },
            )
        },
    ) {
        Text(
            text = if (current == 0f) "0°" else "%+.1f°".format(current),
            style = MaterialTheme.typography.labelSmall,
            color = if (current == 0f) ImagoColors.TextTertiary else ImagoColors.Gold,
            textAlign = TextAlign.Center,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(top = ImagoSpacing.Xs)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    ) { change, drag ->
                        // The sign: pulling the ruler to the left brings the positive degrees under the
                        // cursor, as happens with a real ruler.
                        onDegrees(straightenFromDrag(current, -drag.x, pixelsPerDegree))
                        change.consume()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onReset() })
                },
        ) {
            val centre = size.width / 2f
            val visibleDegrees = (size.width / pixelsPerDegree / 2f).roundToInt() + 1
            val first = (current.roundToInt() - visibleDegrees)
            for (degree in first..(current.roundToInt() + visibleDegrees)) {
                if (degree !in -45..45) continue
                val x = centre + (degree - current) * pixelsPerDegree
                if (x < 0f || x > size.width) continue
                val labelled = degree % LABEL_STEP == 0
                val height = if (labelled) size.height * 0.55f else size.height * 0.3f
                // The ticks fade towards the ends: the ruler has no visible end, and a straight cut
                // looked like a drawing mistake.
                val fade = 1f - abs(x - centre) / centre
                drawLine(
                    color = Color.White.copy(alpha = (if (labelled) 0.7f else 0.4f) * fade.coerceIn(0f, 1f)),
                    start = Offset(x, (size.height - height) / 2f),
                    end = Offset(x, (size.height + height) / 2f),
                    strokeWidth = tickWidth,
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                color = ImagoColors.Gold,
                start = Offset(centre, size.height * 0.1f),
                end = Offset(centre, size.height * 0.9f),
                strokeWidth = cursorWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
