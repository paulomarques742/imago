package eu.studio742.imago.feature.composer

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.*
import eu.studio742.imago.core.designsystem.ImagoChipRow
import eu.studio742.imago.core.designsystem.LocalImagoWindow
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ComposerTextTools(text: CompositionElement.Text, state: ComposerEditorUiState,
    actions: ComposerEditorViewModel, rail: Boolean) {
    val ui = ImagoUi.colors
    val keyboard = LocalSoftwareKeyboardController.current
    var advanced by remember(text.id) { mutableStateOf(false) }
    var styles by remember(text.id) { mutableStateOf(false) }
    var naming by remember(text.id) { mutableStateOf<Pair<String?, String>?>(null) }
    var color by remember(text.id) { mutableStateOf<PendingColor?>(null) }
    val session = state.textSession
    val propertiesHeight = (LocalImagoWindow.current.height * .35f).coerceIn(120.dp, 280.dp)
    Column(Modifier.padding(horizontal = 8.dp)) {
        if (session?.contentEditing == true) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = actions::cancelTextEditing) { Text(stringResource(Res.string.composer_cancel)) }
                TextButton(onClick = actions::confirmTextEditing, modifier = Modifier.testTag("composer_text_apply")) { Text(stringResource(Res.string.composer_apply)) }
            }
        }
        ComposerToolRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(stringResource(Res.string.composer_edit_text), Icons.Outlined.Edit) { actions.beginTextEditing(text.id) }
            ToolButton(CompositionFonts.resolve(text.fontFamily).displayName, Icons.Outlined.TextFields,
                active = session?.fontBeforePreview != null, onClick = { keyboard?.hide(); actions.beginFontPreview() })
            ToolButton(stringResource(Res.string.composer_color), Icons.Outlined.Colorize) {
                color = PendingColor(uiText(Res.string.composer_text_color), text.colorArgb, actions::setElementColor)
            }
            ToolButton(stringResource(Res.string.composer_text_styles), Icons.Outlined.Style, active = styles) { styles = !styles }
            ToolButton(stringResource(Res.string.composer_adjust), Icons.Outlined.Tune, active = advanced) { advanced = !advanced }
        }
        if (session?.fontBeforePreview != null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                CompositionFonts.all.forEach { font ->
                    TextButton(onClick = { actions.setTextFont(font.id) }) {
                        Text(font.displayName, fontFamily = rememberCompositionFontFamily(font.id, text.fontWeight),
                            color = if (font.id == text.fontFamily) ui.accent else ui.text)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { actions.finishFontPreview(false) }) { Text(stringResource(Res.string.composer_cancel)) }
                TextButton(onClick = { actions.finishFontPreview(true) }) { Text(stringResource(Res.string.composer_apply)) }
            }
        }
        Column(Modifier.heightIn(max = propertiesHeight).verticalScroll(rememberScrollState())) {
        if (session?.contentEditing != true || advanced) {
            TextStyleControls(text, actions, rail, onPickColor = { color = it }, advanced = advanced)
        }
        if (styles) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = actions::copyTextStyle) { Text(stringResource(Res.string.composer_copy_text_style)) }
                if (state.copiedTextStyle != null) TextButton(onClick = actions::pasteTextStyle) { Text(stringResource(Res.string.composer_paste_text_style)) }
                TextButton(onClick = { naming = null to "" }) { Text(stringResource(Res.string.composer_save_text_style)) }
            }
            state.brandKit?.textStyles.orEmpty().forEach { saved ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.applyTextStyle(saved.style) }, modifier = Modifier.weight(1f)) { Text(saved.name) }
                    IconButton(onClick = { naming = saved.id to saved.name }) { Icon(Icons.Outlined.Edit, stringResource(Res.string.composer_rename), tint = ui.text) }
                    IconButton(onClick = { actions.deleteTextStyle(saved.id) }) { Icon(Icons.Outlined.Delete, stringResource(Res.string.composer_delete), tint = ui.danger) }
                }
            }
        }
        if (advanced) {
            val inGrid = state.project?.layouts?.any { it.groupId == text.groupId } == true
            if (!inGrid) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.composer_text_auto_height), color = ui.text, modifier = Modifier.weight(1f))
                    Switch(checked = text.autoHeight, onCheckedChange = actions::setTextAutoHeight,
                        colors = SwitchDefaults.colors(checkedTrackColor = ui.accent))
                }
            }
            if (!text.autoHeight && !inGrid) {
                InspectorSlider(stringResource(Res.string.composer_text_box_height), text.transform.bounds.height * 100f,
                    MIN_ELEMENT_SIZE * 100f..maxOf(100f, text.transform.bounds.height * 100f), 20f, actions) { actions.setTextBoxHeight(it / 100f) }
            }
            if (!text.autoHeight || inGrid) {
                Text(stringResource(Res.string.composer_text_vertical_alignment), color = ui.secondaryText, style = MaterialTheme.typography.labelMedium)
                ImagoChipRow(TextVerticalAlignment.entries, text.verticalAlignment, label = { alignment ->
                    when (alignment) {
                        TextVerticalAlignment.TOP -> stringResource(Res.string.composer_align_top)
                        TextVerticalAlignment.CENTER -> stringResource(Res.string.composer_center_vertically)
                        TextVerticalAlignment.BOTTOM -> stringResource(Res.string.composer_align_bottom)
                    }
                }, onSelect = actions::setTextVerticalAlignment)
            }
            InspectorSlider(stringResource(Res.string.composer_opacity), text.transform.opacity, .05f..1f, 1f, actions, actions::setSelectedOpacity)
            InspectorSlider(stringResource(Res.string.composer_rotation), text.transform.rotationDegrees, 0f..360f, 0f, actions) { actions.setSelectedRotation(it) }
            ComposerToolRow {
                ToolButton(stringResource(Res.string.composer_flip_h), Icons.Outlined.Flip, onClick = actions::toggleMirrorHorizontal)
                ToolButton(stringResource(Res.string.composer_flip_v), Icons.Outlined.Flip, onClick = actions::toggleMirrorVertical)
                ToolButton(stringResource(Res.string.composer_duplicate), Icons.Outlined.ContentCopy) { actions.confirmTextEditing(); actions.duplicateSelected() }
                ToolButton(stringResource(Res.string.composer_lock), Icons.Outlined.Lock) { actions.confirmTextEditing(); actions.toggleLocked() }
                ToolButton(stringResource(Res.string.composer_delete), Icons.Outlined.Delete) { actions.confirmTextEditing(); actions.deleteSelected() }
            }
        }
        }
    }
    naming?.let { (id, initial) ->
        var name by remember(id, initial) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = { naming = null }, title = { Text(stringResource(Res.string.composer_text_styles)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(Res.string.composer_name)) }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                if (id == null) actions.saveTextStyle(name) else actions.renameTextStyle(id, name)
                naming = null
            }) { Text(stringResource(Res.string.composer_apply)) } },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(Res.string.composer_cancel)) } })
    }
    color?.let { pending -> ColorPickerDialog(pending.title.resolve(), pending.initial, state.brandKit?.paletteArgb.orEmpty(),
        compositionColors = state.displayProject()?.usedColors().orEmpty(), onDismiss = { color = null }, onConfirm = pending.onPick) }
}
