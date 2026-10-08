package eu.studio742.imago.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.model.AlbumPlace
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.data.DeviceLibrary
import eu.studio742.imago.core.model.AlbumAddition
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.FolderTransfer
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberUpdatedState
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.feature.library.resources.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How adding to an album went, as one sentence; [failed] says whether it reads as an error. */
internal data class AlbumOutcome(val message: UiText, val failed: Boolean)

/** [transfer] is how photos went into a folder album; null for a server's, where they are added. */
internal fun albumAdditionOutcome(result: AlbumAddition, albumName: String, transfer: FolderTransfer? = null): AlbumOutcome = when {
    result.failed > 0 -> AlbumOutcome(uiPlural(Res.plurals.library_album_add_failed, result.failed, result.failed, albumName), failed = true)
    result.added == 0 -> AlbumOutcome(uiText(Res.string.library_album_all_there, albumName), failed = false)
    transfer == FolderTransfer.MOVE ->
        AlbumOutcome(uiPlural(Res.plurals.library_album_moved, result.added, result.added, albumName), failed = false)
    transfer == FolderTransfer.COPY ->
        AlbumOutcome(uiPlural(Res.plurals.library_album_copied, result.added, result.added, albumName), failed = false)
    result.alreadyThere > 0 ->
        AlbumOutcome(uiPlural(Res.plurals.library_album_added_some_there, result.added, result.added, albumName), failed = false)
    else -> AlbumOutcome(uiPlural(Res.plurals.library_album_added, result.added, result.added, albumName), failed = false)
}

/**
 * The albums whose name has every word of [query], ignoring case and accents: "beach 2025" finds
 * "North Beach 2025", and "cafe" finds "Café". A blank query keeps them all.
 */
internal fun albumsMatching(albums: List<AlbumUiModel>, query: String): List<AlbumUiModel> {
    val words = query.folded().split(Regex("""\s+""")).filter(String::isNotEmpty)
    if (words.isEmpty()) return albums
    return albums.filter { album -> album.name.folded().let { name -> words.all(name::contains) } }
}

private fun String.folded(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase()

/** Blank names are refused by Immich; spaces around a name are never meant. */
internal fun albumNameOrNull(value: String): String? = value.trim().takeIf(String::isNotEmpty)

data class AlbumPickerState(
    val albums: List<ImmichAlbum> = emptyList(),
    val isLoading: Boolean = true,
    val error: UiText? = null,
    /** Adding or creating; the sheet does not take a second tap meanwhile. */
    val isWorking: Boolean = false,
)

/**
 * The albums a selection or a photo can go to, and the adding itself. Shared by the library's
 * selection and the detail, which is why it is a model of its own and not part of the library's.
 */
open class AlbumPickerViewModel(
    private val library: LibraryRepository,
    private val device: DeviceLibrary,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AlbumPickerState())
    val state: StateFlow<AlbumPickerState> = mutableState.asStateFlow()

    /** Only the ones this person may add to; the most recent first, as in the albums section. */
    fun load() {
        mutableState.value = AlbumPickerState(isLoading = true)
        viewModelScope.launch {
            runCatching { library.albums() }
                .onSuccess { albums ->
                    mutableState.value = AlbumPickerState(
                        albums = albums.filter { it.canEditContent }
                            .sortedWith(compareByDescending<ImmichAlbum> { it.endDate.orEmpty() }.thenBy { it.name.lowercase() }),
                        isLoading = false,
                    )
                }
                .onFailure { error -> mutableState.value = AlbumPickerState(isLoading = false, error = error.toUiText(Res.string.library_load_failed)) }
        }
    }

    internal fun add(album: ImmichAlbum, assetIds: List<String>, onFinished: (AlbumOutcome) -> Unit) = work(onFinished) {
        albumAdditionOutcome(library.addToAlbum(album.id, assetIds), album.name)
    }

    internal fun create(name: String, assetIds: List<String>, onFinished: (AlbumOutcome) -> Unit) = work(onFinished) {
        val album = library.createAlbum(name, assetIds)
        AlbumOutcome(uiPlural(Res.plurals.library_album_created, assetIds.size, album.name, assetIds.size), failed = false)
    }

    internal fun fileInto(album: ImmichAlbum, assetIds: List<String>, transfer: FolderTransfer, onFinished: (AlbumOutcome) -> Unit) =
        work(onFinished) { albumAdditionOutcome(library.fileIntoAlbum(album.id, assetIds, transfer), album.name, transfer) }

    /** Where a new folder album for [assetIds] can go; empty when there is no choice to make. */
    suspend fun places(assetIds: List<String>): List<AlbumPlace> = runCatching { library.albumPlaces(assetIds) }.getOrDefault(emptyList())

    internal fun createFolder(name: String, assetIds: List<String>, transfer: FolderTransfer, place: String?, onFinished: (AlbumOutcome) -> Unit) =
        work(onFinished) {
            val album = library.createFolderAlbum(name, assetIds, transfer, place)
            AlbumOutcome(uiPlural(Res.plurals.library_album_created, assetIds.size, album.name, assetIds.size), failed = false)
        }

    val rememberedTransfer: StateFlow<FolderTransfer?> get() = device.rememberedTransfer

    fun rememberTransfer(transfer: FolderTransfer?) = device.rememberTransfer(transfer)

    private fun work(onFinished: (AlbumOutcome) -> Unit, block: suspend () -> AlbumOutcome) {
        if (mutableState.value.isWorking) return
        mutableState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val outcome = runCatching { block() }
                .getOrElse { error -> AlbumOutcome(error.toUiText(Res.string.library_album_failed), failed = true) }
            mutableState.update { it.copy(isWorking = false) }
            onFinished(outcome)
        }
    }

    /** Thumbnails come from the library that holds the album, with its key. */
    fun thumbnailOf(album: ImmichAlbum): Pair<String, String>? =
        album.thumbnailAssetId?.let { library.thumbnailUrl(it) to library.apiKey(it) }
}

/**
 * "Add to album": the albums this person may add to, with "New album" first.
 *
 * [onFinished] gets how it went and closes the sheet; [onDismiss] is giving up, which changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumSheet(
    assetIds: List<String>,
    onFinished: (message: UiText, failed: Boolean) -> Unit,
    onDismiss: () -> Unit,
    viewModel: AlbumPickerViewModel = albumPickerViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.load() }
    val finish: (AlbumOutcome) -> Unit = { onFinished(it.message, it.failed) }
    // The device's albums are folders: what goes in is moved or copied, asked unless remembered.
    val onDevice = assetIds.any { runCatching { AssetReference.parse(it).libraryId == DEVICE_LIBRARY_ID }.getOrDefault(false) }
    val remembered by viewModel.rememberedTransfer.collectAsStateWithLifecycle()
    val chooseTransfer = rememberFolderTransfer(remembered, viewModel::rememberTransfer)
    ModalBottomSheet(
        onDismissRequest = { if (!state.isWorking) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ImagoColors.SurfaceElevated,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = ImagoSpacing.Lg)) {
            Text(
                pluralStringResource(Res.plurals.library_album_sheet_title, assetIds.size, assetIds.size),
                style = MaterialTheme.typography.titleMedium,
                color = ImagoColors.TextPrimary,
                modifier = Modifier.padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Md),
            )
            AlbumRow(
                title = stringResource(Res.string.library_album_new),
                subtitle = null,
                enabled = !state.isWorking,
                onClick = { naming = true },
            ) { Icon(Icons.Outlined.Add, contentDescription = null, tint = ImagoColors.Ivory) }
            when {
                state.isWorking || state.isLoading -> Box(Modifier.fillMaxWidth().padding(ImagoSpacing.Xl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ImagoColors.Ivory)
                }
                state.error != null -> Text(
                    state.error!!.resolve(),
                    color = ImagoColors.TextSecondary,
                    modifier = Modifier.padding(ImagoSpacing.Lg),
                )
                state.albums.isEmpty() -> Text(
                    stringResource(Res.string.library_album_none_editable),
                    color = ImagoColors.TextSecondary,
                    modifier = Modifier.padding(ImagoSpacing.Lg),
                )
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(state.albums, key = ImmichAlbum::id) { album ->
                        AlbumRow(
                            title = album.name,
                            subtitle = pluralStringResource(Res.plurals.library_items, album.assetCount, album.assetCount),
                            enabled = true,
                            onClick = {
                                if (album.isFolder) chooseTransfer { transfer -> viewModel.fileInto(album, assetIds, transfer, finish) }
                                else viewModel.add(album, assetIds, finish)
                            },
                        ) { AlbumThumbnail(viewModel.thumbnailOf(album)) }
                    }
                }
            }
        }
    }
    if (naming) {
        val places by produceState(emptyList<AlbumPlace>(), onDevice) { if (onDevice) value = viewModel.places(assetIds) }
        AlbumNameDialog(
            title = stringResource(Res.string.library_album_new),
            initial = "",
            confirmLabel = stringResource(Res.string.library_album_create),
            places = places,
            onConfirm = { name, place ->
                naming = false
                if (onDevice) chooseTransfer { transfer -> viewModel.createFolder(name, assetIds, transfer, place, finish) }
                else viewModel.create(name, assetIds, finish)
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun AlbumRow(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(ImagoRadii.Small)).background(ImagoColors.Background),
            contentAlignment = Alignment.Center,
        ) { leading() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ImagoColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary) }
        }
    }
}

@Composable
private fun AlbumThumbnail(source: Pair<String, String>?) {
    if (source == null) {
        Icon(Icons.Outlined.Collections, contentDescription = null, tint = ImagoColors.TextTertiary)
        return
    }
    val context = LocalPlatformContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(source.first).libraryAuth(source.second).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(48.dp),
    )
}

/**
 * Naming a new album or renaming one: the same field, a different button. With [places], a new
 * folder album also says where it goes, the suggested one already chosen.
 */
@Composable
internal fun AlbumNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (name: String, place: String?) -> Unit,
    onDismiss: () -> Unit,
    places: List<AlbumPlace> = emptyList(),
) {
    var value by remember { mutableStateOf(initial) }
    var chosen by remember(places) { mutableStateOf((places.firstOrNull { it.suggested } ?: places.firstOrNull())?.id) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = albumNameOrNull(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.library_album_name_label)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (places.size > 1) {
                    Text(
                        stringResource(Res.string.library_album_place_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = ImagoColors.TextSecondary,
                        modifier = Modifier.padding(top = ImagoSpacing.Md, bottom = ImagoSpacing.Xs),
                    )
                    places.forEach { place ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(ImagoRadii.Small))
                                .clickable(role = Role.RadioButton) { chosen = place.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == place.id, onClick = null, modifier = Modifier.padding(ImagoSpacing.Sm))
                            Column(Modifier.weight(1f)) {
                                Text(place.name, style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
                                Text(
                                    place.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ImagoColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { name?.let { onConfirm(it, chosen) } }, enabled = name != null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_cancel)) } },
    )
}

/**
 * Deleting an album says, before anything, what happens to the photos: a server's stay in the
 * library; a folder's go with it, to the device's trash.
 */
@Composable
internal fun AlbumDeleteDialog(album: AlbumUiModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_album_delete_question, album.name)) },
        text = {
            Text(if (album.isFolder) folderAlbumDeleteBody(album.assetCount) else stringResource(Res.string.library_album_delete_body))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.library_album_delete), color = ImagoColors.Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_cancel)) } },
    )
}

/**
 * How photos go into a folder album. Returns what to call with the action that needs the answer: it
 * runs at once when the person asked not to be asked again, and after the question otherwise.
 */
@Composable
internal fun rememberFolderTransfer(
    remembered: FolderTransfer?,
    onRemember: (FolderTransfer) -> Unit,
): ((FolderTransfer) -> Unit) -> Unit {
    var waiting by remember { mutableStateOf<((FolderTransfer) -> Unit)?>(null) }
    val current by rememberUpdatedState(remembered)
    waiting?.let { action ->
        FolderTransferDialog(
            onChoose = { transfer, always ->
                waiting = null
                if (always) onRemember(transfer)
                action(transfer)
            },
            onDismiss = { waiting = null },
        )
    }
    return remember { { action -> current?.let(action) ?: run { waiting = action } } }
}

@Composable
private fun FolderTransferDialog(onChoose: (FolderTransfer, always: Boolean) -> Unit, onDismiss: () -> Unit) {
    var always by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.library_folder_transfer_question)) },
        text = {
            Column {
                Text(stringResource(Res.string.library_folder_transfer_body))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ImagoSpacing.Md)
                        .clip(RoundedCornerShape(ImagoRadii.Small))
                        .clickable(role = Role.Checkbox) { always = !always },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = always, onCheckedChange = null)
                    Text(
                        stringResource(Res.string.library_folder_transfer_always),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = ImagoSpacing.Sm),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(FolderTransfer.MOVE, always) }) { Text(stringResource(Res.string.library_folder_transfer_move)) }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(FolderTransfer.COPY, always) }) { Text(stringResource(Res.string.library_folder_transfer_copy)) }
        },
    )
}

/** Where "Always do this" in the move-or-copy question is undone. */
@Composable
internal fun FolderTransferSetting(device: DeviceLibrary) {
    val transfer by device.rememberedTransfer.collectAsStateWithLifecycle()
    Text(stringResource(Res.string.library_folder_transfer_setting), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
    Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
        listOf(
            null to Res.string.library_folder_transfer_ask,
            FolderTransfer.MOVE to Res.string.library_folder_transfer_move,
            FolderTransfer.COPY to Res.string.library_folder_transfer_copy,
        ).forEach { (option, label) ->
            FilterChip(selected = transfer == option, onClick = { device.rememberTransfer(option) }, label = { Text(stringResource(label)) })
        }
    }
}
