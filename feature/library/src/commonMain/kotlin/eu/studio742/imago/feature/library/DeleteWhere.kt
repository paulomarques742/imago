package eu.studio742.imago.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Where a photo on both sides — the phone and the server — is deleted from. */
enum class DeleteWhere { DEVICE, SERVER, BOTH }

/**
 * What to delete for each shown photo, given its copy on the other side ([counterparts], by the
 * shown id). A photo on one side only goes from that side whatever was chosen; the choice is for
 * the ones on both.
 */
fun idsToDelete(shown: List<String>, counterparts: Map<String, String>, where: DeleteWhere): List<String> = shown.flatMap { id ->
    val other = counterparts[id] ?: return@flatMap listOf(id)
    val onDevice = runCatching { AssetReference.parse(id).libraryId == DEVICE_LIBRARY_ID }.getOrDefault(false)
    val phoneCopy = if (onDevice) id else other
    val serverCopy = if (onDevice) other else id
    when (where) {
        DeleteWhere.DEVICE -> listOf(phoneCopy)
        DeleteWhere.SERVER -> listOf(serverCopy)
        DeleteWhere.BOTH -> listOf(phoneCopy, serverCopy)
    }
}

/** Asked when [count] of the photos to delete are on the phone and on the server. */
@Composable
fun DeleteWhereDialog(count: Int, onChoose: (DeleteWhere) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_delete_where_question)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(pluralStringResource(Res.plurals.library_delete_where_body, count, count))
                TextButton(onClick = { onChoose(DeleteWhere.DEVICE) }) { Text(stringResource(Res.string.library_delete_where_device)) }
                TextButton(onClick = { onChoose(DeleteWhere.SERVER) }) { Text(stringResource(Res.string.library_delete_where_server)) }
                TextButton(onClick = { onChoose(DeleteWhere.BOTH) }) {
                    Text(stringResource(Res.string.library_delete_where_both), color = ImagoColors.Danger)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_cancel)) } },
    )
}
