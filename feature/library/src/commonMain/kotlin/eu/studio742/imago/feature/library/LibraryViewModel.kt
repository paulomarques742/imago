package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
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
)

data class MonthUiModel(val value: String, val assetCount: Int)

enum class LibrarySection { TIMELINE, ALBUMS }

data class LibraryUiState(
    val section: LibrarySection = LibrarySection.TIMELINE,
    val selectedMonth: String? = null,
    val selectedAlbum: AlbumUiModel? = null,
    val albums: List<AlbumUiModel> = emptyList(),
    val months: List<MonthUiModel> = emptyList(),
    val isLoadingNavigation: Boolean = false,
    val navigationError: String? = null,
    val query: String = "",
    val isSearching: Boolean = false,
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
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
open class LibraryViewModel(
    private val library: LibraryRepository,
    private val recipes: RecipeRepository,
    private val configuration: ConfigurationRepository,
    private val device: eu.studio742.imago.core.data.DeviceLibrary,
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
    )

    val assets: Flow<PagingData<AssetUiModel>> = combine(
        combine(filter, configuration.activeSource()) { filter, source -> filter to source },
        uiState.map { it.selectedMonth },
        uiState.map { it.selectedAlbum },
        uiState.map { it.query.trim() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0 else SEARCH_DEBOUNCE_MS },
    ) { activeFilter, month, album, query -> AssetQuery(activeFilter.first, month, album, query, activeFilter.second) }
        .distinctUntilChanged()
        .flatMapLatest { (activeFilter, month, album, query) ->
            library.assets(
                filter = activeFilter,
                month = month.takeIf { album == null },
                albumId = album?.id?.takeIf { eu.studio742.imago.core.model.AssetReference.parse(it).libraryId == configuration.selectedLibraryId.value },
                query = query.takeIf(String::isNotEmpty),
            )
        }
        .map { pagingData ->
            pagingData.map { asset ->
                val isVideo = asset.type == AssetType.VIDEO
                AssetUiModel(
                    id = asset.id,
                    checksum = asset.checksum,
                    fileName = asset.originalFileName,
                    date = asset.localDateTime.ifBlank { asset.fileCreatedAt },
                    fileCreatedAt = asset.fileCreatedAt,
                    width = asset.width,
                    height = asset.height,
                    isFavorite = asset.isFavorite,
                    isEdited = asset.isEdited,
                    hasLocalRecipe = asset.hasLocalRecipe,
                    thumbnailUrl = library.thumbnailUrl(asset.id),
                    previewUrl = library.previewUrl(asset.id),
                    apiKey = library.apiKey(asset.id),
                    isVideo = isVideo,
                    durationMs = asset.durationMs,
                    videoUrl = if (isVideo) library.videoPlaybackUrl(asset.id) else null,
                    recipe = asset.localRecipe(),
                )
            }
        }
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
                refreshNavigation()
                syncCatalog()
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
        uiState.update { it.copy(selectedMonth = null, selectedAlbum = null) }
    }

    fun updateQuery(value: String) = uiState.update { it.copy(query = value) }

    fun openSearch() = uiState.update { it.copy(isSearching = true) }

    fun closeSearch() = uiState.update { it.copy(isSearching = false, query = "") }

    fun consumeActionError() = uiState.update { it.copy(actionError = null) }

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
            it.copy(
                section = section,
                selectedAlbum = null,
                navigationError = null,
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

    fun openAlbum(album: AlbumUiModel) = uiState.update {
        it.copy(section = LibrarySection.ALBUMS, selectedAlbum = album, selectedMonth = null)
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
            uiState.update { current ->
                current.copy(
                    albums = albums.getOrNull()?.map { it.toUiModel() }?.sortedWith(
                        compareByDescending<AlbumUiModel> { it.endDate.orEmpty() }.thenBy { it.name.lowercase() },
                    ) ?: current.albums,
                    months = months.getOrNull()?.map { MonthUiModel(it.month, it.assetCount) } ?: current.months,
                    isLoadingNavigation = false,
                    navigationError = albums.exceptionOrNull()?.message ?: months.exceptionOrNull()?.message,
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
    )
}
