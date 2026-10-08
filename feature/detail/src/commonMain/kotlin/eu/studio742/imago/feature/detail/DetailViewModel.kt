package eu.studio742.imago.feature.detail

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.detail.resources.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.model.AssetExif
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.changesTheImage
import eu.studio742.imago.feature.editor.EditorAsset
import eu.studio742.imago.feature.editor.EditorExporter
import eu.studio742.imago.feature.editor.saveEditedToDevice
import java.io.File
import eu.studio742.imago.feature.library.toAssetUiModel
import eu.studio742.imago.feature.library.AssetUiModel

/** What the detail screen receives from the library — photo or video. */
data class DetailAsset(
    val id: String,
    val checksum: String,
    val fileName: String,
    val thumbnailUrl: String,
    val previewUrl: String,
    val apiKey: String,
    val fileCreatedAt: String,
    val date: String,
    val isFavorite: Boolean,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    /** The stream to play; null for a photo. */
    val videoUrl: String? = null,
    /**
     * The local recipe, when it changes anything.
     *
     * Immich serves the original: without it, opening an edited photo showed the version before the
     * edit, unlike the thumbnail that preceded it in the grid.
     */
    val recipe: EditRecipe? = null,
)

/** What saving to the device offers for the open asset. */
enum class DeviceCopy {
    /** Nothing: the asset is already on the device and has no edits. */
    NONE,
    ORIGINAL,
    EDITED,
    /** An edited photo from a server: the user chooses between the original and the edit. */
    ORIGINAL_OR_EDITED,
}

/**
 * [recipe] is the asset's local recipe, already filtered to the ones that change the image.
 *
 * An asset from the device (the gallery, or the folders on desktop) is already there as it is: only the
 * edit is new. A video never carries a recipe the copy could apply — the editor works on bitmaps.
 */
fun deviceCopyFor(asset: DetailAsset, recipe: EditRecipe?): DeviceCopy {
    val onDevice = runCatching { AssetReference.parse(asset.id).libraryId == DEVICE_LIBRARY_ID }.getOrDefault(false)
    val edited = recipe != null && !asset.isVideo
    return when {
        onDevice && edited -> DeviceCopy.EDITED
        onDevice -> DeviceCopy.NONE
        edited -> DeviceCopy.ORIGINAL_OR_EDITED
        else -> DeviceCopy.ORIGINAL
    }
}

data class DetailUiState(
    val assetId: String? = null,
    val exif: AssetExif = AssetExif(),
    /**
     * Immich's caption, read-only.
     *
     * The contract does not expose renaming — `UpdateAssetDto` has no name field in any of the three
     * validated versions — so the screen shows it as the title but does not let it be edited.
     */
    val description: String? = null,
    val isFavorite: Boolean = false,
    /** In the archive: out of the timeline, not deleted. */
    val isArchived: Boolean = false,
    /** The file's name as the library has it now; null until the detail arrives. */
    val fileName: String? = null,
    /** The folder the file is in, as the person knows it; null on a server. */
    val folder: String? = null,
    val people: List<DetailPerson> = emptyList(),
    /** Whether the file can take another name: the device's can, a server's original cannot. */
    val canRename: Boolean = false,
    /**
     * This photo's local recipe, read again every time the screen opens.
     *
     * The one that came from the library was read when the page was paged; coming back from the
     * editor brings a more recent one, and that is what the preview has to show.
     */
    val recipe: EditRecipe? = null,
    /**
     * The photos of the Immich stack this one is in, the cover first; empty when it is in none, or
     * the key cannot read stacks. It survives moving between the photos of the same stack.
     */
    val stack: List<AssetUiModel> = emptyList(),
    /** What Edit opens instead of the photo shown: the original an IMAGO export was made from. */
    val editInstead: AssetUiModel? = null,
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val busyLabel: UiText? = null,
    val message: UiText? = null,
    val error: UiText? = null,
)

/**
 * @param exporter where a copy saved "on this device" goes, and how an edit is rendered for it — the
 *   editor's own export.
 * @param shareDirectory where the original is downloaded for sharing — the app cache on Android, a
 *   temporary folder on desktop. It is cleared on every share.
 */
open class DetailViewModel(
    private val library: LibraryRepository,
    private val recipes: RecipeRepository,
    private val exporter: EditorExporter,
    private val shareDirectory: File,
    private val rotation: eu.studio742.imago.feature.library.PhotoRotation? = null,
) : ViewModel() {
    /** Whether photos turn here without the editor. */
    val canRotate: Boolean get() = rotation != null

    /**
     * A new name for the file, its extension kept. [onResult] gets the reason it was refused, or null;
     * [onRenamed] what the list around the detail has to know — on a computer the id is the path.
     */
    fun rename(asset: DetailAsset, name: String, onResult: (UiText?) -> Unit, onRenamed: (RenamedAsset) -> Unit) {
        viewModelScope.launch {
            val before = mutableState.value.fileName ?: asset.fileName
            runCatching { library.renameAsset(asset.id, name) }
                .onSuccess { id ->
                    val fileName = eu.studio742.imago.core.data.renamedFile(before, name) ?: before
                    mutableState.update { if (it.assetId == asset.id) it.copy(assetId = id, fileName = fileName) else it }
                    onRenamed(RenamedAsset(asset.id, id, fileName, library.thumbnailUrl(id), library.previewUrl(id)))
                    onResult(null)
                }
                .onFailure { error -> onResult(error.toUiText(Res.string.detail_rename_failed)) }
        }
    }

    /** Whether this library has an archive: a server's, or this device's own. */
    val canArchive: Boolean get() = library.canArchive

    /** Into the archive or back; either way the photo leaves the list it was opened from. */
    fun setArchived(assetId: String, archived: Boolean, onDone: () -> Unit) {
        if (mutableState.value.isBusy) return
        viewModelScope.launch {
            runCatching { library.setArchived(listOf(assetId), archived) }
                .onSuccess {
                    mutableState.update { if (it.assetId == assetId) it.copy(isArchived = archived) else it }
                    onDone()
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.toUiText(Res.string.detail_archive_failed)) } }
        }
    }
    private val mutableState = MutableStateFlow(DetailUiState())
    val state = mutableState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Loads the detail of the visible photo.
     *
     * The `isFavorite` that comes with the library's list may be stale — it was read when the page was
     * paged. What the server answers here is what rules.
     */
    fun open(asset: DetailAsset) {
        // Reopening the same photo — the way back from the editor — does not repeat the EXIF request,
        // which does not change, but repeats reading the recipe, which may have just changed.
        val sameAsset = mutableState.value.assetId == asset.id
        loadJob?.cancel()
        if (!sameAsset) {
            mutableState.value = DetailUiState(
                assetId = asset.id,
                isFavorite = asset.isFavorite,
                recipe = asset.recipe,
                isLoading = true,
                canRename = runCatching { library.canRename(asset.id) }.getOrDefault(false),
                // Moving to another photo of the same stack keeps the strip where it is.
                stack = mutableState.value.stack.takeIf { stack -> stack.any { it.id == asset.id } }.orEmpty(),
            )
        }
        loadJob = viewModelScope.launch {
            val recipe = runCatching { recipes.get(asset.id) }
                .getOrNull()
                ?.takeIf(EditRecipe::changesTheImage)
            mutableState.update { current ->
                if (current.assetId != asset.id) current else current.copy(recipe = recipe)
            }
            if (sameAsset) return@launch
            launch { loadStack(asset.id) }
            runCatching { library.assetDetail(asset.id) }
                .onSuccess { detail ->
                    mutableState.update { current ->
                        if (current.assetId != asset.id) return@update current
                        current.copy(
                            exif = detail.exif,
                            description = detail.exif.description.orEmpty(),
                            isFavorite = detail.asset.isFavorite,
                            isArchived = detail.asset.isArchived,
                            fileName = detail.asset.originalFileName.takeIf(String::isNotBlank),
                            folder = detail.folder,
                            people = detail.people.map { DetailPerson(it.id, it.name, library.personThumbnailUrl(it.id), library.apiKey(it.id)) },
                            isLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { current ->
                        if (current.assetId != asset.id) return@update current
                        // Without EXIF the screen stays useful: the photo, the date and the actions do
                        // not depend on it. The notice is discreet for that very reason.
                        current.copy(isLoading = false, error = error.toUiText(Res.string.detail_load_failed))
                    }
                }
        }
    }

    /**
     * The stack around [assetId], and what Edit should open. A key without `stack.read` has no
     * strip, and an export whose original cannot be read is edited as itself.
     */
    private suspend fun loadStack(assetId: String) {
        val known = mutableState.value.stack.takeIf { stack -> stack.any { it.id == assetId } }
        val stack = known ?: runCatching { library.stackMembers(assetId) }.getOrDefault(emptyList()).map { member ->
            member.toAssetUiModel(library, runCatching { recipes.get(member.id) }.getOrNull()?.takeIf(EditRecipe::changesTheImage))
        }
        val originalId = runCatching { library.exportOriginal(assetId) }.getOrNull()
        val original = originalId?.let { id ->
            stack.firstOrNull { it.id == id } ?: runCatching {
                library.assetDetail(id).asset.copy(id = id)
                    .toAssetUiModel(library, runCatching { recipes.get(id) }.getOrNull()?.takeIf(EditRecipe::changesTheImage))
            }.getOrNull()
        }
        mutableState.update { current ->
            if (current.assetId != assetId) current else current.copy(stack = stack, editInstead = original)
        }
    }

    fun toggleFavorite() = setFavorite(!mutableState.value.isFavorite)

    /**
     * The double tap on the photo: marks, never unmarks.
     *
     * A gesture made by mistake — and the double tap is made by mistake — cannot erase an earlier
     * choice. Unmarking is still the top button, where one has to aim.
     */
    fun favorite() {
        if (mutableState.value.isFavorite) return
        setFavorite(true)
    }

    private fun setFavorite(target: Boolean) {
        val assetId = mutableState.value.assetId ?: return
        mutableState.update { it.copy(isFavorite = target) }
        viewModelScope.launch {
            runCatching { library.setFavorite(assetId, target) }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isFavorite = !target,
                        error = error.toUiText(Res.string.detail_favorite_failed),
                    )
                }
            }
        }
    }

    /**
     * Opens the confirmation only if the library lets this asset be deleted. A key created without the
     * permission hears which one is missing instead of confirming something that will fail.
     */
    fun requestDelete(assetId: String, confirm: (counterpart: String?) -> Unit) {
        viewModelScope.launch {
            runCatching {
                // In the unified library a photo may be on both sides: the confirmation then asks where.
                val counterpart = library.counterpartOf(assetId)
                library.checkCanDelete(assetId)
                counterpart?.let { library.checkCanDelete(it) }
                counterpart
            }
                .onSuccess { confirm(it) }
                .onFailure { error -> mutableState.update { it.copy(error = error.toUiText(Res.string.detail_delete_failed)) } }
        }
    }

    fun delete(
        assetId: String,
        onDeleted: () -> Unit,
        counterpart: String? = null,
        where: eu.studio742.imago.feature.library.DeleteWhere = eu.studio742.imago.feature.library.DeleteWhere.BOTH,
    ) {
        mutableState.update { it.copy(isBusy = true, busyLabel = uiText(Res.string.detail_deleting)) }
        viewModelScope.launch {
            val ids = eu.studio742.imago.feature.library.idsToDelete(
                listOf(assetId), counterpart?.let { mapOf(assetId to it) }.orEmpty(), where,
            )
            runCatching { library.deleteAssets(ids) }
                .onSuccess {
                    mutableState.update { it.copy(isBusy = false, busyLabel = null) }
                    onDeleted()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isBusy = false,
                            busyLabel = null,
                            error = error.toUiText(Res.string.detail_delete_failed),
                        )
                    }
                }
        }
    }

    /**
     * Downloads the original and hands it with its content type to whoever shares it: the share menu
     * on Android, "Save copy" on desktop.
     *
     * The original is shared, not the edited version: exporting the recipe belongs to the editor and
     * has its own choices of resolution and destination.
     */
    fun share(asset: DetailAsset, onReady: (file: File, mimeType: String) -> Unit) {
        mutableState.update { it.copy(isBusy = true, busyLabel = uiText(Res.string.detail_preparing_share)) }
        viewModelScope.launch {
            runCatching {
                val directory = shareDirectory.apply {
                    // The folder is cleared on every share: nothing here deserves to outlive the Intent,
                    // and leaving originals in cache would pile up large files with no owner.
                    deleteRecursively()
                    mkdirs()
                }
                val file = File(directory, asset.fileName)
                library.downloadOriginal(asset.id, file)
                file
            }
                .onSuccess { file ->
                    mutableState.update { it.copy(isBusy = false, busyLabel = null) }
                    onReady(file, if (asset.isVideo) "video/*" else "image/*")
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isBusy = false,
                            busyLabel = null,
                            error = error.toUiText(Res.string.detail_share_failed),
                        )
                    }
                }
        }
    }

    /** The original as the library serves it — the only way a server video reaches the gallery. */
    fun saveOriginalToDevice(asset: DetailAsset) = saveToDevice(uiText(Res.string.detail_downloading)) {
        val file = File.createTempFile("download-", ".asset", shareDirectory.apply { mkdirs() })
        try {
            library.downloadOriginal(asset.id, file)
            exporter.saveOriginalToDevice(file, asset.fileName, asset.isVideo, asset.fileCreatedAt)
        } finally {
            file.delete()
        }
    }

    /** The photo with its recipe, at full resolution, exactly as the editor's "Save to gallery" makes it. */
    fun saveEditedToDevice(asset: DetailAsset) {
        val current = mutableState.value
        val recipe = (if (current.assetId == asset.id) current.recipe else asset.recipe) ?: return
        val target = EditorAsset(
            id = asset.id,
            checksum = asset.checksum,
            fileName = asset.fileName,
            previewUrl = asset.previewUrl,
            apiKey = asset.apiKey,
            fileCreatedAt = asset.fileCreatedAt,
        )
        saveToDevice(uiText(Res.string.detail_preparing_edit)) {
            exporter.saveEditedToDevice(target, recipe) { phase -> mutableState.update { it.copy(busyLabel = phase) } }
        }
    }

    private fun saveToDevice(label: UiText, block: suspend () -> UiText?) {
        if (mutableState.value.isBusy) return
        mutableState.update { it.copy(isBusy = true, busyLabel = label) }
        viewModelScope.launch {
            runCatching { block() }
                // A null message is the save dialog closed on desktop: nothing happened, nothing to say.
                .onSuccess { message -> mutableState.update { it.copy(isBusy = false, busyLabel = null, message = message) } }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isBusy = false, busyLabel = null, error = error.toUiText(Res.string.detail_save_failed))
                    }
                }
        }
    }

    /** A quarter turn clockwise; the preview, the grid and a server's thumbnails follow the recipe. */
    fun rotate(asset: DetailAsset) {
        val turn = rotation ?: return
        if (mutableState.value.isBusy) return
        viewModelScope.launch {
            runCatching { turn.rotateClockwise(asset.id, asset.checksum) }
                .onSuccess { rotated ->
                    mutableState.update { current ->
                        if (current.assetId != asset.id) current else current.copy(recipe = rotated.takeIf(EditRecipe::changesTheImage))
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(error = error.toUiText(Res.string.detail_rotate_failed)) } }
        }
    }

    /**
     * The photo as it is seen — with its edits, rendered at full resolution, when it has them — for
     * another app to use as a wallpaper or a contact's picture.
     */
    fun setAs(asset: DetailAsset, onReady: (file: File, mimeType: String) -> Unit) {
        if (mutableState.value.isBusy) return
        val current = mutableState.value
        val recipe = if (current.assetId == asset.id) current.recipe else asset.recipe
        mutableState.update { it.copy(isBusy = true, busyLabel = uiText(if (recipe != null) Res.string.detail_preparing_edit else Res.string.detail_preparing_share)) }
        viewModelScope.launch {
            runCatching {
                val directory = shareDirectory.apply { deleteRecursively(); mkdirs() }
                if (recipe != null) {
                    val target = EditorAsset(asset.id, asset.checksum, asset.fileName, asset.previewUrl, asset.apiKey, asset.fileCreatedAt)
                    val jpeg = exporter.renderJpeg(target, recipe) { phase -> mutableState.update { it.copy(busyLabel = phase) } }
                    File(directory, asset.fileName.substringBeforeLast('.') + ".jpg").also { jpeg.copyTo(it, overwrite = true); jpeg.delete() } to "image/jpeg"
                } else {
                    File(directory, asset.fileName.ifBlank { "IMAGO.jpg" }).also { library.downloadOriginal(asset.id, it) } to "image/*"
                }
            }
                .onSuccess { (file, type) ->
                    mutableState.update { it.copy(isBusy = false, busyLabel = null) }
                    onReady(file, type)
                }
                .onFailure { error ->
                    mutableState.update { it.copy(isBusy = false, busyLabel = null, error = error.toUiText(Res.string.detail_share_failed)) }
                }
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null, error = null) }

    /** A sentence from elsewhere on the screen — how adding to an album went. */
    fun show(message: UiText, failed: Boolean) =
        mutableState.update { if (failed) it.copy(error = message) else it.copy(message = message) }
}

/** Someone the server recognises in the photo, with the face it shows for them. */
data class DetailPerson(val id: String, val name: String, val thumbnailUrl: String, val apiKey: String)

/** A file that took another name: [oldId] was its id, [id] is now — the same on a phone. */
data class RenamedAsset(val oldId: String, val id: String, val fileName: String, val thumbnailUrl: String, val previewUrl: String)
