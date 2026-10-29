package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoParameterSlider
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.Retouch
import eu.studio742.imago.core.model.RetouchMode
import eu.studio742.imago.core.render.FrameGeometry
import eu.studio742.imago.core.render.PhotoBounds
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot

/** What the Remove tool's panel and the photo under it can ask of the editor. */
data class RetouchActions(
    val onMode: (RetouchMode) -> Unit = {},
    val onSetting: (RetouchSetting, Float) -> Unit = { _, _ -> },
    val onSettingFinished: () -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    /** A stroke starts at a point of the image, with the brush's radius already measured on the photo. */
    val onStrokeStart: (Float, Float, Float) -> Unit = { _, _, _ -> },
    val onStrokeTo: (Float, Float) -> Unit = { _, _ -> },
    /** A second finger arrived: the correction being painted goes away and the gesture becomes a pinch. */
    val onStrokeCancel: () -> Unit = {},
    /** The selected correction's source, at an offset from its area in image units. */
    val onMoveSource: (String, Float, Float) -> Unit = { _, _, _ -> },
    /** The selected correction's area, moved by a step in image units. */
    val onMove: (String, Float, Float) -> Unit = { _, _, _ -> },
    val onGestureFinished: () -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onRefresh: (String) -> Unit = {},
    /** The eye: the pins and areas on the photo, or the photo whole. */
    val onShowRetouches: (Boolean) -> Unit = {},
    /** Visualize Spots on or off; its sensitivity is a [RetouchSetting]. */
    val onVisualizeSpots: (Boolean) -> Unit = {},
)

/**
 * The Remove tool's panel: the three modes, the brush, and what can be done to the selected correction.
 * The sliders are the editor's, so dragging one clears the screen as every other slider does.
 */
@Composable
internal fun RemoveToolPanel(
    state: EditorUiState,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    actions: RetouchActions,
    modifier: Modifier = Modifier,
) {
    val chromeAlpha = if (editingKey == null) 1f else 0f
    val selected = state.selectedRetouch
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = ImagoSpacing.Md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().alpha(chromeAlpha).padding(top = ImagoSpacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(ImagoRadii.Small))
                    .background(ImagoColors.SurfaceElevated),
            ) {
                RetouchMode.entries.forEach { mode ->
                    BrushModeButton(
                        label = stringResource(mode.labelRes),
                        icon = mode.icon(),
                        selected = state.retouchTool.mode == mode,
                        onClick = { actions.onMode(mode) },
                    )
                }
            }
            Box(Modifier.weight(1f))
            if (state.isSolvingRetouch) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = ImagoColors.Gold,
                )
            }
            if (!state.recipe?.retouches.isNullOrEmpty()) {
                val shown = state.showsRetouches
                MaskIconButton(
                    icon = if (shown) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    description = stringResource(if (shown) Res.string.editor_remove_hide_overlay else Res.string.editor_remove_show_overlay),
                    tint = if (shown) ImagoColors.Gold else ImagoColors.TextSecondary,
                    onClick = { actions.onShowRetouches(!shown) },
                )
            }
        }
        listOf(
            RetouchSetting.SIZE to stringResource(Res.string.editor_size),
            RetouchSetting.FEATHER to stringResource(Res.string.editor_feather),
            RetouchSetting.OPACITY to stringResource(Res.string.editor_remove_opacity),
        ).forEach { (setting, label) ->
            ImagoParameterSlider(
                label = label,
                value = state.retouchValue(setting),
                neutral = setting.neutral,
                range = setting.range,
                pointerKey = setting,
                editingKey = editingKey,
                onEditing = onEditing,
                valueText = { "%.0f".format(it) },
                onValueChange = { actions.onSetting(setting, it) },
                onValueChangeFinished = actions.onSettingFinished,
                onReset = {
                    actions.onSetting(setting, setting.neutral)
                    actions.onSettingFinished()
                },
            )
        }
        VisualizeSpotsRow(
            on = state.visualizesSpots,
            onChange = actions.onVisualizeSpots,
            modifier = Modifier.alpha(chromeAlpha),
        )
        if (state.visualizesSpots) {
            val setting = RetouchSetting.SPOTS
            ImagoParameterSlider(
                label = stringResource(Res.string.editor_remove_spots_sensitivity),
                value = state.retouchValue(setting),
                neutral = setting.neutral,
                range = setting.range,
                pointerKey = setting,
                editingKey = editingKey,
                onEditing = onEditing,
                valueText = { "%.0f".format(it) },
                onValueChange = { actions.onSetting(setting, it) },
                onValueChangeFinished = actions.onSettingFinished,
                onReset = {
                    actions.onSetting(setting, setting.neutral)
                    actions.onSettingFinished()
                },
            )
        }
        val message = when {
            state.isSolvingRetouch -> stringResource(Res.string.editor_remove_solving)
            state.retouchNotice != null -> state.retouchNotice.resolve()
            state.recipe?.retouches.isNullOrEmpty() -> stringResource(Res.string.editor_remove_hint)
            else -> null
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextSecondary,
                modifier = Modifier.alpha(chromeAlpha).padding(vertical = ImagoSpacing.Sm),
            )
        }
        if (selected != null && !state.isSolvingRetouch) {
            Row(
                modifier = Modifier.fillMaxWidth().alpha(chromeAlpha).padding(vertical = ImagoSpacing.Xs),
                horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
            ) {
                if (selected.mode == RetouchMode.REMOVE) {
                    BrushModeButton(
                        label = stringResource(Res.string.editor_remove_refresh),
                        icon = Icons.Outlined.Refresh,
                        selected = false,
                        onClick = { actions.onRefresh(selected.id) },
                    )
                }
                BrushModeButton(
                    label = stringResource(Res.string.editor_delete),
                    icon = Icons.Outlined.DeleteOutline,
                    selected = false,
                    onClick = { actions.onDelete(selected.id) },
                )
            }
        }
    }
}

@Composable
private fun VisualizeSpotsRow(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .sizeIn(minHeight = ImagoSizes.TouchTarget),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.editor_remove_visualize_spots),
            style = MaterialTheme.typography.bodyMedium,
            color = ImagoColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = ImagoColors.Gold),
        )
    }
}

private fun RetouchMode.icon(): ImageVector = when (this) {
    RetouchMode.REMOVE -> Icons.Outlined.AutoFixHigh
    RetouchMode.HEAL -> Icons.Outlined.Healing
    RetouchMode.CLONE -> Icons.Outlined.ContentCopy
}

/**
 * The corrections on the photo, as Lightroom draws them: a pin on each, and on the selected one its
 * area, and for a Heal or a Clone the area it takes from, joined to it by a line.
 */
@Composable
internal fun RetouchOverlay(
    retouches: List<Retouch>,
    selectedId: String?,
    /** The selected correction still widens with the next stroke: its pin is drawn small. */
    extendable: Boolean = false,
    bounds: PhotoBounds,
    geometry: FrameGeometry,
    modifier: Modifier = Modifier,
    brushCursor: BrushCursor? = null,
) {
    Canvas(modifier = modifier) {
        val projection = MaskProjection(bounds, geometry)
        retouches.forEach { retouch ->
            if (retouch.id == selectedId) return@forEach
            retouch.pin()?.let { (x, y) -> drawPin(projection.toScreen(x, y)) }
        }
        retouches.firstOrNull { it.id == selectedId }?.let { selected ->
            drawArea(selected.strokes, 0f, 0f, projection)
            val pin = selected.pin()?.let { (x, y) -> projection.toScreen(x, y) }
            val source = selected.sourcePin()?.takeIf { selected.sourceX != 0f || selected.sourceY != 0f }
                ?.let { (x, y) -> projection.toScreen(x, y) }
            if (source != null) {
                drawArea(selected.strokes, selected.sourceX, selected.sourceY, projection, dimmer = true)
                if (pin != null) {
                    drawLine(Color.Black.copy(alpha = 0.5f), pin, source, strokeWidth = OUTLINE_WIDTH.toPx() * 3)
                    drawLine(OUTLINE_COLOR, pin, source, strokeWidth = OUTLINE_WIDTH.toPx())
                }
                drawHandle(source, filled = false)
            }
            pin?.let { if (extendable) drawSmallPin(it) else drawHandle(it, filled = true) }
        }
        brushCursor?.let { drawBrushCursor(it) }
    }
}

/**
 * A correction's area, moved by an offset in image units: each stroke drawn as wide as its brush,
 * lightly, so the photo under it still shows. The source's copy is drawn dimmer, so the two do not mix.
 */
private fun DrawScope.drawArea(strokes: List<BrushStroke>, dx: Float, dy: Float, projection: MaskProjection, dimmer: Boolean = false) {
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        val first = projection.toScreen(stroke.points[0] + dx, stroke.points[1] + dy)
        val edge = projection.toScreen(stroke.points[0] + dx, stroke.points[1] + dy + stroke.radius)
        val width = hypot(edge.x - first.x, edge.y - first.y) * 2f
        val path = Path().apply {
            moveTo(first.x, first.y)
            for (i in 2 until stroke.points.size step 2) {
                val point = projection.toScreen(stroke.points[i] + dx, stroke.points[i + 1] + dy)
                lineTo(point.x, point.y)
            }
            // A single dab is a point, and a path of one point draws nothing.
            if (stroke.points.size == 2) lineTo(first.x + 0.01f, first.y)
        }
        val alpha = if (dimmer) 0.16f else 0.28f
        drawPath(path, OUTLINE_COLOR.copy(alpha = alpha), style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The selected pin while a stroke still widens its correction: there, and not in the way of the brush. */
private fun DrawScope.drawSmallPin(position: Offset) {
    val radius = SMALL_PIN_RADIUS.toPx()
    drawCircle(Color.Black.copy(alpha = 0.5f), radius = radius + OUTLINE_WIDTH.toPx() * 1.5f, center = position)
    drawCircle(OUTLINE_COLOR, radius = radius, center = position)
}

private val SMALL_PIN_RADIUS = 4.dp

/** Which part of the selected correction a finger landed on, if any. */
internal enum class RetouchGrip { AREA, SOURCE }

/**
 * What a finger at [position] grabs: the selected correction's source first, then its pin, then any
 * other correction's pin, which it selects. Null when it lands on none of them and so paints.
 */
internal fun hitRetouch(
    retouches: List<Retouch>,
    selectedId: String?,
    projection: MaskProjection,
    position: Offset,
    radius: Float,
    /** False while a stroke still widens the selected correction: its area is then painted on, not moved. */
    areaGrabbable: Boolean = true,
): Pair<String, RetouchGrip>? {
    fun near(point: Pair<Float, Float>?): Boolean =
        point != null && (projection.toScreen(point.first, point.second) - position).getDistance() <= radius
    retouches.firstOrNull { it.id == selectedId }?.let { selected ->
        if ((selected.sourceX != 0f || selected.sourceY != 0f) && near(selected.sourcePin())) return selected.id to RetouchGrip.SOURCE
        if (areaGrabbable && near(selected.pin())) return selected.id to RetouchGrip.AREA
    }
    return retouches.lastOrNull { it.id != selectedId && near(it.pin()) }?.let { it.id to RetouchGrip.AREA }
}
