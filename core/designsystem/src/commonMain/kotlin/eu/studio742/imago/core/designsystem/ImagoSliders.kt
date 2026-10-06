package eu.studio742.imago.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** The thumb's radius. It lives here because the track has to step back by exactly this much at the ends. */
private val ThumbRadius = 9.dp
private val TrackHeight = 2.dp

/**
 * How the focused mode's ruler is coloured.
 *
 * A parameter that goes from cold to warm deserves to say so on the scale itself; the others have no
 * axis with a chromatic meaning and stay neutral, or the colour would lie about what the value does.
 */
enum class ImagoScaleTint { NEUTRAL, TEMPERATURE }

/**
 * The editor's slider.
 *
 * It differs from Material in two things the design system asks for: the fill starts at the neutral
 * value and not at the left — so you can see at a glance which way and how far the adjustment was
 * pushed — and the thumb is a hollow ring, which lets the photo show through instead of covering a
 * piece of it.
 *
 * The drag mechanics, the touch target and accessibility are still Material's; only the drawing is
 * ours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    neutral: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val valueFraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val neutralFraction = ((neutral - range.start) / span).coerceIn(0f, 1f)
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = range,
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier.size(ThumbRadius * 2),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(ThumbRadius * 2)) {
                    val stroke = 2.dp.toPx()
                    // The inside darkens just enough for the white ring not to get lost over a light
                    // area of the photo; it still lets what is underneath show through.
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.28f),
                        radius = size.minDimension / 2 - stroke / 2,
                    )
                    drawCircle(
                        color = if (enabled) ImagoColors.BrandWhite else ImagoColors.TextDisabled,
                        radius = size.minDimension / 2 - stroke / 2,
                        style = Stroke(width = stroke),
                    )
                }
            }
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(ThumbRadius * 2)) {
                // The thumb moves between these two limits, not between 0 and the full width;
                // without this inset the fill would run away from it at the ends.
                val start = ThumbRadius.toPx()
                val end = size.width - ThumbRadius.toPx()
                val usable = (end - start).coerceAtLeast(0f)
                val y = size.height / 2
                val thickness = TrackHeight.toPx()
                drawLine(
                    color = ImagoColors.BorderVisible,
                    start = Offset(start, y),
                    end = Offset(end, y),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round,
                )
                val from = start + usable * neutralFraction
                val to = start + usable * valueFraction
                if (abs(to - from) > 0.5f) {
                    drawLine(
                        color = if (enabled) ImagoColors.BrandWhite else ImagoColors.TextDisabled,
                        start = Offset(minOf(from, to), y),
                        end = Offset(maxOf(from, to), y),
                        strokeWidth = thickness,
                        cap = StrokeCap.Round,
                    )
                }
            }
        },
        colors = SliderDefaults.colors(
            activeTrackColor = Color.Transparent,
            inactiveTrackColor = Color.Transparent,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}

/**
 * The ruler of the focused adjustment mode.
 *
 * While a value is being dragged, the panel leaves the scene and only this stays over the photo:
 * the parameter's name, the value, and a graduated scale that gives a sense of where one is within
 * the range — something a thin slider cannot convey on its own.
 *
 * It takes no gestures. The slider that still exists underneath, invisible, takes them; this is the
 * reading of that state.
 */
@Composable
fun FocusedAdjustmentScale(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    tint: ImagoScaleTint = ImagoScaleTint.NEUTRAL,
    formatBound: (Float) -> String = { "%+.2f".format(it) },
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = ImagoColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.headlineSmall,
            color = ImagoColors.BrandWhite,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = ImagoSpacing.Xs),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ImagoSpacing.Lg, start = ImagoSpacing.Lg, end = ImagoSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatBound(range.start),
                style = MaterialTheme.typography.labelLarge,
                color = ImagoColors.TextTertiary,
            )
            Canvas(
                Modifier
                    .weight(1f)
                    .height(ImagoSizes.TouchTarget)
                    .padding(horizontal = ImagoSpacing.Md),
            ) {
                val ticks = 48
                val y = size.height / 2
                val tickHeight = 10.dp.toPx()
                val stroke = 2.dp.toPx()
                repeat(ticks + 1) { index ->
                    val position = index / ticks.toFloat()
                    val x = size.width * position
                    drawLine(
                        color = tickColor(tint, position),
                        start = Offset(x, y - tickHeight / 2),
                        end = Offset(x, y + tickHeight / 2),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                // The full disc covers the marks where it sits: that is what makes it unmistakable as
                // the current position, and not one more graduation.
                val thumbX = size.width * fraction
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    radius = 13.dp.toPx(),
                    center = Offset(thumbX, y),
                )
                drawCircle(
                    color = ImagoColors.BrandWhite,
                    radius = 10.dp.toPx(),
                    center = Offset(thumbX, y),
                )
            }
            Text(
                text = formatBound(range.endInclusive),
                style = MaterialTheme.typography.labelLarge,
                color = ImagoColors.TextTertiary,
            )
        }
    }
}

/**
 * The colour of a mark, by its position on the ruler.
 *
 * The cold-warm gradient is interpolated by hand instead of with a `Brush` because each mark is an
 * independent line: a gradient over the whole `Canvas` would also paint the gaps between them.
 */
private fun tickColor(tint: ImagoScaleTint, position: Float): Color = when (tint) {
    ImagoScaleTint.NEUTRAL -> ImagoColors.TextSecondary
    ImagoScaleTint.TEMPERATURE -> {
        val cold = Color(0xFF3E8BD8)
        val warm = Color(0xFFE8A33D)
        val neutral = ImagoColors.Ivory
        if (position < 0.5f) {
            lerpColor(cold, neutral, position * 2f)
        } else {
            lerpColor(neutral, warm, (position - 0.5f) * 2f)
        }
    }
}

private fun lerpColor(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    fun channel(a: Float, b: Float) = ((a + (b - a) * t) * 255f).roundToInt() / 255f
    return Color(
        red = channel(from.red, to.red),
        green = channel(from.green, to.green),
        blue = channel(from.blue, to.blue),
    )
}

/**
 * An adjustment row: the name, the value, and the slider below.
 *
 * This component gives the editor the behaviour the design system asks for — while a value is being
 * dragged, the others leave the scene ([editingKey] different from [pointerKey] hides them) and the
 * active row gets a background of its own to be readable over any image. It lives here, and not
 * inside the photo editor, because the composer does exactly the same with its adjustments: it is
 * the same gesture, and it has to be the same drawing.
 *
 * A double tap resets the neutral value, and there is a vibration when passing the neutral and the
 * extremes.
 */
@Composable
fun ImagoParameterSlider(
    label: String,
    value: Float,
    neutral: Float,
    range: ClosedFloatingPointRange<Float>,
    pointerKey: Any,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    valueText: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onReset: () -> Unit,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    var lastSnap by remember(pointerKey) { mutableStateOf<Int?>(null) }
    val snapTolerance = (range.endInclusive - range.start) * 0.008f
    val isActive = editingKey == pointerKey
    val contentAlpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1f
            editingKey != null -> 0f
            enabled -> 1f
            else -> 0.38f
        },
        animationSpec = tween(durationMillis = 160),
        label = "sliderAlpha",
    )
    // Without the panel behind it, the value text would compete with the photo. While dragging, the
    // active row gets a background of its own to stay readable over any image.
    val scrim by animateFloatAsState(
        targetValue = if (isActive) 0.5f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "sliderScrim",
    )
    Column(
        modifier = Modifier
            .alpha(contentAlpha)
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(Color.Black.copy(alpha = scrim))
            .padding(horizontal = if (isActive) ImagoSpacing.Md else 0.dp)
            .pointerInput(pointerKey, enabled) {
                if (enabled) detectTapGestures(onDoubleTap = { onReset() })
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText(value),
                style = MaterialTheme.typography.labelLarge,
                color = if (abs(value - neutral) < 0.001f) {
                    ImagoColors.TextTertiary
                } else {
                    ImagoColors.Gold
                },
            )
        }
        ImagoSlider(
            value = value,
            enabled = enabled,
            neutral = neutral,
            range = range,
            onValueChange = { newValue ->
                val snap = when {
                    abs(newValue - neutral) <= snapTolerance -> 0
                    abs(newValue - range.start) <= snapTolerance -> -1
                    abs(newValue - range.endInclusive) <= snapTolerance -> 1
                    else -> null
                }
                if (snap != null && snap != lastSnap) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                lastSnap = snap
                onEditing(pointerKey)
                onValueChange(newValue)
            },
            onValueChangeFinished = {
                onEditing(null)
                onValueChangeFinished()
            },
        )
    }
}
