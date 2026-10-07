package eu.studio742.imago.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What another app accepts, from the types it asked for. */
data class PickerMedia(val photos: Boolean, val videos: Boolean)

/**
 * Reads the types an app asks for — the Intent's own (any image, anything at all, or a MediaStore
 * folder's `vnd.android.cursor.dir/image` in a pick) and any extra list. Nothing usable means
 * anything goes: an app that does not say has to be shown photos and videos alike.
 */
fun pickerAccepts(types: List<String?>): PickerMedia {
    val known = types.filterNotNull().map { it.lowercase() }.filter { it.isNotBlank() }
    if (known.isEmpty() || known.any { it == "*/*" || it == "*" }) return PickerMedia(photos = true, videos = true)
    val photos = known.any { it.startsWith("image/") || it == "vnd.android.cursor.dir/image" }
    val videos = known.any { it.startsWith("video/") || it == "vnd.android.cursor.dir/video" }
    return if (!photos && !videos) PickerMedia(photos = true, videos = true) else PickerMedia(photos, videos)
}

/** Whether a photo in what was chosen has edits, and so the question of which version to hand over. */
internal fun pickNeedsChoice(chosen: List<AssetUiModel>): Boolean = chosen.any { !it.isVideo && it.recipe != null }

/**
 * The library choosing for another app: one or several, as it asked, photos and videos as it
 * accepts them. With edited photos in the choice it asks whether the originals or the edits go.
 *
 * [onPicked] prepares the files and hands them over, reporting each one done; it may take a while
 * (a server's original, an edit rendered at full resolution), and the screen says how far it is.
 */
@Composable
fun PickForOtherAppRoute(
    /** The app asking, by name, when it is known. */
    caller: String?,
    multiple: Boolean,
    allowsPhotos: Boolean,
    allowsVideo: Boolean,
    onPicked: suspend (assets: List<AssetUiModel>, edited: Boolean, progress: (done: Int, total: Int) -> Unit) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<List<AssetUiModel>?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var failure by remember { mutableStateOf<UiText?>(null) }
    val start: (List<AssetUiModel>, Boolean) -> Unit = { chosen, edited ->
        progress = 0 to chosen.size
        scope.launch {
            try {
                onPicked(chosen, edited) { done, total -> progress = done to total }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.toUiText(Res.string.library_pick_failed)
            } finally {
                progress = null
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LibraryPickerRoute(
            title = stringResource(if (multiple) Res.string.library_pick_title_many else Res.string.library_pick_title_one),
            subtitle = caller?.let { stringResource(Res.string.library_pick_for, it) },
            allowsVideo = allowsVideo,
            allowsPhotos = allowsPhotos,
            multiple = multiple,
            confirmLabel = stringResource(Res.string.library_pick_confirm),
            onConfirm = { chosen -> if (pickNeedsChoice(chosen)) asking = chosen else start(chosen, false) },
            onCancel = onCancel,
        )
        progress?.let { (done, total) ->
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = ImagoColors.Ivory)
                    Text(
                        pluralStringResource(Res.plurals.library_selection_preparing, total, (done + 1).coerceAtMost(total), total),
                        color = ImagoColors.TextSecondary,
                        modifier = Modifier.padding(top = ImagoSpacing.Md),
                    )
                }
            }
        }
    }
    asking?.let { chosen ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(Res.string.library_pick_which_question)) },
            text = { Text(stringResource(Res.string.library_pick_which_body)) },
            confirmButton = {
                TextButton(onClick = { asking = null; start(chosen, true) }) { Text(stringResource(Res.string.library_selection_save_edited)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = null; start(chosen, false) }) { Text(stringResource(Res.string.library_selection_save_original)) }
            },
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            text = { Text(message.resolve()) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(Res.string.library_open_close)) } },
        )
    }
}
