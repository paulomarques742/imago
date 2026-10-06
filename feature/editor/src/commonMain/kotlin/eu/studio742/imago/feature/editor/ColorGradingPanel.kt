package eu.studio742.imago.feature.editor

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoParameterSlider
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.ColorWheel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What colour grading does to the state, gathered in a single type.
 *
 * For the same reason as [MaskActions]: there are six lambdas, and passing them one by one through
 * three layers of composition added six parameters to each.
 */
data class ColorGradeActions(
    val onSelectWheel: (ColorGradeWheel) -> Unit,
    /** Hue and saturation at once, which is what the handle dragged in the circle gives. */
    val onHandle: (ColorGradeWheel, Float, Float) -> Unit,
    val onComponent: (ColorGradeWheel, ColorGradeComponent, Float) -> Unit,
    val onResetWheel: (ColorGradeWheel) -> Unit,
    val onResetAll: () -> Unit,
    val onGestureFinished: () -> Unit,
)

/**
 * The edit key of a wheel's handle.
 *
 * It lives in a function because it is read both ways: the gesture declares it, and the panel asks
 * which of the four wheels is under the finger to know what to show in place of the header.
 */
private fun gradeHandleKey(wheel: ColorGradeWheel) = "grade-$wheel"

/**
 * Step 12 as the editor sees it: four wheels in a sliding row, with the row of icons above saying
 * which one is in front.
 *
 * The wheels are in a carousel and not in a selector that swaps the content in place because it is
 * the only layout where it is clear there is more than one: the next wheel peeks at the edge, and the
 * same gesture that brings it to the middle is the one used to peek at it.
 *
 * The header, the icons and the chosen wheel stay **outside** the scrolling part, and the wheel is
 * sized by the height left for it. A wheel cut off by the panel's edge is worse than a small wheel:
 * the angle is the control, and half a circle does not say where it is.
 */
@Composable
internal fun ColorGradingPanel(
    state: EditorUiState,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    onAdjustment: (Adjustment, Float) -> Unit,
    onResetAdjustment: (Adjustment) -> Unit,
    actions: ColorGradeActions,
    onClose: () -> Unit,
) {
    val chromeAlpha by animateFloatAsState(
        targetValue = if (editingKey == null) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "gradeChrome",
    )
    val wheels = ColorGradeWheel.entries
    val selectedIndex = wheels.indexOf(state.selectedColorGradeWheel).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedIndex) { wheels.size }
    val dragged = wheels.firstOrNull { editingKey == gradeHandleKey(it) }

    // The row and the icons say the same thing, and so have to agree both ways: the icon brings the
    // wheel to the middle, and swiping to a wheel lights its icon. `settledPage` and not `currentPage`
    // on purpose — halfway through a drag the icon has not moved yet.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            wheels.getOrNull(page)?.let(actions.onSelectWheel)
        }
    }
    LaunchedEffect(state.selectedColorGradeWheel) {
        val target = wheels.indexOf(state.selectedColorGradeWheel)
        if (target >= 0 && target != pagerState.currentPage) pagerState.animateScrollToPage(target)
    }

    Column(Modifier.fillMaxSize()) {
        // The header and the readout share the same strip: one leaves as the other comes in, and the
        // height does not change when the finger touches the wheel — the whole panel would jump.
        Box(Modifier.fillMaxWidth()) {
            SubToolHeader(
                title = stringResource(Res.string.editor_color_grading),
                onClose = onClose,
                onReset = actions.onResetAll,
                modifier = Modifier.alpha(chromeAlpha).padding(horizontal = ImagoSpacing.Lg),
            )
            dragged?.let { wheel ->
                ColorGradeReadout(
                    wheel = wheel,
                    values = state.recipe?.colorGradeWheel(wheel) ?: ColorWheel(),
                    modifier = Modifier.align(Alignment.Center).alpha(1f - chromeAlpha),
                )
            }
        }
        ColorGradeWheelSelector(
            selected = state.selectedColorGradeWheel,
            onSelect = actions.onSelectWheel,
            modifier = Modifier.fillMaxWidth().alpha(chromeAlpha),
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The wheel is sized by what fits on both axes: the width decides how much of the next
            // wheel peeks, and the height left by the header and the icons is what keeps it from being
            // cut off at the bottom.
            val pageWidth = (maxWidth * 0.66f).coerceIn(140.dp, 240.dp)
            val gutter = ((maxWidth - pageWidth) / 2).coerceAtLeast(ImagoSpacing.Sm)
            val wheelSide = minOf(pageWidth, maxHeight - WHEEL_CAPTION_HEIGHT).coerceAtLeast(96.dp)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
            ) {
                HorizontalPager(
                    state = pagerState,
                    pageSize = PageSize.Fixed(pageWidth),
                    contentPadding = PaddingValues(horizontal = gutter),
                    pageSpacing = ImagoSpacing.Md,
                    // The whole page is not worth being taller than the wheel and its caption: the rest
                    // of this column belongs to the sliders, and that is what the scrolling is for.
                    modifier = Modifier.fillMaxWidth().height(wheelSide + WHEEL_CAPTION_HEIGHT),
                ) { page ->
                    val wheel = wheels[page]
                    ColorWheelPage(
                        wheel = wheel,
                        values = state.recipe?.colorGradeWheel(wheel) ?: ColorWheel(),
                        side = wheelSide,
                        editingKey = editingKey,
                        // Only the wheel under the finger stays on scene; the neighbours leave with the
                        // rest of the panel, by the same rule as the sliders.
                        dimmed = editingKey != null && dragged != wheel,
                        onEditing = onEditing,
                        actions = actions,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ImagoSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
                ) {
                    val wheel = state.selectedColorGradeWheel
                    val values = state.recipe?.colorGradeWheel(wheel) ?: ColorWheel()
                    ColorGradeComponent.entries.forEach { component ->
                        ImagoParameterSlider(
                            label = component.historyLabel(),
                            value = values.component(component),
                            neutral = 0f,
                            range = component.range(),
                            pointerKey = "$wheel-$component",
                            editingKey = editingKey,
                            onEditing = onEditing,
                            valueText = { component.format(it) },
                            onValueChange = { actions.onComponent(wheel, component, it) },
                            onValueChangeFinished = actions.onGestureFinished,
                            onReset = { actions.onComponent(wheel, component, 0f) },
                        )
                    }
                    // Blending and balance belong to the three tonal zones, not to the wheel in front:
                    // they stay down here, with the header saying whose they are, and keep the same
                    // value while the global one is chosen.
                    Text(
                        text = stringResource(Res.string.editor_tonal_zones),
                        style = MaterialTheme.typography.labelLarge,
                        color = ImagoColors.Gold,
                        modifier = Modifier
                            .alpha(chromeAlpha)
                            .padding(top = ImagoSpacing.Sm, bottom = ImagoSpacing.Xs),
                    )
                    listOf(
                        Adjustment.GRADE_BLENDING to stringResource(Res.string.editor_grading_blending),
                        Adjustment.GRADE_BALANCE to stringResource(Res.string.editor_grading_balance),
                    ).forEach { (adjustment, label) ->
                        ImagoParameterSlider(
                            label = label,
                            value = state.recipe?.adjustmentValue(adjustment) ?: adjustment.neutral,
                            neutral = adjustment.neutral,
                            range = adjustment.range,
                            pointerKey = adjustment,
                            editingKey = editingKey,
                            onEditing = onEditing,
                            valueText = { formatAdjustmentValue(adjustment, it) },
                            onValueChange = { onAdjustment(adjustment, it) },
                            onValueChangeFinished = actions.onGestureFinished,
                            onReset = { onResetAdjustment(adjustment) },
                        )
                    }
                    Spacer(Modifier.height(ImagoSpacing.Lg))
                }
            }
        }
    }
}

/** Height reserved for the wheel's name and the two values under it. */
private val WHEEL_CAPTION_HEIGHT = 44.dp

/**
 * What is read while the finger is on the wheel: the tonal zone and the two values, in a capsule of
 * its own.
 *
 * It takes the header's place because the panel, at that moment, is transparent: without a
 * background of its own, this text would compete with the photo being judged.
 */
@Composable
private fun ColorGradeReadout(wheel: ColorGradeWheel, values: ColorWheel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Pill))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = wheel.historyLabel(),
            style = MaterialTheme.typography.labelLarge,
            color = ImagoColors.TextPrimary,
        )
        Text(
            text = stringResource(Res.string.editor_hue_value, ColorGradeComponent.HUE.format(values.hue)),
            style = MaterialTheme.typography.labelLarge,
            color = ImagoColors.Gold,
        )
        Text(
            text = stringResource(Res.string.editor_sat_value, ColorGradeComponent.SATURATION.format(values.saturation)),
            style = MaterialTheme.typography.labelLarge,
            color = ImagoColors.Gold,
        )
    }
}

/**
 * The row of icons: the three tonal zones, a bar, and the global one.
 *
 * The bar is not decoration. The global one is not a fourth zone — it obeys neither blending nor
 * balance — and without the separation the row suggested a four-step scale of lightness.
 */
@Composable
private fun ColorGradeWheelSelector(
    selected: ColorGradeWheel,
    onSelect: (ColorGradeWheel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = ImagoSpacing.Xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorGradeWheel.entries.forEach { wheel ->
            if (wheel == ColorGradeWheel.GLOBAL) {
                Box(
                    Modifier
                        .padding(horizontal = ImagoSpacing.Sm)
                        .width(1.dp)
                        .height(ImagoSizes.IconDefault)
                        .clip(CircleShape)
                        .border(1.dp, ImagoColors.BorderVisible, CircleShape),
                )
            }
            val isSelected = wheel == selected
            val wheelLabel = wheel.historyLabel()
            Box(
                modifier = Modifier
                    .size(ImagoSizes.TouchTarget)
                    .clip(CircleShape)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(wheel) })
                    // Without this TalkBack only sees four circles: lightness is the only clue.
                    .semantics { contentDescription = wheelLabel },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(if (isSelected) 26.dp else 22.dp)) {
                    drawWheelBadge(wheel)
                    drawCircle(
                        color = if (isSelected) ImagoColors.Ivory else ImagoColors.BorderVisible,
                        radius = size.minDimension / 2 - (if (isSelected) 1f else 0.5f).dp.toPx(),
                        style = Stroke(width = (if (isSelected) 2f else 1f).dp.toPx()),
                    )
                }
            }
        }
    }
}

/** Each icon's disc: black, grey, white, and half of each for the global one. */
private fun DrawScope.drawWheelBadge(wheel: ColorGradeWheel) {
    val radius = size.minDimension / 2
    when (wheel) {
        ColorGradeWheel.SHADOWS -> drawCircle(color = Color(0xFF0B0D0F), radius = radius)
        ColorGradeWheel.MIDTONES -> drawCircle(color = Color(0xFF75797E), radius = radius)
        ColorGradeWheel.HIGHLIGHTS -> drawCircle(color = ImagoColors.Ivory, radius = radius)
        ColorGradeWheel.GLOBAL -> {
            drawArc(
                color = Color(0xFF0B0D0F),
                startAngle = 90f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset.Zero,
                size = Size(size.minDimension, size.minDimension),
            )
            drawArc(
                color = ImagoColors.Ivory,
                startAngle = 270f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset.Zero,
                size = Size(size.minDimension, size.minDimension),
            )
        }
    }
}

/** A page of the carousel: the circle, the wheel's name and the two values the handle defines. */
@Composable
private fun ColorWheelPage(
    wheel: ColorGradeWheel,
    values: ColorWheel,
    side: Dp,
    editingKey: Any?,
    dimmed: Boolean,
    onEditing: (Any?) -> Unit,
    actions: ColorGradeActions,
) {
    val alpha by animateFloatAsState(
        targetValue = if (dimmed) 0f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "wheelPage",
    )
    // The caption belongs to the panel, not to the wheel: while the finger is there, what is read is
    // the capsule above, over the photo.
    val captionAlpha by animateFloatAsState(
        targetValue = if (editingKey == null) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "wheelCaption",
    )
    Column(
        modifier = Modifier.alpha(alpha),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ColorWheelControl(
            wheel = wheel,
            values = values,
            side = side,
            editing = editingKey == gradeHandleKey(wheel),
            onEditing = onEditing,
            actions = actions,
        )
        Column(
            modifier = Modifier.alpha(captionAlpha).height(WHEEL_CAPTION_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = wheel.historyLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    Res.string.editor_hue_sat_value,
                    ColorGradeComponent.HUE.format(values.hue),
                    ColorGradeComponent.SATURATION.format(values.saturation),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (values.saturation == 0f) ImagoColors.TextTertiary else ImagoColors.Gold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The hue and saturation circle, with the hue handle outside it.
 *
 * The outer handle is not a shortcut: with saturation at zero the inner handle is at the centre,
 * where it has no angle at all, and without a second place to grab it was impossible to choose a
 * colour before choosing its amount. It is what Lightroom solves on the computer with a modifier
 * key, and on a touch screen it has to be a target of its own.
 *
 * **While dragging the disc turns into a ring.** The panel, at that moment, is transparent to let
 * the photo show, and an opaque disc of colour in the middle of it would cancel the effect: what is
 * being judged is the photo, not the wheel. The rim with the colours stays, with the handle and the
 * centre dot marking where neutral is.
 *
 * Double tap resets the wheel, as in any slider in the editor.
 */
@Composable
private fun ColorWheelControl(
    wheel: ColorGradeWheel,
    values: ColorWheel,
    side: Dp,
    editing: Boolean,
    onEditing: (Any?) -> Unit,
    actions: ColorGradeActions,
) {
    val density = LocalDensity.current
    val handleKey = gradeHandleKey(wheel)
    // The width of the outer ring, where a touch counts as hue and not as saturation.
    val ringWidth = 18.dp
    var hueOnly by remember(wheel) { mutableStateOf(false) }
    // The saturation read by the gesture comes from here and not from the `pointerInput` key: a key
    // changing with every new value recreated the detector mid-drag, and the finger kept dragging a
    // gesture that had already been cancelled.
    val saturation by rememberUpdatedState(values.saturation)
    val discAlpha by animateFloatAsState(
        targetValue = if (editing) 0f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "wheelDisc",
    )

    fun apply(position: Offset, canvas: Float) {
        val centre = canvas / 2f
        val radius = centre - with(density) { ringWidth.toPx() }
        val dx = position.x - centre
        val dy = centre - position.y
        val hue = positiveHue(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
        val picked = if (hueOnly) saturation else (hypot(dx, dy) / radius).coerceIn(0f, 1f) * 100f
        actions.onHandle(wheel, hue, picked)
    }

    val wheelDescription = stringResource(
        Res.string.editor_wheel_description,
        wheel.historyLabel(),
        ColorGradeComponent.HUE.format(values.hue),
        ColorGradeComponent.SATURATION.format(values.saturation),
    )
    Canvas(
        modifier = Modifier
            .size(side)
            .semantics { contentDescription = wheelDescription }
            .pointerInput(wheel) {
                detectTapGestures(
                    onDoubleTap = { actions.onResetWheel(wheel) },
                    onTap = { position ->
                        hueOnly = isOnRing(position, size.width.toFloat(), density, ringWidth)
                        apply(position, size.width.toFloat())
                        actions.onGestureFinished()
                    },
                )
            }
            .pointerInput(wheel) {
                detectDragGestures(
                    onDragStart = { position ->
                        hueOnly = isOnRing(position, size.width.toFloat(), density, ringWidth)
                        onEditing(handleKey)
                        apply(position, size.width.toFloat())
                    },
                    onDragEnd = {
                        onEditing(null)
                        actions.onGestureFinished()
                    },
                    onDragCancel = {
                        onEditing(null)
                        actions.onGestureFinished()
                    },
                ) { change, _ ->
                    apply(change.position, size.width.toFloat())
                }
            },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f - ringWidth.toPx()

        // Hue grows counter-clockwise, with red at three o'clock — the convention of any colour wheel,
        // and that of the panel this reproduces. Compose's sweep gradient runs the other way, so the
        // colours go in reversed.
        val sweep = Brush.sweepGradient(colors = HUE_SWEEP, center = centre)
        if (discAlpha > 0.01f) {
            drawCircle(brush = sweep, radius = radius, center = centre, alpha = discAlpha)
            // The white in the middle is saturation: the handle at the centre tints nothing.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
                alpha = discAlpha,
            )
        }
        // The rim always stays, and it is all that is left while dragging.
        drawCircle(
            brush = sweep,
            radius = radius,
            center = centre,
            style = Stroke(width = (1f + 2f * (1f - discAlpha)).dp.toPx()),
        )

        val radians = Math.toRadians(values.hue.toDouble())
        val amount = (values.saturation / 100f).coerceIn(0f, 1f)
        val handle = Offset(
            x = centre.x + (cos(radians) * radius * amount).toFloat(),
            y = centre.y - (sin(radians) * radius * amount).toFloat(),
        )
        // Without the disc underneath, the centre stops showing — and it is this wheel's zero.
        if (discAlpha < 0.99f) {
            drawCircle(
                color = ImagoColors.TextTertiary,
                radius = 3.dp.toPx(),
                center = centre,
                alpha = 1f - discAlpha,
            )
        }
        drawCircle(
            color = Color.hsv(values.hue, amount, 1f),
            radius = 9.dp.toPx(),
            center = handle,
        )
        drawCircle(
            color = ImagoColors.BrandWhite,
            radius = 11.dp.toPx(),
            center = handle,
            style = Stroke(width = 2.dp.toPx()),
        )

        // The hue handle, against the outside of the disc.
        val ringRadius = radius + ringWidth.toPx() / 2f
        val hueHandle = Offset(
            x = centre.x + (cos(radians) * ringRadius).toFloat(),
            y = centre.y - (sin(radians) * ringRadius).toFloat(),
        )
        drawCircle(color = Color.hsv(values.hue, 1f, 1f), radius = 7.dp.toPx(), center = hueHandle)
        drawCircle(
            color = if (editing) ImagoColors.BrandWhite else ImagoColors.BorderStrong,
            radius = 7.dp.toPx(),
            center = hueHandle,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/** Outside the disc it is hue; inside it is hue and saturation. */
private fun isOnRing(position: Offset, canvas: Float, density: Density, ringWidth: Dp): Boolean {
    val centre = canvas / 2f
    val radius = centre - with(density) { ringWidth.toPx() }
    return hypot(position.x - centre, position.y - centre) > radius
}

/**
 * The disc's colours, clockwise from three o'clock — which is the direction of `sweepGradient` — and
 * therefore with the hue decreasing.
 */
private val HUE_SWEEP = listOf(
    Color(0xFFFF0000),
    Color(0xFFFF00FF),
    Color(0xFF0000FF),
    Color(0xFF00FFFF),
    Color(0xFF00FF00),
    Color(0xFFFFFF00),
    Color(0xFFFF0000),
)
