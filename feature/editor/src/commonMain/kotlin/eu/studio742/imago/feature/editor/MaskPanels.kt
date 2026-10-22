package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.AutoFixOff
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoParameterSlider
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MAX_BRUSH_COMPONENTS
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MAX_MASK_COMPONENTS
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape

/**
 * The mask actions, in a single bundle.
 *
 * [EditorScreen] hoists each lambda separately, and there are already more than fifty; a dozen new ones
 * in the same signature would start hiding those already there. What mattered to preserve was the
 * composable not knowing the ViewModel, and a bundle of lambdas preserves that just like loose ones.
 */
data class MaskActions(
    val onAdd: (MaskShape, MaskPlacement) -> Unit,
    val onSelect: (String?) -> Unit,
    /** Opens a mask on one of its shapes, from the list. */
    val onSelectComponent: (String, Int) -> Unit,
    /** Moves the handles to another shape of the open mask, from its pin on the photo. */
    val onFocusComponent: (Int) -> Unit,
    val onAddComponent: (String, MaskShape, MaskOp, MaskPlacement) -> Unit,
    val onDeleteComponent: (String, Int) -> Unit,
    val onSetComponentInverted: (String, Int, Boolean) -> Unit,
    /** Duplicate, or with true, Duplicate and Invert. */
    val onDuplicate: (String, Boolean) -> Unit,
    val onShowList: () -> Unit,
    val onDelete: (String) -> Unit,
    val onSetEnabled: (String, Boolean) -> Unit,
    val onSetInverted: (String, Boolean) -> Unit,
    val onSetOverlayPinned: (Boolean) -> Unit,
    val onDismissNotice: () -> Unit,
    /** Opens the history entry of the whole gesture, as `onCropGestureStart` does for crop. */
    val onBeginGesture: (String, UiText) -> Unit,
    val onMove: (String, Float, Float) -> Unit,
    val onSetRadius: (String, Float?, Float?) -> Unit,
    val onSetWidth: (String, Float) -> Unit,
    val onRotate: (String, Float) -> Unit,
    /** A stroke starts at a point of the image, with the brush's radius already measured on the photo. */
    val onBrushStart: (String, Float, Float, Float) -> Unit,
    val onBrushTo: (String, Float, Float) -> Unit,
    /** A second finger arrived: the stroke goes away and the gesture becomes a pinch. */
    val onBrushCancel: () -> Unit,
    val onBrushErasing: (Boolean) -> Unit,
    val onBrushAutoMask: (Boolean) -> Unit,
    val onBrushSetting: (BrushSetting, Float) -> Unit,
    val onBrushSettingFinished: () -> Unit,
    /**
     * The part of the photo on screen when a shape is created: only the stage knows it, and it changes
     * with every pinch, so it is asked for at the tap and not kept.
     */
    val newShapePlacement: () -> MaskPlacement = { MaskPlacement.WholeImage },
)

/**
 * The list of masks, each with its shapes nested under it as in Lightroom, and the buttons that
 * create a new mask.
 */
@Composable
internal fun MaskListPanel(
    masks: List<LocalMask>,
    notice: UiText?,
    actions: MaskActions,
    modifier: Modifier = Modifier,
) {
    val canAddComponent = masks.sumOf { it.components.size } < MAX_MASK_COMPONENTS
    val canAddBrush = canAddComponent &&
        masks.sumOf { mask -> mask.components.count { it.shape == MaskShape.BRUSH } } < MAX_BRUSH_COMPONENTS
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        ) {
            MaskCreateButton(
                label = stringResource(Res.string.editor_mask_linear),
                icon = Icons.Outlined.Gradient,
                enabled = masks.size < MAX_LOCAL_MASKS && canAddComponent,
                onClick = { actions.onAdd(MaskShape.LINEAR, actions.newShapePlacement()) },
                modifier = Modifier.weight(1f),
            )
            MaskCreateButton(
                label = stringResource(Res.string.editor_mask_radial),
                icon = Icons.Outlined.Adjust,
                enabled = masks.size < MAX_LOCAL_MASKS && canAddComponent,
                onClick = { actions.onAdd(MaskShape.RADIAL, actions.newShapePlacement()) },
                modifier = Modifier.weight(1f),
            )
            MaskCreateButton(
                label = stringResource(Res.string.editor_mask_brush),
                icon = Icons.Outlined.Brush,
                enabled = masks.size < MAX_LOCAL_MASKS && canAddBrush,
                onClick = { actions.onAdd(MaskShape.BRUSH, actions.newShapePlacement()) },
                modifier = Modifier.weight(1f),
            )
        }

        notice?.let { text ->
            Text(
                text = text.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = actions.onDismissNotice)
                    .padding(vertical = ImagoSpacing.Sm),
            )
        }

        if (masks.isEmpty()) {
            Text(
                text = stringResource(Res.string.editor_masks_intro),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
                modifier = Modifier.padding(vertical = ImagoSpacing.Md),
            )
        } else {
            masks.forEach { mask ->
                MaskRow(
                    mask = mask,
                    canDuplicate = masks.size < MAX_LOCAL_MASKS &&
                        masks.sumOf { it.components.size } + mask.components.size <= MAX_MASK_COMPONENTS,
                    actions = actions,
                )
                mask.components.forEachIndexed { index, component ->
                    MaskComponentRow(mask = mask, index = index, component = component, actions = actions)
                }
                MaskCombineRow(mask = mask, enabled = canAddComponent, canAddBrush = canAddBrush, actions = actions)
            }
        }
    }
}

@Composable
private fun MaskCreateButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (enabled) ImagoColors.TextPrimary else ImagoColors.TextDisabled
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .background(ImagoColors.SurfaceElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(ImagoSizes.IconDefault))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun MaskRow(mask: LocalMask, canDuplicate: Boolean, actions: MaskActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ImagoSpacing.Sm)
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .clickable { actions.onSelect(mask.id) }
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val enableDescription = stringResource(Res.string.editor_mask_enable, mask.name)
        Switch(
            checked = mask.enabled,
            onCheckedChange = { actions.onSetEnabled(mask.id, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = ImagoColors.Gold),
            modifier = Modifier.semantics { contentDescription = enableDescription },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mask.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (mask.enabled) ImagoColors.TextPrimary else ImagoColors.TextDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = maskSummary(mask),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MaskIconButton(
            icon = Icons.Outlined.InvertColors,
            description = stringResource(Res.string.editor_mask_invert_named, mask.name),
            tint = if (mask.inverted) ImagoColors.Gold else ImagoColors.TextSecondary,
            onClick = { actions.onSetInverted(mask.id, !mask.inverted) },
        )
        MaskMoreMenu(mask = mask, canDuplicate = canDuplicate, actions = actions)
    }
}

/** What Lightroom keeps in a mask's menu: duplicate, duplicate and invert, delete. */
@Composable
private fun MaskMoreMenu(mask: LocalMask, canDuplicate: Boolean, actions: MaskActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        MaskIconButton(
            icon = Icons.Outlined.MoreVert,
            description = stringResource(Res.string.editor_mask_more_named, mask.name),
            tint = ImagoColors.TextSecondary,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.editor_mask_duplicate)) },
                enabled = canDuplicate,
                onClick = {
                    open = false
                    actions.onDuplicate(mask.id, false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.editor_mask_duplicate_invert)) },
                enabled = canDuplicate,
                onClick = {
                    open = false
                    actions.onDuplicate(mask.id, true)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.editor_mask_delete)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                onClick = {
                    open = false
                    actions.onDelete(mask.id)
                },
            )
        }
    }
}

/** One shape of the mask, indented under it: how it combines, what it is, invert and remove. */
@Composable
private fun MaskComponentRow(mask: LocalMask, index: Int, component: MaskComponent, actions: MaskActions) {
    val shape = component.shape.label()
    // The first shape is the seed the others combine with; its operation means nothing and is not shown.
    val op = if (index == 0) MaskOp.ADD else component.op
    val label = when (op) {
        MaskOp.ADD -> stringResource(Res.string.editor_mask_shape_added, shape)
        MaskOp.SUBTRACT -> stringResource(Res.string.editor_mask_shape_subtracted, shape)
        MaskOp.INTERSECT -> stringResource(Res.string.editor_mask_shape_intersected, shape)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ImagoSpacing.Xl)
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .clickable { actions.onSelectComponent(mask.id, index) }
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = op.icon(),
            contentDescription = null,
            tint = ImagoColors.TextSecondary,
            modifier = Modifier.size(ImagoSizes.IconDefault),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (mask.enabled) ImagoColors.TextSecondary else ImagoColors.TextDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MaskIconButton(
            icon = Icons.Outlined.InvertColors,
            description = stringResource(Res.string.editor_mask_shape_invert_named, shape),
            tint = if (component.inverted) ImagoColors.Gold else ImagoColors.TextTertiary,
            onClick = { actions.onSetComponentInverted(mask.id, index, !component.inverted) },
        )
        MaskIconButton(
            icon = Icons.Outlined.Close,
            description = stringResource(Res.string.editor_mask_shape_delete_named, shape),
            tint = ImagoColors.TextTertiary,
            onClick = { actions.onDeleteComponent(mask.id, index) },
        )
    }
}

/** Add, Subtract and Intersect, each asking for the shape to combine with. */
@Composable
private fun MaskCombineRow(mask: LocalMask, enabled: Boolean, canAddBrush: Boolean, actions: MaskActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ImagoSpacing.Xl),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
    ) {
        listOf(
            Triple(MaskOp.ADD, Res.string.editor_mask_add, Res.string.editor_mask_add_to_named),
            Triple(MaskOp.SUBTRACT, Res.string.editor_mask_subtract, Res.string.editor_mask_subtract_from_named),
            Triple(MaskOp.INTERSECT, Res.string.editor_mask_intersect, Res.string.editor_mask_intersect_with_named),
        ).forEach { (op, label, description) ->
            MaskCombineButton(
                op = op,
                label = stringResource(label),
                description = stringResource(description, mask.name),
                enabled = enabled,
                canAddBrush = canAddBrush,
                onPick = { shape -> actions.onAddComponent(mask.id, shape, op, actions.newShapePlacement()) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MaskCombineButton(
    op: MaskOp,
    label: String,
    description: String,
    enabled: Boolean,
    canAddBrush: Boolean,
    onPick: (MaskShape) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val tint = if (enabled) ImagoColors.TextSecondary else ImagoColors.TextDisabled
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ImagoRadii.Small))
                .clickable(enabled = enabled) { open = true }
                .semantics { contentDescription = description }
                .sizeIn(minHeight = ImagoSizes.TouchTarget)
                .padding(horizontal = ImagoSpacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(op.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MaskShape.entries.forEach { shape ->
                DropdownMenuItem(
                    text = { Text(shape.label()) },
                    leadingIcon = { Icon(shape.icon(), contentDescription = null) },
                    enabled = shape != MaskShape.BRUSH || canAddBrush,
                    onClick = {
                        open = false
                        onPick(shape)
                    },
                )
            }
        }
    }
}

/** The header of a mask's sliders: back, name, invert and show. */
@Composable
internal fun MaskAdjustmentsHeader(
    mask: LocalMask,
    overlayPinned: Boolean,
    actions: MaskActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaskIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            description = stringResource(Res.string.editor_mask_back_to_list),
            tint = ImagoColors.TextPrimary,
            onClick = actions.onShowList,
        )
        Text(
            text = mask.name,
            style = MaterialTheme.typography.titleSmall,
            color = ImagoColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MaskIconButton(
            icon = Icons.Outlined.InvertColors,
            description = stringResource(Res.string.editor_mask_invert),
            tint = if (mask.inverted) ImagoColors.Gold else ImagoColors.TextSecondary,
            onClick = { actions.onSetInverted(mask.id, !mask.inverted) },
        )
        MaskIconButton(
            icon = if (overlayPinned) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            description = if (overlayPinned) stringResource(Res.string.editor_mask_hide_overlay) else stringResource(Res.string.editor_mask_show_overlay),
            tint = if (overlayPinned) ImagoColors.Gold else ImagoColors.TextSecondary,
            onClick = { actions.onSetOverlayPinned(!overlayPinned) },
        )
    }
}

@Composable
private fun MaskIconButton(
    icon: ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .clickable(onClick = onClick)
            .size(ImagoSizes.TouchTarget)
            .border(0.dp, ImagoColors.BorderSubtle, RoundedCornerShape(ImagoRadii.Small)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(ImagoSizes.IconDefault))
    }
}

@Composable
private fun MaskShape.label(): String = when (this) {
    MaskShape.LINEAR -> stringResource(Res.string.editor_mask_linear)
    MaskShape.RADIAL -> stringResource(Res.string.editor_mask_radial)
    MaskShape.BRUSH -> stringResource(Res.string.editor_mask_brush)
}

private fun MaskShape.icon(): ImageVector = when (this) {
    MaskShape.LINEAR -> Icons.Outlined.Gradient
    MaskShape.RADIAL -> Icons.Outlined.Adjust
    MaskShape.BRUSH -> Icons.Outlined.Brush
}

/**
 * The brush in the hand, on top of the mask's sliders: Brush or Eraser, the Auto Mask, and the four
 * settings of whichever is in the hand — Lightroom keeps the two apart, so switching to the Eraser does
 * not shrink the Brush.
 *
 * The sliders are the editor's own, with the same focused drag; they write to the device, not to the
 * recipe, and so leave no history.
 */
@Composable
internal fun BrushToolPanel(
    tool: BrushTool,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    actions: MaskActions,
) {
    val headerAlpha = if (editingKey == null) 1f else 0f
    Row(
        modifier = Modifier.fillMaxWidth().alpha(headerAlpha).padding(top = ImagoSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(ImagoRadii.Small))
                .background(ImagoColors.SurfaceElevated),
        ) {
            BrushModeButton(
                label = stringResource(Res.string.editor_brush_paint),
                icon = Icons.Outlined.Brush,
                selected = !tool.erasing,
                onClick = { actions.onBrushErasing(false) },
            )
            BrushModeButton(
                label = stringResource(Res.string.editor_brush_erase),
                icon = Icons.Outlined.AutoFixOff,
                selected = tool.erasing,
                onClick = { actions.onBrushErasing(true) },
            )
        }
        Box(Modifier.weight(1f))
        val autoMaskLabel = stringResource(Res.string.editor_brush_auto_mask)
        Text(
            text = autoMaskLabel,
            style = MaterialTheme.typography.labelMedium,
            color = ImagoColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Switch(
            checked = tool.active.autoMask,
            onCheckedChange = actions.onBrushAutoMask,
            colors = SwitchDefaults.colors(checkedTrackColor = ImagoColors.Gold),
            modifier = Modifier.semantics { contentDescription = autoMaskLabel },
        )
    }
    listOf(
        BrushSetting.SIZE to stringResource(Res.string.editor_size),
        BrushSetting.FEATHER to stringResource(Res.string.editor_feather),
        BrushSetting.FLOW to stringResource(Res.string.editor_brush_flow),
        BrushSetting.DENSITY to stringResource(Res.string.editor_brush_density),
    ).forEach { (setting, label) ->
        ImagoParameterSlider(
            label = label,
            value = tool.active.value(setting),
            neutral = setting.neutral,
            range = setting.range,
            pointerKey = setting,
            editingKey = editingKey,
            onEditing = onEditing,
            valueText = { "%.0f".format(it) },
            onValueChange = { actions.onBrushSetting(setting, it) },
            onValueChangeFinished = actions.onBrushSettingFinished,
            onReset = {
                actions.onBrushSetting(setting, setting.neutral)
                actions.onBrushSettingFinished()
            },
        )
    }
}

@Composable
private fun BrushModeButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) ImagoColors.Gold else ImagoColors.TextSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .background(if (selected) ImagoColors.Gold.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

private fun MaskOp.icon(): ImageVector = when (this) {
    MaskOp.ADD -> Icons.Outlined.Add
    MaskOp.SUBTRACT -> Icons.Outlined.Remove
    MaskOp.INTERSECT -> Icons.Outlined.JoinInner
}

/** A row that says what the mask is and what it already does, without having to open it. */
@Composable
private fun maskSummary(mask: LocalMask): String {
    val shape = mask.components.firstOrNull()?.shape?.label() ?: stringResource(Res.string.editor_mask_empty)
    val touched = Adjustment.entries.filter {
        it.isLocal() && mask.adjustments.value(it) != it.neutral
    }
    return when {
        touched.isEmpty() -> stringResource(Res.string.editor_mask_summary_none, shape)
        touched.size == 1 -> stringResource(Res.string.editor_mask_summary_one, shape, touched.first().label())
        else -> stringResource(Res.string.editor_mask_summary_many, shape, touched.size)
    }
}
