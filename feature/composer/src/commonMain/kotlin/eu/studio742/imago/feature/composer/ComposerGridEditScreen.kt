package eu.studio742.imago.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.*
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource

/** A dedicated stage keeps cell swapping separate from element selection and group gestures. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ComposerGridEditScreen(state: ComposerEditorUiState, actions: ComposerEditorViewModel, snackbar: SnackbarHostState) {
    val editor = state.gridEditor ?: return
    val layout = editor.layout
    val preview = remember(editor) { editor.preview() }
    val colors = ImagoUi.colors
    var settings by remember(layout.groupId) { mutableStateOf(false) }
    var pickingOutline by remember(layout.groupId) { mutableStateOf<Boolean?>(null) }
    var pickingMedia by remember(layout.groupId) { mutableStateOf(false) }
    val keyboardFocus = remember { FocusRequester() }
    fun cancel() {
        when {
            pickingMedia -> pickingMedia = false
            pickingOutline != null -> pickingOutline = null
            else -> actions.cancelGrid()
        }
    }
    BackHandler { cancel() }
    LaunchedEffect(layout.groupId) { keyboardFocus.requestFocus() }
    Surface(Modifier.fillMaxSize().testTag("composer-grid-editor").focusRequester(keyboardFocus)
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { cancel(); true } else false
        }.focusable(), color = colors.background, contentColor = colors.text) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(colors.surface).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::cancelGrid) { Text(stringResource(Res.string.composer_cancel)) }
                Text(stringResource(Res.string.composer_edit_grid), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                TextButton(onClick = actions::confirmGrid, modifier = Modifier.testTag("grid-confirm")) {
                    Text(stringResource(Res.string.composer_apply))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("grid-preview")) {
                ComposerCanvas(preview, state.copy(project = preview, selectedElementId = null, selectedElementIds = emptySet(),
                    multiSelect = false, editingGroupId = null, backgroundSelected = false, movementGuides = emptyList()),
                    drawing = false, drawingKind = StrokeKind.PEN, multiSelect = false, positioningBackground = false, positioningMedia = false,
                    previewUrl = actions::previewUrl, thumbnailUrl = actions::thumbnailUrl, videoPlaybackUrl = actions::videoPlaybackUrl,
                    apiKey = actions::apiKey, actions = actions, onDrawing = {}, onPickMediaFor = {}, onFrameMedia = {},
                    stageInsets = ComposerStageInsets(0.dp, 0.dp), modifier = Modifier.fillMaxSize(), gridEditor = editor)
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            if (settings) {
                Column(Modifier.fillMaxWidth().heightIn(max = LocalImagoWindow.current.height * .4f)
                    .background(colors.surface).verticalScroll(rememberScrollState()).padding(16.dp).testTag("grid-stage-settings"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.composer_grid_target), style = MaterialTheme.typography.labelMedium)
                    ImagoChipRow(CompositionArrangementReference.entries, editor.reference,
                        label = { if (it == CompositionArrangementReference.PAGE) stringResource(Res.string.composer_page_n, editor.pageIndex + 1)
                            else stringResource(Res.string.composer_reference_selection) }, onSelect = actions::setGridReference)
                    GridLayoutControls(state, actions) { pickingOutline = it }
                }
            }
            Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(stringResource(Res.string.composer_grid_swap_hint), color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { pickingMedia = true }, modifier = Modifier.testTag("grid-add-elements")) {
                        Text(stringResource(Res.string.composer_grid_add_elements))
                    }
                    TextButton(onClick = { settings = !settings }, modifier = Modifier.testTag("grid-toggle-settings")) {
                        Text(stringResource(Res.string.composer_adjust))
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalEyedropper provides null) {
        pickingOutline?.let { outline ->
            val style = if (outline) layout.outline else layout.separators
            ColorPickerDialog(stringResource(if (outline) Res.string.composer_grid_outer_outline else Res.string.composer_grid_separators),
                style.argb, state.brandKit?.paletteArgb.orEmpty(), onDismiss = { pickingOutline = null },
                compositionColors = preview.usedColors(), onConfirm = { color ->
                    actions.updateGridDraft(if (outline) layout.copy(outline = style.copy(argb = color))
                        else layout.copy(separators = style.copy(argb = color)))
                    pickingOutline = null
                })
        }
    }
    if (pickingMedia) LocalComposerMediaPicker.current(MediaPickerTarget.Grid(layout.groupId, draft = true),
        { pickingMedia = false }, { chosen ->
            actions.addMediaToGrid(chosen, layout.groupId, draft = true)
            pickingMedia = false
        })
}
