package eu.studio742.imago.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.studio742.imago.core.data.DeviceLibrary
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The hidden ones after the person confirmed [shown] out of [listed]. An album hidden earlier that is
 * not listed now — emptied, or its card unplugged — stays hidden for when it comes back.
 */
internal fun hiddenAfterChoosing(hidden: Set<String>, listed: List<ImmichAlbum>, shown: Set<String>): Set<String> {
    val ids = listed.map { it.id }.toSet()
    return (hidden - ids) + (ids - shown)
}

/** Which of this device's albums "All" shows: screenshots and what messaging apps save need not. */
@Composable
internal fun AllViewAlbumsSetting(device: DeviceLibrary) {
    val hidden by remember(device) { device.hiddenAlbums() }.collectAsStateWithLifecycle(emptySet())
    var choosing by remember { mutableStateOf(false) }
    // The settings page outlives the dialog: saving from the dialog's own scope was cancelled as it closed.
    val scope = rememberCoroutineScope()
    Text(stringResource(Res.string.library_all_albums_setting), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
    Text(
        if (hidden.isEmpty()) stringResource(Res.string.library_all_albums_every)
        else pluralStringResource(Res.plurals.library_all_albums_hidden, hidden.size, hidden.size),
        style = MaterialTheme.typography.bodySmall,
        color = ImagoColors.TextSecondary,
    )
    OutlinedButton(onClick = { choosing = true }) { Text(stringResource(Res.string.library_all_albums_choose)) }
    if (choosing) {
        AllViewAlbumsDialog(
            device = device,
            hidden = hidden,
            onSave = { chosen -> scope.launch { device.setHiddenAlbums(chosen) } },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun AllViewAlbumsDialog(device: DeviceLibrary, hidden: Set<String>, onSave: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val albums by produceState<List<ImmichAlbum>?>(null, device) {
        value = runCatching { device.albums() }.getOrDefault(emptyList())
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
    var shown by remember(albums) { mutableStateOf(albums.orEmpty().map { it.id }.toSet() - hidden) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_all_albums_setting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                Text(stringResource(Res.string.library_all_albums_body), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
                val listed = albums
                when {
                    listed == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    listed.isEmpty() -> Text(stringResource(Res.string.library_all_albums_empty), style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        TextButton(onClick = { shown = listed.map { it.id }.toSet() }) {
                            Text(stringResource(Res.string.library_all_albums_show_all))
                        }
                        // A phone can have dozens of folders: the list scrolls inside the dialog.
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(listed, key = { it.id }) { album ->
                                val checked = album.id in shown
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = ImagoSizes.TouchTarget)
                                        .toggleable(value = checked, role = Role.Checkbox) { shown = if (it) shown + album.id else shown - album.id },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Text(
                                        album.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(start = ImagoSpacing.Md),
                                    )
                                    Text(
                                        album.assetCount.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ImagoColors.TextSecondary,
                                        modifier = Modifier.padding(start = ImagoSpacing.Sm),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = albums != null,
                onClick = {
                    onSave(hiddenAfterChoosing(hidden, albums.orEmpty(), shown))
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.library_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_cancel)) } },
    )
}
