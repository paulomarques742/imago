package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.FolderTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import java.io.File
import eu.studio742.imago.feature.library.resources.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.activeSource
import eu.studio742.imago.core.data.CatalogSyncState
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.render.changesTheImage
import java.time.LocalDate

data class AssetUiModel(
    val id: String,
    val checksum: String,
    val fileName: String,
    val date: String,
    val fileCreatedAt: String,
    val width: Long?,
    val height: Long?,
    val isFavorite: Boolean,
    val isEdited: Boolean,
    val hasLocalRecipe: Boolean,
    val thumbnailUrl: String,
    val previewUrl: String,
    val apiKey: String,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    /** The stream URL, only for videos; for photos there is nothing to play. */
    val videoUrl: String? = null,
    /**
     * The local recipe, when it changes anything.
     *
     * It travels with the photo from the grid because the thumbnail and the preview come out of it —
     * Immich always serves the original, and without this the library showed a photo that is no
     * longer the one the user left in the editor.
     */
    val recipe: EditRecipe? = null,
    /** In the unified library: also on the server, or only there. The tile shows a small cloud. */
    val isOnServer: Boolean = false,
)

data class AlbumUiModel(
    val id: String,
    val name: String,
    val description: String,
    val thumbnailUrl: String?,
    val assetCount: Int,
    val startDate: String?,
    val endDate: String?,
    val shared: Boolean,
    val apiKey: String,
    /** Photos can be added and taken out here. */
    val canEditContent: Boolean = false,
    /** Renaming and deleting are the owner's. */
    val isOwned: Boolean = false,
    /** A folder of the device: photos are moved or copied into it, never taken out. */
    val isFolder: Boolean = false,
    /** Not an album: a person the server recognises, open with the photos they are in. */
    val isPerson: Boolean = false,
)

/** Someone the server recognises; [name] is empty while nobody named them. */
data class PersonUiModel(val id: String, val name: String, val thumbnailUrl: String, val apiKey: String)

/**
 * A photo of [library] as the screens show it: the grid, the detail, the editor's strip. [recipe] is
 * its local recipe, when it changes the image. The ids are the routed library's references.
 */
fun ImmichAsset.toAssetUiModel(library: LibraryRepository, recipe: EditRecipe?): AssetUiModel {
    val isVideo = type == AssetType.VIDEO
    return AssetUiModel(
        id = id,
        checksum = checksum,
        fileName = originalFileName,
        date = localDateTime.ifBlank { fileCreatedAt },
        fileCreatedAt = fileCreatedAt,
        width = width,
        height = height,
        isFavorite = isFavorite,
        isEdited = isEdited,
        hasLocalRecipe = hasLocalRecipe,
        thumbnailUrl = library.thumbnailUrl(id),
        previewUrl = library.previewUrl(id),
        apiKey = library.apiKey(id),
        isVideo = isVideo,
        durationMs = durationMs,
        videoUrl = if (isVideo) library.videoPlaybackUrl(id) else null,
        recipe = recipe,
        isOnServer = isOnServer,
    )
}

data class MonthUiModel(val value: String, val assetCount: Int)

enum class LibrarySection { TIMELINE, ALBUMS }

/** What the search box looks for in a server's library: what is in the photo, or the file's name. */
enum class SearchMode { CONTENT, FILE_NAME }

/**
 * The album the grid asks the library for. One left open from another library has nothing to show
 * here; the unified library holds the phone's albums and the server's, each with its own reference.
 */
internal fun gridAlbumId(album: AlbumUiModel, selectedLibraryId: String): String? {
    val libraryId = eu.studio742.imago.core.model.AssetReference.parse(album.id).libraryId
    return album.id.takeIf { selectedLibraryId == eu.studio742.imago.core.model.UNIFIED_LIBRARY_ID || libraryId == selectedLibraryId }
}

data class LibraryUiState(
    val section: LibrarySection = LibrarySection.TIMELINE,
    val selectedMonth: String? = null,
    val selectedAlbum: AlbumUiModel? = null,
    val albums: List<AlbumUiModel> = emptyList(),
    val months: List<MonthUiModel> = emptyList(),
    val isLoadingNavigation: Boolean = false,
    val navigationError: UiText? = null,
    val query: String = "",
    val isSearching: Boolean = false,
    val searchMode: SearchMode = SearchMode.CONTENT,
    /** The open library searches by content: a server with it on. Until it says, by name. */
    val contentSearchAvailable: Boolean = false,
    val actionError: UiText? = null,
    /**
     * The date the grid has to jump to as soon as it has content.
     *
     * Filtering by month is immediate, but the position within it is only known once the page
     * arrives — so the request stays here until the UI can carry it out.
     */
    val pendingScrollDate: LocalDate? = null,
    /**
     * The photos chosen with the long press, in the order they were chosen.
     *
     * They are kept whole, and not just their ids, because the selection survives scrolling: what was
     * chosen at the start of last year has already left the paging window when the button is pressed,
     * and without the model at hand there was no way to send it to the composition.
     */
    val selection: Map<String, AssetUiModel> = emptyMap(),
    /** What is being done with the selection right now, with its progress; null when nothing is. */
    val selectionWork: UiText? = null,
    /** A sentence to show once that is not an error: how many copies were saved. */
    val actionMessage: UiText? = null,
    /**
     * Raised when an album changed under the open grid. An album is read straight from the server,
     * without the catalogue in between, so nothing else would tell the grid to read it again.
     */
    val gridRevision: Int = 0,
    /** The open library knows who is in its photos: a server does. */
    val hasPeople: Boolean = false,
    /** The people chip is on: the grid gives way to the faces. */
    val showingPeople: Boolean = false,
    val people: List<PersonUiModel> = emptyList(),
    val isLoadingPeople: Boolean = false,
    val peopleError: UiText? = null,
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
open class LibraryViewModel(
    private val library: LibraryRepository,
    private val recipes: RecipeRepository,
    private val configuration: ConfigurationRepository,
    private val device: eu.studio742.imago.core.data.DeviceLibrary,
    private val deviceCopies: DeviceCopies? = null,
    private val rotation: PhotoRotation? = null,
) : ViewModel() {
    val selectedSource = configuration.selectedLibraryId
    val filter = MutableStateFlow(LibraryFilter.ALL)
    val uiState = MutableStateFlow(LibraryUiState())

    /**
     * What slices the grid.
     *
     * The search comes in with a delay: every key would change the paging key and force a trip to the
     * server per character typed.
     */
    private data class AssetQuery(
        val filter: LibraryFilter,
        val month: String?,
        val album: AlbumUiModel?,
        val query: String,
        val source: eu.studio742.imago.core.model.LibrarySource,
        val byContent: Boolean,
    )

    val assets: Flow<PagingData<AssetUiModel>> = combine(
        combine(filter, configuration.activeSource()) { filter, source -> filter to source },
        uiState.map { it.selectedMonth },
        uiState.map { it.selectedAlbum },
        uiState.map { it.query.trim() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0 else SEARCH_DEBOUNCE_MS },
        uiState.map { it.contentSearchAvailable && it.searchMode == SearchMode.CONTENT }.distinctUntilChanged(),
    ) { activeFilter, month, album, query, byContent ->
        AssetQuery(activeFilter.first, month, album, query, activeFilter.second, byContent)
    }
        .distinctUntilChanged()
        .flatMapLatest { (activeFilter, month, album, query, _, byContent) ->
            val albumId = album?.let { gridAlbumId(it, configuration.selectedLibraryId.value) }
            if (album?.isPerson == true) {
                library.personAssets(album.id, activeFilter)
            } else if (byContent && query.isNotEmpty()) {
                library.searchByContent(query, activeFilter, month.takeIf { album == null }, albumId)
            } else {
                library.assets(
                    filter = activeFilter,
                    month = month.takeIf { album == null },
                    albumId = albumId,
                    query = query.takeIf(String::isNotEmpty),
                )
            }
        }
        .map { pagingData -> pagingData.map { asset -> asset.toAssetUiModel(library, asset.localRecipe()) } }
        .cachedIn(viewModelScope)

    /** How the timeline sync is going, so the bar can say. */
    val catalogSync: StateFlow<CatalogSyncState> = library.catalogSync

    private var navigationJob: kotlinx.coroutines.Job? = null
    private val navigationStates = mutableMapOf<String, Pair<LibraryFilter, LibraryUiState>>()
    init {
        viewModelScope.launch {
            var previous: String? = null
            configuration.activeSource().distinctUntilChanged().collect { source ->
                val id = source.id
                previous?.let { navigationStates[it] = filter.value to uiState.value.copy(selection = emptyMap(), isLoadingNavigation = false) }
                navigationJob?.cancel()
                val restored = navigationStates[id]
                filter.value = restored?.first ?: LibraryFilter.ALL
                uiState.value = restored?.second ?: LibraryUiState()
                previous = id
                uiState.update { it.copy(hasPeople = library.hasPeople) }
                if (uiState.value.showingPeople) loadPeople()
                refreshNavigation()
                syncCatalog()
                // A server says whether it searches by content; the phone and the folders do not.
                launch {
                    val available = runCatching { library.contentSearchAvailable() }.getOrDefault(false)
                    if (configuration.selectedLibraryId.value == id) uiState.update { it.copy(contentSearchAvailable = available) }
                }
            }
        }
        viewModelScope.launch {
            device.accessRevision.collect {
                if (configuration.selectedLibraryId.value == eu.studio742.imago.core.model.DEVICE_LIBRARY_ID) syncCatalog()
            }
        }
    }

    /**
     * Brings the whole timeline into the local catalogue, in the background.
     *
     * Metadata only, and only the months that changed since last time — so the second time costs one
     * request. It is what gives the grid the library's true size, and fast scrolling somewhere to
     * land in any year.
     */
    fun syncCatalog() {
        viewModelScope.launch {
            runCatching { library.syncCatalog() }.onFailure { error ->
                if (error !is kotlinx.coroutines.CancellationException) uiState.update { it.copy(actionError = error.toUiText(Res.string.library_refresh_failed)) }
            }
            refreshNavigation()
        }
    }

    /**
     * The index of a photo in this view, for the grid to come back centred on it.
     *
     * In an album it returns null: that list has another source and another order, and the catalogue
     * count would point to the position the photo has in the timeline, not in the album.
     */
    suspend fun indexOfAsset(assetId: String): Int? {
        if (uiState.value.selectedAlbum != null) return null
        return runCatching {
            library.indexOfAsset(
                assetId = assetId,
                filter = filter.value,
                month = uiState.value.selectedMonth,
                query = uiState.value.query.trim().takeIf(String::isNotEmpty),
            )
        }.getOrNull()
    }

    /** The index of the first photo of this date or earlier, in this view. */
    suspend fun indexOfDate(date: LocalDate): Int? = runCatching {
        library.indexOfDate(
            date = date,
            filter = filter.value,
            month = uiState.value.selectedMonth,
            query = uiState.value.query.trim().takeIf(String::isNotEmpty),
        )
    }.getOrNull()

    /** Fetches the month of a date the catalogue does not have yet. */
    fun loadMonthFor(date: LocalDate) {
        viewModelScope.launch {
            runCatching { library.loadMonth(date.withDayOfMonth(1).toString()) }
        }
    }

    /**
     * The recipe to apply to this photo's thumbnail, if there is one that changes it.
     *
     * The catalogue flag avoids a trip to the database per tile; a neutral recipe is discarded because
     * processing it cost the same and returned the photo as it is.
     */
    private suspend fun ImmichAsset.localRecipe(): EditRecipe? {
        if (!hasLocalRecipe) return null
        return runCatching { recipes.get(id) }.getOrNull()?.takeIf(EditRecipe::changesTheImage)
    }

    /**
     * Adds this photo to the selection or takes it out.
     *
     * The long press is what opens the selection and taking the last one out is what closes it: a
     * selection mode with nothing selected has nothing to act on, and would stay covering the library
     * bar waiting for a tap nobody has a reason to make any more.
     */
    fun toggleSelection(asset: AssetUiModel) = uiState.update { state ->
        val selection = state.selection.toMutableMap()
        if (selection.remove(asset.id) == null) selection[asset.id] = asset
        state.copy(selection = selection)
    }

    fun clearSelection() = uiState.update { it.copy(selection = emptyMap()) }

    fun selectFilter(value: LibraryFilter) {
        filter.value = value
        // A chip is a view of the whole library; leaving the previous month applied underneath would
        // give an empty list with nothing on screen explaining why.
        uiState.update { it.copy(selectedMonth = null, selectedAlbum = null, showingPeople = false) }
    }

    /** The people chip: the faces in place of the grid, read again each time it is chosen. */
    fun showPeople() {
        filter.value = LibraryFilter.ALL
        uiState.update { it.copy(showingPeople = true, selectedMonth = null, selectedAlbum = null) }
        loadPeople()
    }

    private var peopleJob: kotlinx.coroutines.Job? = null

    fun loadPeople() {
        val sourceId = configuration.selectedLibraryId.value
        peopleJob?.cancel()
        uiState.update { it.copy(isLoadingPeople = true, peopleError = null) }
        peopleJob = viewModelScope.launch {
            val result = runCatching {
                library.people().map { PersonUiModel(it.id, it.name, library.personThumbnailUrl(it.id), library.apiKey(it.id)) }
            }
            if (sourceId != configuration.selectedLibraryId.value) return@launch
            uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(people = it, isLoadingPeople = false) },
                    onFailure = { state.copy(peopleError = it.toUiText(Res.string.library_load_failed), isLoadingPeople = false) },
                )
            }
        }
    }

    /** A person opens like an album: their photos, with the way back to the faces. */
    fun openPerson(person: PersonUiModel) = uiState.update {
        it.copy(
            selectedAlbum = AlbumUiModel(
                id = person.id, name = person.name, description = "", thumbnailUrl = person.thumbnailUrl, assetCount = 0,
                startDate = null, endDate = null, shared = false, apiKey = person.apiKey, isPerson = true,
            ),
            isSearching = false,
            query = "",
        )
    }

    fun updateQuery(value: String) = uiState.update { it.copy(query = value) }

    fun openSearch() = uiState.update { it.copy(isSearching = true) }

    fun selectSearchMode(mode: SearchMode) = uiState.update { it.copy(searchMode = mode) }

    fun closeSearch() = uiState.update { it.copy(isSearching = false, query = "") }

    fun consumeActionError() = uiState.update { it.copy(actionError = null, actionMessage = null) }

    private val chosen: List<AssetUiModel> get() = uiState.value.selection.values.toList()

    /**
     * Runs one job over the selection, with the bar showing [label] meanwhile.
     *
     * The selection is dropped when the job ends well: what was chosen has been dealt with. When it
     * fails it stays, so the person can try again without choosing everything again. A system
     * confirmation the person refused is not a failure, and says nothing.
     */
    private fun runOnSelection(
        label: UiText,
        failure: StringResource,
        needsSelection: Boolean = true,
        block: suspend () -> UiText?,
    ) {
        if (uiState.value.selectionWork != null || (needsSelection && uiState.value.selection.isEmpty())) return
        uiState.update { it.copy(selectionWork = label) }
        viewModelScope.launch {
            try {
                val message = block()
                uiState.update { it.copy(selectionWork = null, selection = emptyMap(), actionMessage = message) }
            } catch (cancelled: CancellationException) {
                uiState.update { it.copy(selectionWork = null) }
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (error: Exception) {
                uiState.update { it.copy(selectionWork = null, actionError = error.toUiText(failure)) }
            }
        }
    }

    private fun progress(label: PluralStringResource, done: Int, total: Int) =
        uiState.update { it.copy(selectionWork = uiPlural(label, total, done, total)) }

    fun favoriteSelection() {
        val selection = chosen
        val target = favoriteTarget(selection)
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_favorite_failed) {
            library.setFavorites(selection.map { it.id }, target)
            null
        }
    }

    /**
     * Opens the confirmation only if every library in the selection lets its photos be deleted: a key
     * without the permission hears which one is missing before confirming something that will fail.
     */
    /**
     * [confirm] gets the copies on the other side of the photos that are on both — only in the
     * unified library; empty everywhere else — so the confirmation can ask where they go from.
     */
    fun requestDeleteSelection(confirm: (counterparts: Map<String, String>) -> Unit) {
        val selection = chosen
        viewModelScope.launch {
            runCatching {
                val counterparts = selection.mapNotNull { asset -> library.counterpartOf(asset.id)?.let { asset.id to it } }.toMap()
                (selection.map { it.id } + counterparts.values).groupBy { AssetReference.parse(it).libraryId }
                    .values.forEach { library.checkCanDelete(it.first()) }
                counterparts
            }
                .onSuccess { confirm(it) }
                .onFailure { error -> uiState.update { it.copy(actionError = error.toUiText(Res.string.library_selection_delete_failed)) } }
        }
    }

    fun deleteSelection(counterparts: Map<String, String> = emptyMap(), where: DeleteWhere = DeleteWhere.BOTH) {
        val selection = chosen
        runOnSelection(uiText(Res.string.library_selection_deleting), Res.string.library_selection_delete_failed) {
            library.deleteAssets(idsToDelete(selection.map { it.id }, counterparts, where))
            null
        }
    }

    /** The originals, downloaded to where [share] says and handed to it. Edits are the editor's export. */
    fun shareSelection(share: SelectionShare) {
        val selection = chosen
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_selection_share_failed) {
            val directory = share.prepare() ?: return@runOnSelection null
            val taken = mutableSetOf<String>()
            val files = selection.mapIndexed { index, asset ->
                progress(Res.plurals.library_selection_preparing, index + 1, selection.size)
                val name = withContext(Dispatchers.IO) { uniqueFileName(asset.fileName, directory, taken, "IMAGO-${index + 1}") }
                File(directory, name).also { library.downloadOriginal(asset.id, it) }
            }
            share.deliver(files, sharedMimeType(selection))
        }
    }

    /** Whether "Save to gallery" has anything to save in this selection. */
    fun canSaveSelectionToDevice(selection: List<AssetUiModel>): Boolean =
        deviceCopies != null && deviceCopyPlan(selection, edited = true).isNotEmpty()

    /**
     * One copy per photo, counted at the end. A photo that fails does not stop the others: a server
     * that lost one original should not cost the whole selection.
     */
    fun saveSelectionToDevice(edited: Boolean) {
        val copies = deviceCopies ?: return
        val plan = deviceCopyPlan(chosen, edited)
        if (plan.isEmpty()) return
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_selection_save_failed) {
            var failed = 0
            plan.forEachIndexed { index, (asset, kind) ->
                progress(Res.plurals.library_selection_saving, index + 1, plan.size)
                try {
                    when (kind) {
                        CopyKind.ORIGINAL -> copies.saveOriginal(asset)
                        CopyKind.EDITED -> copies.saveEdited(asset, checkNotNull(asset.recipe))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed++
                }
            }
            val saved = plan.size - failed
            check(saved > 0) { "No copy could be saved" }
            if (failed == 0) uiPlural(Res.plurals.library_selection_saved, saved, saved)
            else uiPlural(Res.plurals.library_selection_saved_some, failed, saved, failed)
        }
    }

    /** Whether the selection has photos to turn here; videos never turn. */
    fun canRotateSelection(selection: List<AssetUiModel>): Boolean = rotation != null && selection.any { !it.isVideo }

    /**
     * A quarter turn clockwise for every photo chosen. The selection stays: turning twice is common,
     * and choosing them all again for it would not be.
     */
    fun rotateSelection() {
        val turn = rotation ?: return
        val photos = chosen.filterNot { it.isVideo }
        if (photos.isEmpty() || uiState.value.selectionWork != null) return
        uiState.update { it.copy(selectionWork = uiText(Res.string.library_selection_working)) }
        viewModelScope.launch {
            var failure: Throwable? = null
            photos.forEach { asset ->
                runCatching { turn.rotateClockwise(asset.id, asset.checksum) }.onFailure { failure = it }
            }
            uiState.update {
                it.copy(
                    selectionWork = null,
                    // An album is read from the server, not the catalogue: it has to be told.
                    gridRevision = it.gridRevision + 1,
                    actionError = failure?.toUiText(Res.string.library_selection_rotate_failed),
                )
            }
        }
    }

    /** The photos of this device in the selection, by their id in its library, for the upload to Immich. */
    fun deviceAssetsIn(selection: List<AssetUiModel>): List<String> =
        selection.filter { it.isOnDevice() }.map { AssetReference.parse(it.id).localId }

    /** Only with photos of this device and a server to send them to. */
    fun canSendToImmich(selection: List<AssetUiModel>): Boolean =
        selection.any { it.isOnDevice() } && configuration.libraries.value.any { !it.isDevice && it.isConnected }

    /** The upload takes the selection over; here it is done. */
    fun selectionHandedOver() = clearSelection()

    /** How photos of the device go into its albums when the person stopped being asked; null asks. */
    val rememberedTransfer: StateFlow<FolderTransfer?> get() = device.rememberedTransfer

    fun rememberTransfer(transfer: FolderTransfer?) = device.rememberTransfer(transfer)

    /** Whether the open library has a trash to show — a server's, the phone's; not the computer's folders. */
    val hasTrash: Boolean get() = library.hasTrash

    /** Whether the open library is this device's. */
    val isDeviceLibrary: Boolean get() = configuration.selectedLibraryId.value == eu.studio742.imago.core.model.DEVICE_LIBRARY_ID

    /** How adding the selection to an album went: done, it stops being chosen; failed, it stays. */
    fun selectionAddedToAlbum(message: UiText, failed: Boolean) {
        uiState.update {
            if (failed) it.copy(actionError = message) else it.copy(actionMessage = message, selection = emptyMap())
        }
        // The counts and the cover in the albums section changed.
        refreshNavigation()
    }

    /** Out of the open album, not out of the library. */
    fun removeSelectionFromAlbum() {
        val album = uiState.value.selectedAlbum ?: return
        val selection = chosen
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_album_failed) {
            val removed = library.removeFromAlbum(album.id, selection.map { it.id })
            albumChanged(album.copy(assetCount = (album.assetCount - removed).coerceAtLeast(0)))
            uiPlural(Res.plurals.library_album_removed, removed, removed, album.name)
        }
    }

    /** What was chosen in the picker opened from inside [album]; [transfer] for a folder album. */
    fun addToAlbum(album: AlbumUiModel, assets: List<AssetUiModel>, transfer: FolderTransfer? = null) {
        if (assets.isEmpty()) return
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_album_failed, needsSelection = false) {
            val ids = assets.map { it.id }
            val result = if (transfer != null) library.fileIntoAlbum(album.id, ids, transfer) else library.addToAlbum(album.id, ids)
            albumChanged(album.copy(assetCount = album.assetCount + result.added))
            albumAdditionOutcome(result, album.name, transfer).message
        }
    }

    /** Where a new album of the open library can go; only this device's folders give a choice. */
    suspend fun newAlbumPlaces(): List<eu.studio742.imago.core.model.AlbumPlace> =
        if (isDeviceLibrary) runCatching { library.albumPlaces(emptyList()) }.getOrDefault(emptyList()) else emptyList()

    /**
     * A new album with what was chosen, opened straight away; [transfer] makes it a folder of the
     * device, in [place] when there was one to choose.
     */
    fun createAlbum(name: String, assets: List<AssetUiModel>, transfer: FolderTransfer? = null, place: String? = null) {
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_album_failed, needsSelection = false) {
            val ids = assets.map { it.id }
            val created = if (transfer != null) library.createFolderAlbum(name, ids, transfer, place) else library.createAlbum(name, ids)
            val album = created.toUiModel()
            uiState.update { it.copy(section = LibrarySection.ALBUMS, selectedAlbum = album, selectedMonth = null) }
            albumChanged(album)
            uiPlural(Res.plurals.library_album_created, assets.size, album.name, assets.size)
        }
    }

    fun renameOpenAlbum(name: String) {
        val album = uiState.value.selectedAlbum ?: return
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_album_failed, needsSelection = false) {
            // A folder's id is where it is, and changes with the name.
            val id = library.renameAlbum(album.id, name)
            uiState.update { it.copy(selectedAlbum = album.copy(id = id, name = name)) }
            refreshNavigation()
            null
        }
    }

    /** The album goes and the grid goes back to the albums; the photos stay in the library. */
    fun deleteOpenAlbum() {
        val album = uiState.value.selectedAlbum ?: return
        runOnSelection(uiText(Res.string.library_selection_working), Res.string.library_album_failed, needsSelection = false) {
            library.deleteAlbum(album.id)
            uiState.update { it.copy(selectedAlbum = null, albums = it.albums.filterNot { other -> other.id == album.id }) }
            refreshNavigation()
            null
        }
    }

    private fun albumChanged(album: AlbumUiModel) {
        uiState.update {
            it.copy(
                selectedAlbum = album.takeIf { _ -> it.selectedAlbum?.id == album.id } ?: it.selectedAlbum,
                gridRevision = it.gridRevision + 1,
            )
        }
        refreshNavigation()
    }

    /**
     * Toggles the favourite.
     *
     * `AssetDao` is updated inside the repository and paging re-emits on its own, so there is no
     * optimistic state to keep here — what there is, is the error to show if Immich refuses.
     */
    fun toggleFavorite(asset: AssetUiModel) {
        viewModelScope.launch {
            runCatching { library.setFavorite(asset.id, !asset.isFavorite) }
                .onFailure { error ->
                    uiState.update {
                        it.copy(actionError = error.toUiText(Res.string.library_favorite_failed))
                    }
                }
        }
    }

    fun selectSection(section: LibrarySection) {
        uiState.update {
            // The timeline searches file names and the albums search album names: a query does
            // not carry from one to the other.
            it.copy(
                section = section,
                selectedAlbum = null,
                navigationError = null,
                isSearching = if (section == it.section) it.isSearching else false,
                query = if (section == it.section) it.query else "",
            )
        }
        if (
            (section == LibrarySection.ALBUMS && uiState.value.albums.isEmpty()) ||
            (section == LibrarySection.TIMELINE && uiState.value.months.isEmpty())
        ) {
            refreshNavigation()
        }
    }

    fun selectMonth(month: String?) = uiState.update {
        it.copy(
            section = LibrarySection.TIMELINE,
            selectedMonth = month,
            selectedAlbum = null,
            pendingScrollDate = null,
        )
    }

    /**
     * Jumps to a date.
     *
     * The month is the slice Immich knows how to make; within it, the UI scrolls to the first photo
     * of that date or earlier. A date without photos lands on the closest one before it, which is what
     * is expected from a jump in a descending timeline.
     */
    fun jumpToDate(date: LocalDate) {
        filter.value = LibraryFilter.ALL
        uiState.update {
            it.copy(
                section = LibrarySection.TIMELINE,
                // The month is no longer a slice. With the whole timeline in the catalogue, jumping to
                // a date is scrolling to its index — and the grid stays the whole library, instead of
                // being stuck in a month with a chip explaining it.
                selectedMonth = null,
                selectedAlbum = null,
                isSearching = false,
                query = "",
                pendingScrollDate = date,
            )
        }
    }

    fun consumePendingScroll() = uiState.update { it.copy(pendingScrollDate = null) }

    /**
     * The search that found the album ends here: it was by album name, and kept open it would go on
     * slicing the photos inside by file name — the album "Beach" searched as "beach" opened empty.
     */
    fun openAlbum(album: AlbumUiModel) = uiState.update {
        it.copy(section = LibrarySection.ALBUMS, selectedAlbum = album, selectedMonth = null, isSearching = false, query = "")
    }

    fun closeAlbum() = uiState.update { it.copy(selectedAlbum = null) }

    fun refreshNavigation() {
        if (uiState.value.isLoadingNavigation) return
        uiState.update { it.copy(isLoadingNavigation = true, navigationError = null) }
        val sourceId = configuration.selectedLibraryId.value
        navigationJob = viewModelScope.launch {
            val albums = runCatching { library.albums() }
            val months = runCatching { library.timeBuckets() }
            if (sourceId != configuration.selectedLibraryId.value) return@launch
            val refreshed = albums.getOrNull()?.map { it.toUiModel() }?.sortedWith(
                compareByDescending<AlbumUiModel> { it.endDate.orEmpty() }.thenBy { it.name.lowercase() },
            )
            uiState.update { current ->
                current.copy(
                    albums = refreshed ?: current.albums,
                    // The open album follows the list: photos moved out of it from the selection left
                    // its header counting them. One no longer listed has nothing left in it.
                    selectedAlbum = current.selectedAlbum?.let { open ->
                        if (refreshed == null || open.isPerson) open
                        else refreshed.firstOrNull { it.id == open.id } ?: open.copy(assetCount = 0)
                    },
                    months = months.getOrNull()?.map { MonthUiModel(it.month, it.assetCount) } ?: current.months,
                    isLoadingNavigation = false,
                    // Albums first: a key without album.read has the timeline, and the albums section is
                    // where it has to say which permission is missing.
                    navigationError = (albums.exceptionOrNull() ?: months.exceptionOrNull())?.toUiText(Res.string.library_load_failed),
                )
            }
        }
    }

    fun disconnect() { viewModelScope.launch { configuration.clear() } }

    private companion object { const val SEARCH_DEBOUNCE_MS = 350L }

    private fun ImmichAlbum.toUiModel() = AlbumUiModel(
        id = id,
        name = name,
        description = description,
        thumbnailUrl = thumbnailAssetId?.let(library::thumbnailUrl),
        assetCount = assetCount,
        startDate = startDate,
        endDate = endDate,
        shared = shared,
        apiKey = thumbnailAssetId?.let(library::apiKey).orEmpty(),
        canEditContent = canEditContent,
        isOwned = isOwned,
        isFolder = isFolder,
    )
}
