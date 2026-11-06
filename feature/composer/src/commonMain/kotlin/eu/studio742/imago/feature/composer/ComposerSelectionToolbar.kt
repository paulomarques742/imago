package eu.studio742.imago.feature.composer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.CompositionArrangementReference
import eu.studio742.imago.core.designsystem.ImagoChipRow
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ComposerSelectionToolbar(
    state: ComposerEditorUiState, actions: ComposerEditorViewModel,
    propertiesExpanded: Boolean, onToggleProperties: (() -> Unit)?,
    onAddGridMedia: ((String) -> Unit)? = null,
) {
    val members = state.selectionElements()
    val units = state.selectionUnitCount()
    val group = members.firstOrNull()?.groupId?.takeIf { id -> members.all { it.groupId == id } }
    ComposerToolRow(Modifier.testTag("composer-selection-tools")) {
        if (state.editingGroupId == null && units > 1) {
            ToolButton(stringResource(Res.string.composer_group), Icons.Outlined.Layers, onClick = actions::groupSelection)
        }
        if (members.any { it.groupId != null }) {
            ToolButton(stringResource(Res.string.composer_ungroup), Icons.Outlined.LayersClear, onClick = actions::ungroupSelected)
        }
        if (group != null && state.editingGroupId == null) {
            ToolButton(stringResource(Res.string.composer_edit_group), Icons.Outlined.Edit) { actions.editGroup(group) }
        }
        if (state.editingGroupId == null && members.size > 1) {
            ToolButton(stringResource(if (state.selectedLayout() == null) Res.string.composer_create_grid else Res.string.composer_edit_grid),
                Icons.Outlined.GridView, onClick = actions::beginGrid)
        }
        if (group != null && state.selectedLayout() != null && onAddGridMedia != null &&
            state.project?.elements?.none { it.groupId == group && (it.locked || it.repeatOnPages.isNotEmpty()) } == true) {
            ToolButton(stringResource(Res.string.composer_grid_add_elements), Icons.Outlined.AddPhotoAlternate) { onAddGridMedia(group) }
        }
        ComposerArrangementMenu(state, actions)
        if (onToggleProperties != null) ToolButton(stringResource(Res.string.composer_adjust), Icons.Outlined.Tune,
            active = propertiesExpanded, onClick = onToggleProperties)
    }
}

@Composable
internal fun ComposerArrangementMenu(state: ComposerEditorUiState, actions: ComposerEditorViewModel) {
    val units = state.selectionUnitCount()
    val reference = if (units < 2) CompositionArrangementReference.PAGE else state.arrangementReference
    ToolMenuButton(stringResource(Res.string.composer_distribute), Icons.Outlined.AlignHorizontalCenter) { dismiss ->
        Text(stringResource(Res.string.composer_arrange_reference), style = MaterialTheme.typography.labelMedium,
            color = ImagoUi.colors.secondaryText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        ImagoChipRow(if (units < 2) listOf(CompositionArrangementReference.PAGE) else CompositionArrangementReference.entries,
            selected = reference,
            label = { if (it == CompositionArrangementReference.PAGE) stringResource(Res.string.composer_page_n, state.currentPage + 1)
                else stringResource(Res.string.composer_reference_selection) },
            onSelect = actions::setArrangementReference, contentPadding = 12.dp,
            modifier = Modifier.widthIn(min = 260.dp).testTag("composer-arrangement-reference"))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ToolMenuItem(stringResource(Res.string.composer_align_left), Icons.AutoMirrored.Outlined.AlignHorizontalLeft, dismiss) { actions.alignSelectedHorizontal(-1) }
        ToolMenuItem(stringResource(Res.string.composer_center_horizontally), Icons.Outlined.AlignHorizontalCenter, dismiss) { actions.alignSelectedHorizontal(0) }
        ToolMenuItem(stringResource(Res.string.composer_align_right), Icons.AutoMirrored.Outlined.AlignHorizontalRight, dismiss) { actions.alignSelectedHorizontal(1) }
        ToolMenuItem(stringResource(Res.string.composer_align_top), Icons.Outlined.AlignVerticalTop, dismiss) { actions.alignSelectedVertical(-1) }
        ToolMenuItem(stringResource(Res.string.composer_center_vertically), Icons.Outlined.AlignVerticalCenter, dismiss) { actions.alignSelectedVertical(0) }
        ToolMenuItem(stringResource(Res.string.composer_align_bottom), Icons.Outlined.AlignVerticalBottom, dismiss) { actions.alignSelectedVertical(1) }
        if (units >= if (reference == CompositionArrangementReference.PAGE) 2 else 3) {
            HorizontalDivider()
            ToolMenuItem(stringResource(Res.string.composer_distribute_horizontally), Icons.Outlined.AlignHorizontalCenter, dismiss) { actions.distributeSelected(true) }
            ToolMenuItem(stringResource(Res.string.composer_distribute_vertically), Icons.Outlined.AlignVerticalCenter, dismiss) { actions.distributeSelected(false) }
        }
    }
}

@Composable
internal fun ComposerGroupMergeDialog(state: ComposerEditorUiState, actions: ComposerEditorViewModel) {
    val request = state.groupMergeRequest ?: return
    AlertDialog(onDismissRequest = actions::cancelGroupMerge,
        title = { Text(stringResource(Res.string.composer_combine_groups)) },
        text = { Text(stringResource(Res.string.composer_combine_groups_message, request.members.size)) },
        confirmButton = { TextButton(onClick = actions::confirmGroupMerge, modifier = Modifier.testTag("composer-confirm-group-merge")) {
            Text(stringResource(Res.string.composer_combine_groups))
        } },
        dismissButton = { TextButton(onClick = actions::cancelGroupMerge) { Text(stringResource(Res.string.composer_cancel)) } },
    )
}
