@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package eu.studio742.imago.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun ComposerLayersList(state: ComposerEditorUiState, actions: ComposerEditorViewModel, modifier: Modifier = Modifier) {
    val project = state.project ?: return
    val units = project.elements.sortedWith(compareBy<CompositionElement> { it.zIndex }.thenBy { it.id }).selectionUnits().asReversed()
    val expanded = remember(project.id) { mutableStateMapOf<String, Boolean>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var savedExpanded by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val rowHeight = with(LocalDensity.current) { 60.dp.toPx() }
    LaunchedEffect(state.editingGroupId) { state.editingGroupId?.let { expanded[it] = true } }
    val finishDrag = { dragging = null; dragOffset = 0f; expanded.putAll(savedExpanded); savedExpanded = emptyMap() }
    LazyColumn(modifier.testTag("composer-layers-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var groupNumber = 0
        units.forEachIndexed { unitIndex, unit ->
            val group = unit.groupId
            val members = unit.members.sortedWith(compareByDescending<CompositionElement> { it.zIndex }.thenBy { it.id })
            if (group != null) {
                groupNumber++
                val number = groupNumber
                item(key = unit.key) {
                    GroupLayerHeader(group, number, members, expanded[group] == true,
                        selected = state.editingGroupId == group || members.all { it.id in state.selectedElementIds },
                        onExpand = { expanded[group] = expanded[group] != true }, state = state, actions = actions,
                        index = unitIndex, lastIndex = units.lastIndex,
                        onDragStart = { dragging = unit.key; dragOffset = 0f; savedExpanded = expanded.toMap(); expanded.clear() },
                        onDrag = { dy ->
                            dragOffset += dy
                            val steps = (dragOffset / rowHeight).roundToInt()
                            val target = (unitIndex + steps).coerceIn(units.indices)
                            if (target != unitIndex) { actions.reorderLayer(unitIndex, target); dragOffset -= steps * rowHeight }
                        }, onDragEnd = finishDrag)
                }
                if (expanded[group] == true) members.forEachIndexed { memberIndex, element ->
                    item(key = "member:${element.id}") {
                        LayerRow(element, memberIndex, members.lastIndex,
                            selected = state.editingGroupId == group && element.id in state.selectedElementIds,
                            dragging = dragging == element.id, dragOffset = if (dragging == element.id) dragOffset else 0f,
                            thumbnailUrl = actions::thumbnailUrl, apiKey = actions::apiKey, actions = actions,
                            onDragStart = { dragging = element.id; dragOffset = 0f; actions.focusGroupMember(element.id) },
                            onDrag = { dy ->
                                dragOffset += dy
                                val steps = (dragOffset / rowHeight).roundToInt()
                                val target = (memberIndex + steps).coerceIn(members.indices)
                                if (target != memberIndex) { actions.reorderGroupMember(group, memberIndex, target); dragOffset -= steps * rowHeight }
                            }, onDragEnd = finishDrag, memberOfGroup = true)
                    }
                }
            } else {
                val element = members.single()
                item(key = unit.key) {
                    LayerRow(element, unitIndex, units.lastIndex, selected = element.id in state.selectedElementIds,
                        dragging = dragging == element.id, dragOffset = if (dragging == element.id) dragOffset else 0f,
                        thumbnailUrl = actions::thumbnailUrl, apiKey = actions::apiKey, actions = actions,
                        onDragStart = { dragging = element.id; dragOffset = 0f; savedExpanded = expanded.toMap(); expanded.clear() },
                        onDrag = { dy ->
                            dragOffset += dy
                            val steps = (dragOffset / rowHeight).roundToInt()
                            val target = (unitIndex + steps).coerceIn(units.indices)
                            if (target != unitIndex) { actions.reorderLayer(unitIndex, target); dragOffset -= steps * rowHeight }
                        }, onDragEnd = finishDrag)
                }
            }
        }
    }
}

@Composable
private fun GroupLayerHeader(
    group: String, number: Int, members: List<CompositionElement>, expanded: Boolean, selected: Boolean,
    onExpand: () -> Unit, state: ComposerEditorUiState, actions: ComposerEditorViewModel,
    index: Int, lastIndex: Int, onDragStart: () -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit,
) {
    val colors = ImagoUi.colors
    val haptic = LocalHapticFeedback.current
    val label = stringResource(Res.string.composer_group_label, number)
    val locked = members.any { it.locked }
    val visible = members.any { it.visible }
    val forward = stringResource(Res.string.composer_layer_forward)
    val backward = stringResource(Res.string.composer_layer_backward)
    var menu by remember(group) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(ImagoRadii.Medium))
        .background(if (selected) colors.selectedSurface else colors.elevated)
        .testTag("composer-group-$group")
        .combinedClickable(enabled = !locked,
            onClick = { if (state.multiSelect && state.editingGroupId == null) actions.toggleElementInSelection(members.first().id) else actions.selectGroup(group) },
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); actions.beginMultiSelection(members.first().id) })
        .semantics {
            customActions = if (locked) emptyList() else listOf(
                CustomAccessibilityAction(forward) { if (index > 0) { actions.reorderLayer(index, index - 1); true } else false },
                CustomAccessibilityAction(backward) { if (index < lastIndex) { actions.reorderLayer(index, index + 1); true } else false })
        }, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onExpand, modifier = Modifier.testTag("composer-expand-group-$group")) {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                stringResource(if (expanded) Res.string.composer_collapse_group else Res.string.composer_expand_group, label), tint = colors.secondaryText)
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = if (visible) colors.text else colors.disabledText, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(Res.plurals.composer_group_members, members.size, members.size),
                    color = colors.tertiaryText, style = MaterialTheme.typography.labelSmall)
                if (locked) Icon(Icons.Outlined.Lock, null, Modifier.padding(start = 4.dp).size(12.dp), tint = colors.secondaryText)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("composer-group-options-$group")) {
                Icon(Icons.Outlined.MoreVert, stringResource(Res.string.composer_group_options, label), tint = colors.secondaryText)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (members.any { !it.locked }) ToolMenuItem(stringResource(Res.string.composer_edit_group), Icons.Outlined.Edit, { menu = false }) { actions.editGroup(group) }
                ToolMenuItem(stringResource(if (locked) Res.string.composer_unlock else Res.string.composer_lock), Icons.Outlined.Lock, { menu = false }) { actions.toggleGroupLocked(group) }
                ToolMenuItem(stringResource(if (visible) Res.string.composer_hide else Res.string.composer_show), Icons.Outlined.Visibility, { menu = false }) { actions.toggleGroupVisible(group) }
                DropdownMenuItem(text = { Text(stringResource(Res.string.composer_delete_element)) }, enabled = !locked,
                    onClick = { menu = false; actions.deleteGroup(group) })
            }
        }
        val start by rememberUpdatedState(onDragStart)
        val drag by rememberUpdatedState(onDrag)
        val end by rememberUpdatedState(onDragEnd)
        Box(Modifier.size(ImagoSizes.TouchTarget).pointerInput(group, locked) {
            if (!locked) detectDragGesturesAfterLongPress(onDragStart = { start() }, onDragEnd = end, onDragCancel = end) { change, delta ->
                change.consume(); drag(delta.y)
            }
        }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.DragHandle, stringResource(Res.string.composer_reorder_named, label), tint = colors.tertiaryText)
        }
    }
}
