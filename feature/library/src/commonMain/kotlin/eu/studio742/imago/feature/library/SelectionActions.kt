package eu.studio742.imago.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoNavBarSurface
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.feature.library.resources.*
import java.io.File
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Saving copies with the person's photos. Rendering an edit and knowing where exports live belong to
 * the editor, which depends on this module; the app hands this in. Null where there is no such
 * thing — on desktop, sharing already is saving copies to a folder.
 */
interface DeviceCopies {
    /** The original as the library serves it: a photo or a video. */
    suspend fun saveOriginal(asset: AssetUiModel)

    /** The photo with its recipe, at full resolution, as the editor's "Save to gallery" makes it. */
    suspend fun saveEdited(asset: AssetUiModel, recipe: EditRecipe)
}

/** Where the shared originals go: the share menu on Android, a folder the person picks on desktop. */
interface SelectionShare {
    /** The folder to download into, empty of anything else; null when the person gave up. */
    suspend fun prepare(): File?

    /** Hands the downloaded files over; the sentence to show afterwards, if there is one. */
    fun deliver(files: List<File>, mimeType: String): UiText?
}

internal enum class CopyKind { ORIGINAL, EDITED }

internal fun AssetUiModel.isOnDevice(): Boolean =
    runCatching { AssetReference.parse(id).libraryId == DEVICE_LIBRARY_ID }.getOrDefault(false)

/** The recipe a copy could apply. A video never has one: the editor works on still images. */
private fun AssetUiModel.copyRecipe(): EditRecipe? = recipe?.takeUnless { isVideo }

/**
 * What saving each chosen asset to the device makes, in the order chosen.
 *
 * One already on the device has only its edit to add, and nothing at all without one. One from a
 * server goes as the original, or as the edit when it has one and [edited] was chosen.
 */
internal fun deviceCopyPlan(selection: List<AssetUiModel>, edited: Boolean): List<Pair<AssetUiModel, CopyKind>> =
    selection.mapNotNull { asset ->
        val canEdit = asset.copyRecipe() != null
        when {
            asset.isOnDevice() -> if (canEdit && edited) asset to CopyKind.EDITED else null
            canEdit && edited -> asset to CopyKind.EDITED
            else -> asset to CopyKind.ORIGINAL
        }
    }

/** Asked once for the whole selection, and only when a server photo in it has edits. */
internal fun deviceCopyNeedsChoice(selection: List<AssetUiModel>): Boolean =
    selection.any { !it.isOnDevice() && it.copyRecipe() != null }

/** Taps the heart on every one, unless they are all favourites already: then it takes them all out. */
internal fun favoriteTarget(selection: Collection<AssetUiModel>): Boolean = !selection.all { it.isFavorite }

/** The type the receiving apps filter by; a mix of photos and videos can only say "anything". */
internal fun sharedMimeType(selection: List<AssetUiModel>): String = when {
    selection.all { it.isVideo } -> "video/*"
    selection.none { it.isVideo } -> "image/*"
    else -> "*/*"
}

/**
 * A name nobody in [taken] or in [directory] has yet. Two photos from different folders can both be
 * IMG_0001.JPG, and the second would replace the first in the share; on desktop the chosen folder may
 * already hold a file with that name, and it is not ours to overwrite.
 */
internal fun uniqueFileName(fileName: String, directory: File, taken: MutableSet<String>, fallback: String): String {
    val name = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { fallback }
    val stem = name.substringBeforeLast('.', name)
    val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var candidate = name
    var counter = 2
    while (candidate.lowercase() in taken || File(directory, candidate).exists()) {
        candidate = "$stem ($counter)$extension"
        counter++
    }
    taken += candidate.lowercase()
    return candidate
}

/** What the album place in the selection bar does, if anything. */
internal enum class AlbumSlot { NONE, ADD, REMOVE }

/**
 * Which album action takes the bar and which goes to "More". Inside a server album this person may
 * change, taking out is what is wanted there, and adding to another album moves to "More". A folder
 * of the device is never taken out of — a photo lives in one — only moved or copied to another.
 */
internal fun albumSlotFor(selection: List<AssetUiModel>, openAlbum: AlbumUiModel?): AlbumSlot = when {
    selection.isEmpty() -> AlbumSlot.NONE
    selection.any { it.isOnDevice() } && !selection.all { it.isOnDevice() } -> AlbumSlot.NONE
    selection.any { it.isOnDevice() } -> AlbumSlot.ADD
    openAlbum?.canEditContent == true && !openAlbum.isFolder -> AlbumSlot.REMOVE
    else -> AlbumSlot.ADD
}

/**
 * The bottom bar while photos are chosen: what can be done with all of them at once.
 *
 * Only what works for this selection shows. "More" holds deleting and the copies between the device
 * and a server.
 */
@Composable
internal fun SelectionActionsBar(
    selection: List<AssetUiModel>,
    work: UiText?,
    albumSlot: AlbumSlot,
    canSaveToDevice: Boolean,
    canSendToImmich: Boolean,
    canRotate: Boolean,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onCompose: () -> Unit,
    onAddToAlbum: () -> Unit,
    onRemoveFromAlbum: () -> Unit,
    onRequestDelete: () -> Unit,
    onSaveToDevice: () -> Unit,
    onSendToImmich: () -> Unit,
    onRotate: () -> Unit,
) {
    ImagoNavBarSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ImagoSizes.TouchTarget + ImagoSpacing.Xl)
                .padding(horizontal = ImagoSpacing.Sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (work != null) {
                // Halfway through, the buttons would start a second job over the first.
                CircularProgressIndicator(color = ImagoColors.Ivory, modifier = Modifier.size(ImagoSizes.IconDefault))
                Text(
                    work.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ImagoColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = ImagoSpacing.Md),
                )
                return@Row
            }
            val allFavorite = !favoriteTarget(selection)
            val cell = Modifier.weight(1f)
            SelectionAction(SelectionShareIcon, stringResource(SelectionShareLabel), cell, onShare)
            SelectionAction(
                if (allFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                stringResource(if (allFavorite) Res.string.library_selection_unfavorite else Res.string.library_selection_favorite),
                cell,
                onFavorite,
            )
            SelectionAction(Icons.Outlined.ViewCarousel, stringResource(Res.string.library_selection_compose), cell, onCompose)
            when (albumSlot) {
                AlbumSlot.ADD -> SelectionAction(
                    Icons.Outlined.PhotoAlbum, stringResource(Res.string.library_selection_album), cell, onAddToAlbum,
                )
                AlbumSlot.REMOVE -> SelectionAction(
                    Icons.Outlined.RemoveCircleOutline, stringResource(Res.string.library_selection_remove_from_album), cell, onRemoveFromAlbum,
                )
                AlbumSlot.NONE -> Unit
            }
            run {
                var menu by remember { mutableStateOf(false) }
                Box(cell) {
                    SelectionAction(Icons.Outlined.MoreHoriz, stringResource(Res.string.library_selection_more), Modifier.fillMaxWidth()) { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (albumSlot == AlbumSlot.REMOVE) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.library_selection_add_to_album)) },
                                leadingIcon = { Icon(Icons.Outlined.PhotoAlbum, contentDescription = null) },
                                onClick = { menu = false; onAddToAlbum() },
                            )
                        }
                        if (canRotate) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.library_selection_rotate)) },
                                leadingIcon = { Icon(Icons.Outlined.RotateRight, contentDescription = null) },
                                onClick = { menu = false; onRotate() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.library_selection_delete)) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; onRequestDelete() },
                        )
                        if (canSaveToDevice) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.library_selection_save_to_gallery)) },
                                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                onClick = { menu = false; onSaveToDevice() },
                            )
                        }
                        if (canSendToImmich) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.library_selection_send_to_immich)) },
                                leadingIcon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
                                onClick = { menu = false; onSendToImmich() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(vertical = ImagoSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ImagoColors.Ivory, modifier = Modifier.size(ImagoSizes.IconDefault))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** One confirmation for the whole selection, saying where the photos go and whether they come back. */
@Composable
internal fun SelectionDeleteDialog(selection: List<AssetUiModel>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val onDevice = selection.any { it.isOnDevice() }
    val onServer = selection.any { !it.isOnDevice() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(Res.plurals.library_selection_delete_question, selection.size, selection.size)) },
        text = {
            Text(
                listOfNotNull(
                    // As a media management app, Android no longer asks: saying it would is a promise of a dialog.
                    stringResource(
                        if (rememberMediaManagement()?.granted == true) Res.string.library_selection_delete_device_body_managed
                        else SelectionDeleteDeviceBody,
                    ).takeIf { onDevice },
                    stringResource(Res.string.library_selection_delete_server_body).takeIf { onServer },
                ).joinToString("\n\n"),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.library_selection_delete), color = ImagoColors.Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_cancel)) } },
    )
}

/** Originals or edits, asked once when a server photo in the selection has edits. */
@Composable
internal fun SelectionSaveWhichDialog(onChoose: (edited: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_selection_save_which_question)) },
        text = { Text(stringResource(Res.string.library_selection_save_which_body)) },
        confirmButton = {
            TextButton(onClick = { onChoose(true) }) { Text(stringResource(Res.string.library_selection_save_edited)) }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(false) }) { Text(stringResource(Res.string.library_selection_save_original)) }
        },
    )
}

/**
 * Turning a photo a quarter clockwise without opening the editor: its recipe turns, crop and all,
 * and a server's copy follows. The editor, which knows geometry, does it; the library and the detail
 * only ask. Null where nothing turns photos.
 */
interface PhotoRotation {
    /** The photo's recipe after turning it. */
    suspend fun rotateClockwise(assetId: String, checksum: String): eu.studio742.imago.core.model.EditRecipe
}
