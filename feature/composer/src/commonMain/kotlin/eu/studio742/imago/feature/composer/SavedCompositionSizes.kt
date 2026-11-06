package eu.studio742.imago.feature.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.composition.SavedCompositionFormat
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource

private data class SizeNameRequest(val id: String?, val name: String, val width: Int, val height: Int)

@Composable
internal fun SavedCompositionSizes(
    format: PageFormat, valid: Boolean, state: ComposerFormatsUiState, actions: ComposerFormatsViewModel,
    onFormat: (PageFormat) -> Unit,
) {
    val colors = ImagoUi.colors
    var editing by remember { mutableStateOf<SizeNameRequest?>(null) }
    var deleting by remember { mutableStateOf<SavedCompositionFormat?>(null) }
    LaunchedEffect(state.saved) {
        state.saved?.let { if (editing?.id == null) onFormat(it.format); editing = null; actions.consumeSaved() }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (editing == null) state.error?.let { error ->
            Text(error.resolve(), color = colors.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { actions.reload() }, enabled = !state.isWorking) {
                Text(stringResource(Res.string.composer_retry))
            }
        }
        val matching = state.formats.firstOrNull { it.pixelWidth == format.pixelWidth && it.pixelHeight == format.pixelHeight }
        if (matching == null || !valid) TextButton(
            onClick = { actions.clearError(); editing = SizeNameRequest(null, "", format.pixelWidth, format.pixelHeight) },
            enabled = valid && !state.isLoading && !state.isWorking,
            modifier = Modifier.testTag("composer-save-size"),
        ) { Text(stringResource(Res.string.composer_save_size)) }
        else Text(stringResource(Res.string.composer_size_saved_as, matching.name), color = colors.accent,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        if (state.formats.isNotEmpty()) {
            Text(stringResource(Res.string.composer_saved_sizes), color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(Res.string.composer_sizes_local), color = colors.tertiaryText, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 144.dp).testTag("composer-saved-sizes")) {
                items(state.formats, key = { it.id }) { saved ->
                    var menu by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.isWorking, role = Role.Button) { onFormat(saved.format) }
                        .testTag("composer-saved-size-${saved.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(saved.name, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(Res.string.composer_size_dimensions, saved.pixelWidth, saved.pixelHeight),
                                color = colors.secondaryText, style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !state.isWorking,
                                modifier = Modifier.testTag("composer-size-options-${saved.id}")) {
                                Icon(Icons.Outlined.MoreVert, stringResource(Res.string.composer_size_actions, saved.name), tint = colors.secondaryText)
                            }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(Res.string.composer_rename)) }, onClick = {
                                    menu = false; actions.clearError()
                                    editing = SizeNameRequest(saved.id, saved.name, saved.pixelWidth, saved.pixelHeight)
                                })
                                DropdownMenuItem(text = { Text(stringResource(Res.string.composer_delete)) }, onClick = { menu = false; deleting = saved })
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { request ->
        var name by remember(request) { mutableStateOf(request.name) }
        AlertDialog(onDismissRequest = { if (!state.isWorking) editing = null },
            title = { Text(stringResource(if (request.id == null) Res.string.composer_save_size else Res.string.composer_rename_size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.composer_size_dimensions, request.width, request.height))
                    OutlinedTextField(name, onValueChange = { name = it; actions.clearError() },
                        label = { Text(stringResource(Res.string.composer_format_name)) }, singleLine = true,
                        enabled = !state.isWorking, modifier = Modifier.testTag("composer-size-name"))
                    state.error?.let { Text(it.resolve(), color = colors.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = { actions.save(name, request.width, request.height, request.id) },
                    enabled = name.isNotBlank() && !state.isWorking, modifier = Modifier.testTag("composer-confirm-size")) {
                    Text(stringResource(Res.string.composer_save))
                }
            },
            dismissButton = { TextButton(onClick = { editing = null; actions.clearError() }, enabled = !state.isWorking) { Text(stringResource(Res.string.composer_cancel)) } },
        )
    }
    deleting?.let { saved ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(Res.string.composer_delete_size)) },
            text = { Text(stringResource(Res.string.composer_delete_size_hint, saved.name)) },
            confirmButton = { TextButton(onClick = { actions.delete(saved.id); deleting = null },
                modifier = Modifier.testTag("composer-confirm-delete-size")) { Text(stringResource(Res.string.composer_delete), color = colors.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.composer_cancel)) } },
        )
    }
}
