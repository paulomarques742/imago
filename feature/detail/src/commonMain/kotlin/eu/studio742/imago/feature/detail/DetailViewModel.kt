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
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.changesTheImage
import java.io.File

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
    /**
     * This photo's local recipe, read again every time the screen opens.
     *
     * The one that came from the library was read when the page was paged; coming back from the
     * editor brings a more recent one, and that is what the preview has to show.
     */
    val recipe: EditRecipe? = null,
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val busyLabel: UiText? = null,
    val message: UiText? = null,
    val error: UiText? = null,
)

/**
 * @param shareDirectory where the original is downloaded for sharing — the app cache on Android, a
 *   temporary folder on desktop. It is cleared on every share.
 */
open class DetailViewModel(
    private val library: LibraryRepository,
    private val recipes: RecipeRepository,
    private val shareDirectory: File,
) : ViewModel() {
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
            runCatching { library.assetDetail(asset.id) }
                .onSuccess { detail ->
                    mutableState.update { current ->
                        if (current.assetId != asset.id) return@update current
                        current.copy(
                            exif = detail.exif,
                            description = detail.exif.description.orEmpty(),
                            isFavorite = detail.asset.isFavorite,
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
    fun requestDelete(assetId: String, confirm: () -> Unit) {
        viewModelScope.launch {
            runCatching { library.checkCanDelete(assetId) }
                .onSuccess { confirm() }
                .onFailure { error -> mutableState.update { it.copy(error = error.toUiText(Res.string.detail_delete_failed)) } }
        }
    }

    fun delete(assetId: String, onDeleted: () -> Unit) {
        mutableState.update { it.copy(isBusy = true, busyLabel = uiText(Res.string.detail_deleting)) }
        viewModelScope.launch {
            runCatching { library.deleteAsset(assetId) }
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

    fun consumeMessage() = mutableState.update { it.copy(message = null, error = null) }
}
