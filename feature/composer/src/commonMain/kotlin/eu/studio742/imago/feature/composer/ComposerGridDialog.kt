package eu.studio742.imago.feature.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun ComposerGridDialog(state: ComposerEditorUiState, actions: ComposerEditorViewModel,
    thumbnailUrl: (String) -> String, apiKey: (String) -> String,
    onAddMedia: ((String) -> Unit)? = null,
) {
    val editor = state.gridEditor ?: return
    val layout = editor.layout; val colors = ImagoUi.colors
    val feedback = LocalHapticFeedback.current
    val preview = remember(editor) { editor.preview() }
    var pickingOutline by remember(layout.groupId) { mutableStateOf<Boolean?>(null) }
    var swapDrag by remember(layout.groupId) { mutableStateOf<GridSwapDrag?>(null) }
    fun update(next: CompositionLayout) = actions.updateGridLayout(next)
    pickingOutline?.let { outline -> GridStrokeColorDialog(state, actions, layout, preview, outline) { pickingOutline = null } }
    Dialog(onDismissRequest = actions::cancelGrid) {
        Surface(Modifier.widthIn(max = minOf(600.dp, LocalImagoWindow.current.width - 32.dp)).fillMaxWidth()
            .heightIn(max = LocalImagoWindow.current.height - 32.dp).fillMaxHeight(.92f).testTag("composer-grid-dialog"),
            shape = MaterialTheme.shapes.large, color = colors.surface, contentColor = colors.text) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(if (editor.base.layouts.any { it.groupId == layout.groupId }) Res.string.composer_edit_grid else Res.string.composer_create_grid),
                    style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.composer_grid_target), style = MaterialTheme.typography.labelMedium, color = colors.secondaryText)
                    ImagoChipRow(CompositionArrangementReference.entries, editor.reference,
                        label = { if (it == CompositionArrangementReference.PAGE) stringResource(Res.string.composer_page_n, editor.pageIndex + 1)
                            else stringResource(Res.string.composer_reference_selection) }, onSelect = actions::setGridReference)
                    BoxWithConstraints(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        val originalAspect = preview.format.aspectRatio
                        val target = if (editor.reference == CompositionArrangementReference.PAGE) NormalizedRect(editor.pageIndex.toFloat(), 0f, 1f, 1f)
                            else layout.proxy().visualBounds(aspect = originalAspect).let { it.copy(x = it.x - it.width * .03f,
                                y = it.y - it.height * .03f, width = it.width * 1.06f, height = it.height * 1.06f) }
                        val aspect = target.width * originalAspect / target.height
                        val width = minOf(maxWidth, maxHeight * aspect); val height = width / aspect
                        val pageWidth = width / target.width; val pageHeight = height / target.height
                        val density = LocalDensity.current
                        val wp = with(density) { pageWidth.toPx() }; val hp = with(density) { pageHeight.toPx() }
                        val origin = Offset(-target.x * wp, -target.y * hp)
                        val adjust: (String, Int, Float) -> Unit = { split, index, delta -> update(layout.copy(root = layout.root.adjustDivider(split, index, delta))) }
                        Box(Modifier.size(width, height).clipToBounds().background(colors.workspace).testTag("grid-preview")
                            .composerGridDividerGestures(layout, originalAspect, origin, wp, hp, {}, {}, adjust)
                            .composerGridSwapGestures(preview, layout, origin, wp, hp, { swapDrag = it }, actions::swapGridMembers,
                                { feedback.performHapticFeedback(HapticFeedbackType.LongPress) })) {
                            preview.pages.filter { target.intersectionWithPage(it.index) != null }.forEach { page ->
                                Box(Modifier.offset(pageWidth * (page.index - target.x), pageHeight * -target.y)
                                    .wrapContentSize(Alignment.TopStart, unbounded = true).size(pageWidth, pageHeight)
                                    .border(1.dp, colors.border).testTag("grid-preview-page-${page.index}")) {
                                    CompositionPageCanvas(preview, page, pageWidth, pageHeight, thumbnailUrl, apiKey)
                                }
                            }
                            ComposerGridDividers(layout, originalAspect, origin, wp, hp, {}, {},
                                onAdjust = adjust)
                            ComposerGridSwapOverlay(preview, layout, origin, wp, hp, swapDrag, thumbnailUrl, apiKey, actions::swapGridMembers)
                        }
                    }
                    Text(stringResource(Res.string.composer_grid_drag_hint), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                    Text(stringResource(Res.string.composer_grid_swap_hint), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                    if (onAddMedia != null) OutlinedButton(onClick = { onAddMedia(layout.groupId) }, modifier = Modifier.testTag("grid-add-elements")) {
                        Text(stringResource(Res.string.composer_grid_add_elements))
                    }
                    GridLayoutControls(state, actions) { pickingOutline = it }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions::cancelGrid) { Text(stringResource(Res.string.composer_cancel)) }
                    Button(onClick = actions::confirmGrid, modifier = Modifier.testTag("grid-confirm")) { Text(stringResource(Res.string.composer_apply)) }
                }
            }
        }
    }
}

@Composable
private fun GridStrokeColorDialog(state: ComposerEditorUiState, actions: ComposerEditorViewModel, layout: CompositionLayout,
    project: CompositionProject, outline: Boolean, onDismiss: () -> Unit) {
    val style = if (outline) layout.outline else layout.separators
    CompositionLocalProvider(LocalEyedropper provides null) {
        ColorPickerDialog(stringResource(if (outline) Res.string.composer_grid_outer_outline else Res.string.composer_grid_separators),
            style.argb, state.brandKit?.paletteArgb.orEmpty(), onDismiss = onDismiss,
            compositionColors = project.usedColors(), onConfirm = {
                actions.updateGridLayout(if (outline) layout.copy(outline = style.copy(argb = it)) else layout.copy(separators = style.copy(argb = it)))
                onDismiss()
            })
    }
}

/** The grid's settings on the stage, in the panel the selected element's tools use. The rail scrolls on its own. */
@Composable
internal fun GridInspector(state: ComposerEditorUiState, actions: ComposerEditorViewModel, rail: Boolean) {
    val layout = state.editedLayout() ?: return
    val project = state.project ?: return
    var pickingOutline by remember(layout.groupId) { mutableStateOf<Boolean?>(null) }
    val page = layout.pageIndex()
    val fillsPage = layout.frame.rotationDegrees == 0f && layout.frame.bounds == NormalizedRect(page.toFloat(), 0f, 1f, 1f)
    Column(Modifier.fillMaxWidth().then(if (rail) Modifier else Modifier.heightIn(max = LocalImagoWindow.current.height * .4f).verticalScroll(rememberScrollState()))
        .padding(16.dp).testTag("grid-stage-settings"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(Res.string.composer_grid_target), style = MaterialTheme.typography.labelMedium)
        ImagoChipRow(CompositionArrangementReference.entries, if (fillsPage) CompositionArrangementReference.PAGE else CompositionArrangementReference.SELECTION,
            label = { if (it == CompositionArrangementReference.PAGE) stringResource(Res.string.composer_page_n, page + 1)
                else stringResource(Res.string.composer_reference_selection) }, onSelect = actions::setGridReference)
        GridLayoutControls(state, actions) { pickingOutline = it }
    }
    pickingOutline?.let { outline -> GridStrokeColorDialog(state, actions, layout, project, outline) { pickingOutline = null } }
}

@Composable
internal fun GridLayoutControls(state: ComposerEditorUiState, actions: ComposerEditorViewModel, onPickOutline: (Boolean) -> Unit) {
    val layout = state.gridEditor?.layout ?: state.editedLayout() ?: return
    val elements = state.gridEditor?.base?.elements ?: state.project?.elements.orEmpty()
    val colors = ImagoUi.colors
    fun update(next: CompositionLayout) = actions.updateGridLayout(next)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompositionLayoutKind.entries.forEach { kind ->
            val title = stringResource(kind.label())
            Surface(shape = MaterialTheme.shapes.small, color = if (layout.kind == kind) colors.selectedSurface else colors.elevated,
                modifier = Modifier.width(92.dp).selectable(layout.kind == kind, role = Role.RadioButton, onClick = { actions.setGridKind(kind) })
                    .testTag("grid-kind-${kind.name}")) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val schematic = remember(layout.root.memberIds(), kind) {
                        layout.copy(frame = ElementTransform(NormalizedRect(0f, 0f, 1f, 1f)),
                            root = layoutTree(layout.root.memberIds(), kind, layout.columns))
                    }
                    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                        schematic.geometry(1.5f).cells.values.forEachIndexed { index, cell ->
                            drawRect(if (index == 0) colors.accent else colors.secondaryText,
                                Offset(cell.x * size.width, cell.y * size.height), Size(cell.width * size.width, cell.height * size.height))
                        }
                    }
                    Text(title, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    if (layout.kind == CompositionLayoutKind.GRID) {
        val count = layout.root.memberIds().size
        GridSlider(stringResource(Res.string.composer_grid_columns), layout.columns.toFloat(), 1f..count.toFloat(), "grid-columns", actions, integer = true) {
            actions.setGridKind(layout.kind, columns = it.roundToInt())
        }
        val rows = maxOf(layout.rows, ceil(count.toDouble() / layout.columns).toInt())
        GridSlider(stringResource(Res.string.composer_grid_rows), rows.toFloat(), 1f..count.toFloat(), "grid-rows", actions, integer = true) {
            val wanted = it.roundToInt().coerceIn(1, count)
            actions.setGridKind(layout.kind, columns = ceil(count.toDouble() / wanted).toInt(), rows = wanted)
        }
    }
    GridSlider(stringResource(Res.string.composer_grid_margin), layout.margin, 0f.. .2f, "grid-margin", actions) { update(layout.copy(margin = it)) }
    GridSlider(stringResource(Res.string.composer_grid_spacing), layout.gap, 0f.. .15f, "grid-spacing", actions) { update(layout.copy(gap = it)) }
    GridStrokeControls(stringResource(Res.string.composer_grid_separators), layout.separators, "separators", actions,
        onColor = { onPickOutline(false) }, onChange = { update(layout.copy(separators = it)) })
    GridStrokeControls(stringResource(Res.string.composer_grid_outer_outline), layout.outline, "outer", actions,
        onColor = { onPickOutline(true) }, onChange = { update(layout.copy(outline = it)) })
    if (state.gridEditor?.combinesGroups == true) Text(stringResource(Res.string.composer_grid_combines_groups),
        style = MaterialTheme.typography.bodySmall, color = colors.secondaryText, modifier = Modifier.testTag("grid-combines-groups"))
    val hasFrames = elements.any { it.id in layout.root.memberIds() && when (it) {
        is CompositionElement.Photo -> it.frame.borderWidth > 0f
        is CompositionElement.Video -> it.frame.borderWidth > 0f
        is CompositionElement.MediaPlaceholder -> it.frame.borderWidth > 0f
        else -> false
    } }
    if (hasFrames) Text(stringResource(Res.string.composer_grid_frames_notice), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
}

private fun CompositionLayoutKind.label(): StringResource = when (this) {
    CompositionLayoutKind.ROW -> Res.string.composer_grid_row
    CompositionLayoutKind.COLUMN -> Res.string.composer_grid_column
    CompositionLayoutKind.GRID -> Res.string.composer_grid_regular
    CompositionLayoutKind.FEATURE_LEFT -> Res.string.composer_grid_feature_left
    CompositionLayoutKind.FEATURE_TOP -> Res.string.composer_grid_feature_top
}

@Composable
private fun GridStrokeControls(label: String, style: LayoutStroke, tag: String, actions: ComposerEditorViewModel, onColor: () -> Unit, onChange: (LayoutStroke) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = ImagoUi.colors.text)
        ColorDot(style.argb, label, onColor)
    }
    GridSlider(stringResource(Res.string.composer_thickness), style.width, 0f.. .05f, "grid-$tag-width", actions) { onChange(style.copy(width = it)) }
    GridSlider(stringResource(Res.string.composer_opacity), style.opacity, 0f..1f, "grid-$tag-opacity", actions) { onChange(style.copy(opacity = it)) }
}

@Composable
private fun GridSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tag: String, actions: ComposerEditorViewModel,
    integer: Boolean = false, onChange: (Float) -> Unit) {
    // On the stage each step reaches the project: the whole drag has to be a single undo step.
    var editing by remember(label) { mutableStateOf(false) }
    val finish = { if (editing) { editing = false; actions.endGesture(stage = false) } }
    DisposableEffect(actions, label) { onDispose { finish() } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ImagoUi.colors.text, modifier = Modifier.width(82.dp))
        ImagoSlider(value, {
            if (!editing) { actions.beginGesture(stage = false); editing = true }
            onChange(it)
        }, range, range.start, Modifier.weight(1f).testTag(tag).semantics { contentDescription = label }, onValueChangeFinished = finish)
        Text(if (integer) value.roundToInt().toString() else stringResource(Res.string.composer_percent_value, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelMedium, color = ImagoUi.colors.secondaryText, modifier = Modifier.widthIn(min = 36.dp).padding(start = 6.dp))
    }
}
