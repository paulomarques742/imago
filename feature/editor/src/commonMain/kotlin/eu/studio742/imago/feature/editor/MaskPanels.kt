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
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MaskShape

/**
 * The mask actions, in a single bundle.
 *
 * [EditorScreen] hoists each lambda separately, and there are already more than fifty; eight new ones
 * in the same signature would start hiding those already there. What mattered to preserve was the
 * composable not knowing the ViewModel, and a bundle of lambdas preserves that just like loose ones.
 */
data class MaskActions(
    val onAdd: (MaskShape) -> Unit,
    val onSelect: (String?) -> Unit,
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
)

/** The list of masks and the two buttons that create a new one. */
@Composable
internal fun MaskListPanel(
    masks: List<LocalMask>,
    notice: UiText?,
    actions: MaskActions,
    modifier: Modifier = Modifier,
) {
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
                enabled = masks.size < MAX_LOCAL_MASKS,
                onClick = { actions.onAdd(MaskShape.LINEAR) },
                modifier = Modifier.weight(1f),
            )
            MaskCreateButton(
                label = stringResource(Res.string.editor_mask_radial),
                icon = Icons.Outlined.Adjust,
                enabled = masks.size < MAX_LOCAL_MASKS,
                onClick = { actions.onAdd(MaskShape.RADIAL) },
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
                MaskRow(mask = mask, actions = actions)
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
private fun MaskRow(mask: LocalMask, actions: MaskActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        MaskIconButton(
            icon = Icons.Outlined.DeleteOutline,
            description = stringResource(Res.string.editor_mask_delete_named, mask.name),
            tint = ImagoColors.TextSecondary,
            onClick = { actions.onDelete(mask.id) },
        )
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

/** A row that says what the mask is and what it already does, without having to open it. */
@Composable
private fun maskSummary(mask: LocalMask): String {
    val shape = when (mask.components.firstOrNull()?.shape) {
        MaskShape.LINEAR -> stringResource(Res.string.editor_mask_linear)
        MaskShape.RADIAL -> stringResource(Res.string.editor_mask_radial)
        null -> stringResource(Res.string.editor_mask_empty)
    }
    val touched = Adjustment.entries.filter {
        it.isLocal() && mask.adjustments.value(it) != it.neutral
    }
    return when {
        touched.isEmpty() -> stringResource(Res.string.editor_mask_summary_none, shape)
        touched.size == 1 -> stringResource(Res.string.editor_mask_summary_one, shape, touched.first().label())
        else -> stringResource(Res.string.editor_mask_summary_many, shape, touched.size)
    }
}
