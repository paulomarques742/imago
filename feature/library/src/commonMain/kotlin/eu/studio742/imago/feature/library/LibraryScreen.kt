@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.library

import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.core.designsystem.i18n.toUiText
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.library.resources.*
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.core.render.withRecipe
import coil3.request.crossfade
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import eu.studio742.imago.core.designsystem.ImagoChipRow
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoNavBarSurface
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoWidthClass
import eu.studio742.imago.core.designsystem.LocalImagoWindow
import eu.studio742.imago.core.designsystem.PhotoOverlayIcon
import eu.studio742.imago.core.designsystem.ImagoBrand
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import eu.studio742.imago.core.data.CatalogSyncState
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.core.render.editedAspectRatio
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The grid and its skeleton share these values. */
private val GRID_GUTTER = 6.dp

/**
 * The width the wide grid aims for when there is room to spare.
 *
 * It is not a hard minimum — it is the target from which the number of columns that make sense is
 * counted. A tile much larger than this stops being a grid and becomes a gallery of half a dozen
 * photos, which is what happened on a tablet with the phone's three fixed columns.
 */
private val GRID_TILE_TARGET = 200.dp

/**
 * The earliest year the date picker offers.
 *
 * An Immich library can have scans of old photographs; 1826 is the date of the oldest known one,
 * and serves as the bottom of the scale without inventing an arbitrary limit.
 */
private const val FIRST_PHOTOGRAPH_YEAR = 1826
private val TILE_RADIUS = 12.dp

/** How long to wait for the month the jump asked for before giving up on it. */
private const val MONTH_LOAD_TIMEOUT_MS = 4_000L
private val EDIT_DOT_SIZE = 7.dp

/** How much a tile the current selection does not accept is dimmed. */
private const val DISABLED_TILE_ALPHA = .35f

/**
 * How many columns fit in the window.
 *
 * Three is what the phone gets, and it is still what is used there. The steps above are not for
 * "tablets" but for widths: a phone held sideways and half a tablet screen end up in the same place,
 * which is what we want.
 */
@Composable
private fun gridColumns(): Int {
    val window = LocalImagoWindow.current
    return when (window.widthClass) {
        ImagoWidthClass.Compact -> 3
        ImagoWidthClass.Medium -> 4
        ImagoWidthClass.Expanded -> (window.width / GRID_TILE_TARGET).toInt().coerceIn(5, 8)
    }
}

@Composable
fun LibraryRoute(
    onOpenAsset: (AssetUiModel, List<AssetUiModel>) -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenComposer: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * The photos chosen on their way to a composition.
     *
     * The second argument drops the selection, and belongs to whoever receives it: while the
     * composition is not chosen nothing has happened yet, and closing the sheet without choosing
     * anything has to return the library exactly as it was.
     */
    onComposeSelection: (List<AssetUiModel>, onAdded: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    /** The photo open in the side pane, when there is one. On a narrow screen there is none. */
    selectedAssetId: String? = null,
    /** The photo we came from: the grid reappears centred on it instead of at the top. */
    focusAssetId: String? = null,
    onFocusConsumed: () -> Unit = {},
    /**
     * The view whoever navigated here asked for, or null to leave the library where it was.
     *
     * The bottom bar now lives outside the library, and from there "Albums" has to reach the albums
     * and not just the last place the grid was left. It is a one-off request — [onSectionConsumed]
     * clears it — so that switching views in here is not undone by it afterwards.
     */
    section: LibrarySection? = null,
    onSectionConsumed: () -> Unit = {},
    viewModel: LibraryViewModel = libraryViewModel(),
) {
    LaunchedEffect(section) {
        section?.let {
            viewModel.selectSection(it)
            onSectionConsumed()
        }
    }
    LibraryHost(
        viewModel = viewModel,
        modifier = modifier,
        picker = null,
        selectedAssetId = selectedAssetId,
        focusAssetId = focusAssetId,
        onFocusConsumed = onFocusConsumed,
        onOpenAsset = onOpenAsset,
        onOpenRecipes = onOpenRecipes,
        onOpenComposer = onOpenComposer,
        onOpenSettings = onOpenSettings,
        onComposeSelection = { selected -> onComposeSelection(selected, viewModel::clearSelection) },
    )
}

/**
 * The key of the instance that serves the pickers.
 *
 * Without it the picker shared the `ViewModel` with the library — it is the same activity — and
 * inherited the album, the chip and the selection that were there. Worse: choosing here left photos
 * marked in the library when going back to it.
 */
private const val PICKER_KEY = "library-picker"

/**
 * The library serving as a picker for another screen.
 *
 * It is the same screen, and that is the point: whoever fetches photos for a composition has the
 * albums there, the chips, the search, the jump to a date and the year ruler — instead of the bare
 * three-column grid the composer used to have as a picker.
 */
@Composable
fun LibraryPickerRoute(
    title: String,
    onConfirm: (List<AssetUiModel>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** A page background does not accept videos; an element does. */
    allowsVideo: Boolean = true,
    /** With a single choice, tapping decides and closes: there is nothing to confirm afterwards. */
    multiple: Boolean = true,
    confirmLabel: String = stringResource(Res.string.library_add),
    viewModel: LibraryViewModel = libraryViewModel(key = PICKER_KEY),
) {
    // The selection starts empty every time the picker opens: what was left from last time says
    // nothing about this one, and the `ViewModel` survives the screen closing.
    LaunchedEffect(Unit) { viewModel.clearSelection() }
    LibraryHost(
        viewModel = viewModel,
        modifier = modifier,
        picker = PickerMode(
            title = title,
            subtitle = subtitle,
            allowsVideo = allowsVideo,
            multiple = multiple,
            confirmLabel = confirmLabel,
            onConfirm = { chosen ->
                viewModel.clearSelection()
                onConfirm(chosen)
            },
            onCancel = {
                viewModel.clearSelection()
                onCancel()
            },
        ),
        selectedAssetId = null,
        focusAssetId = null,
        onFocusConsumed = {},
        onOpenAsset = { _, _ -> },
        onOpenRecipes = {},
        onOpenComposer = {},
        onOpenSettings = {},
        onComposeSelection = {},
    )
}

/** What the picker needs to know about itself. */
internal data class PickerMode(
    val title: String,
    val subtitle: String?,
    val allowsVideo: Boolean,
    val multiple: Boolean,
    val confirmLabel: String,
    val onConfirm: (List<AssetUiModel>) -> Unit,
    val onCancel: () -> Unit,
)

/**
 * What connects the `ViewModel` to the screen, be it the library or a picker.
 *
 * A single definition because the connection is the same in both cases: what changes is [picker],
 * and it is what the screen reads to know whether it is browsing or choosing.
 */
@Composable
private fun LibraryHost(
    viewModel: LibraryViewModel,
    modifier: Modifier,
    picker: PickerMode?,
    selectedAssetId: String?,
    focusAssetId: String?,
    onFocusConsumed: () -> Unit,
    onOpenAsset: (AssetUiModel, List<AssetUiModel>) -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenComposer: () -> Unit,
    onOpenSettings: () -> Unit,
    onComposeSelection: (List<AssetUiModel>) -> Unit,
) {
    val sourceId by viewModel.selectedSource.collectAsStateWithLifecycle()
    val savedSourceState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val assets = viewModel.assets.collectAsLazyPagingItems()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val catalogSync by viewModel.catalogSync.collectAsStateWithLifecycle()
    var retriedDateNavigation by remember { mutableStateOf(false) }

    LaunchedEffect(assets.loadState.refresh, assets.itemCount, uiState.isLoadingNavigation) {
        if (
            assets.loadState.refresh is LoadState.NotLoading &&
            assets.itemCount > 0 &&
            uiState.months.isEmpty() &&
            !uiState.isLoadingNavigation &&
            !retriedDateNavigation
        ) {
            retriedDateNavigation = true
            viewModel.refreshNavigation()
        }
    }

    savedSourceState.SaveableStateProvider(sourceId) {
        LibraryScreen(
            modifier = modifier,
            picker = picker,
            selectedAssetId = selectedAssetId,
            focusAssetId = focusAssetId,
            onFocusConsumed = onFocusConsumed,
            catalogSync = catalogSync,
            onResolveAssetIndex = viewModel::indexOfAsset,
            onResolveDateIndex = viewModel::indexOfDate,
            onRequestMonth = viewModel::loadMonthFor,
            assets = assets,
            filter = filter,
            uiState = uiState,
            // Changing the slice drops what was chosen — but only in the library, where still counting
            // photos the new view does not show could not be explained on screen. In a picker it is the
            // other way round: the chip and the album are how one looks for what is left to choose,
            // and losing the selection on every change kept it from gathering photos from different places.
            onFilterChange = { viewModel.selectFilter(it); if (picker == null) viewModel.clearSelection() },
            onSectionChange = { viewModel.selectSection(it); if (picker == null) viewModel.clearSelection() },
            onSelectMonth = viewModel::selectMonth,
            onOpenAlbum = { viewModel.openAlbum(it); if (picker == null) viewModel.clearSelection() },
            onCloseAlbum = { viewModel.closeAlbum(); if (picker == null) viewModel.clearSelection() },
            onOpenAsset = onOpenAsset,
            onOpenRecipes = onOpenRecipes,
            onOpenComposer = onOpenComposer,
            onComposeSelection = onComposeSelection,
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onToggleFavorite = viewModel::toggleFavorite,
            onOpenSearch = viewModel::openSearch,
            onCloseSearch = viewModel::closeSearch,
            onQueryChange = viewModel::updateQuery,
            onJumpToDate = viewModel::jumpToDate,
            onConsumePendingScroll = viewModel::consumePendingScroll,
            onConsumeActionError = viewModel::consumeActionError,
            onRefresh = {
                assets.refresh()
                viewModel.refreshNavigation()
                // Pulling down asks the server what changed, and what changed in the timeline counts
                // too: without this, a new month only appeared on the next launch.
                viewModel.syncCatalog()
            },
            onOpenSettings = onOpenSettings,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    modifier: Modifier,
    /** Null when this is the library; filled in when it is a picker for another screen. */
    picker: PickerMode?,
    selectedAssetId: String?,
    focusAssetId: String?,
    onFocusConsumed: () -> Unit,
    catalogSync: CatalogSyncState,
    onResolveAssetIndex: suspend (String) -> Int?,
    onResolveDateIndex: suspend (LocalDate) -> Int?,
    onRequestMonth: (LocalDate) -> Unit,
    assets: LazyPagingItems<AssetUiModel>,
    filter: LibraryFilter,
    uiState: LibraryUiState,
    onFilterChange: (LibraryFilter) -> Unit,
    onSectionChange: (LibrarySection) -> Unit,
    onSelectMonth: (String?) -> Unit,
    onOpenAlbum: (AlbumUiModel) -> Unit,
    onCloseAlbum: () -> Unit,
    onOpenAsset: (AssetUiModel, List<AssetUiModel>) -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenComposer: () -> Unit,
    onComposeSelection: (List<AssetUiModel>) -> Unit,
    onToggleSelection: (AssetUiModel) -> Unit,
    onClearSelection: () -> Unit,
    onToggleFavorite: (AssetUiModel) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onJumpToDate: (LocalDate) -> Unit,
    onConsumePendingScroll: () -> Unit,
    onConsumeActionError: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // The order is that of priorities, and it is the opposite of what it looks like: the dispatcher
    // calls what was registered last first. In a picker, leaving it is the last resort — before that
    // "back" closes the album, closes the search, drops whatever is chosen.
    picker?.let { BackHandler(onBack = it.onCancel) }
    BackHandler(enabled = uiState.selectedAlbum != null, onBack = onCloseAlbum)
    BackHandler(enabled = uiState.selectedAlbum == null && uiState.isSearching, onBack = onCloseSearch)
    val selecting = uiState.selection.isNotEmpty()
    BackHandler(enabled = selecting, onBack = onClearSelection)
    // In a multi-choice picker, the circles are on the tiles from the start: here choosing is the
    // reason the screen is open, and hiding the mark until the first tap would be hiding it.
    val selectionVisible = selecting || picker?.multiple == true
    var datePicker by remember { mutableStateOf(false) }
    val gridState = rememberLazyStaggeredGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Jumping to a date is scrolling to its index, counted in SQL over the whole catalogue. It used
    // to be a scan of the loaded list, which only found the destination if it was already there —
    // and so the jump had to slice the grid by month to put it there.
    var requestedFor by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(uiState.pendingScrollDate, assets.itemCount) {
        val target = uiState.pendingScrollDate ?: return@LaunchedEffect
        val index = onResolveDateIndex(target)
        if (index != null && index < assets.itemCount) {
            snapshotFlow { gridState.layoutInfo.viewportSize.height }.first { it > 0 }
            gridState.scrollToItem(index)
            onConsumePendingScroll()
            return@LaunchedEffect
        }
        // The date is outside what the catalogue already has. That month is requested — one request —
        // and the effect runs again on its own when the rows come in.
        if (requestedFor != target) {
            requestedFor = target
            onRequestMonth(target)
            delay(MONTH_LOAD_TIMEOUT_MS)
        }
        // Either the month had nothing, or it did not arrive in time: giving up is better than leaving
        // the request pending to fire later, in the middle of another scroll.
        onConsumePendingScroll()
    }
    // Coming back from the detail puts the grid where it was. The scroll position does not survive —
    // on a narrow screen the library leaves the composition while the photo is open — but the paged
    // catalogue does, and that is where the photo we came from is looked up.
    LaunchedEffect(focusAssetId) {
        val target = focusAssetId ?: return@LaunchedEffect
        // The index comes from a count over the catalogue, not from a search in the loaded list. With
        // the whole timeline in Room, the photo we came from is almost always outside the window
        // paging has at hand — looking for it there almost never found it.
        val index = onResolveAssetIndex(target)
            ?: (0 until assets.itemCount).firstOrNull { assets.peek(it)?.id == target }
        if (index == null) {
            onFocusConsumed()
            return@LaunchedEffect
        }
        // The grid may not have been measured yet; without a measurement there is no centre to bring anything to.
        snapshotFlow { gridState.layoutInfo.viewportSize.height }.first { it > 0 }
        gridState.centreOn(index)
        onFocusConsumed()
    }
    LaunchedEffect(uiState.actionError) {
        val message = uiState.actionError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolveNow())
        onConsumeActionError()
    }
    val inAlbum = uiState.selectedAlbum != null
    // The months the server counts describe the whole library — but they only describe *this* grid
    // when it is not sliced. In an album, a chip or a search the map does not serve, and fast
    // scrolling goes back to measuring itself by what is loaded.
    val describesTheGrid = filter == LibraryFilter.ALL &&
        uiState.query.isBlank() &&
        uiState.selectedAlbum == null
    val timeline = remember(uiState.months, describesTheGrid) {
        LibraryTimeline(if (describesTheGrid) uiState.months else emptyList())
    }
    Scaffold(
        modifier = modifier,
        containerColor = ImagoColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(Modifier.background(Color.Black.copy(alpha = 0.22f)).statusBarsPadding()) {
                if (picker != null) {
                    PickerTopBar(
                        picker = picker,
                        album = uiState.selectedAlbum,
                        selected = uiState.selection.size,
                        section = uiState.section,
                        isSearching = uiState.isSearching,
                        query = uiState.query,
                        onQueryChange = onQueryChange,
                        onOpenSearch = onOpenSearch,
                        onCloseSearch = onCloseSearch,
                        onOpenDatePicker = { datePicker = true },
                        onSectionChange = onSectionChange,
                        onCloseAlbum = onCloseAlbum,
                        onClearSelection = onClearSelection,
                    )
                } else if (selecting) {
                    SelectionTopBar(count = uiState.selection.size, onClear = onClearSelection)
                } else if (inAlbum) {
                    AlbumTopBar(album = uiState.selectedAlbum, onBack = onCloseAlbum)
                } else {
                    LibraryTopBar(
                        isSearching = uiState.isSearching,
                        query = uiState.query,
                        onQueryChange = onQueryChange,
                        onOpenSearch = onOpenSearch,
                        onCloseSearch = onCloseSearch,
                        onOpenDatePicker = { datePicker = true },
                        onRefresh = onRefresh,
                        onOpenSettings = onOpenSettings,
                    )
                }
                // The chips slice the timeline. Inside an album, or in the album list, they have
                // nothing to act on; in the middle of a selection neither, because switching the
                // slice is what undoes it.
                if ((!selecting || picker != null) && !inAlbum && uiState.section == LibrarySection.TIMELINE) {
                    ImagoChipRow(
                        options = LibraryFilter.entries,
                        selected = filter,
                        label = { it.label() },
                        onSelect = onFilterChange,
                        modifier = Modifier.padding(bottom = ImagoSpacing.Md),
                    )
                }
                uiState.selectedMonth?.takeIf { !inAlbum && !selecting }?.let { month ->
                    SelectedMonthChip(month = month, onClear = { onSelectMonth(null) })
                }
                if (catalogSync.syncing) CatalogSyncBar(catalogSync)
            }
        },
        bottomBar = {
            // With photos chosen, the bar stops navigating and becomes what is done with them — also
            // inside an album, which is where the normal bar does not appear.
            if (picker != null) {
                // Choosing a single one is decided on the tap: a bar saying "confirm" would be a
                // second tap confirming what the first had left no doubt about.
                if (picker.multiple) {
                    PickerConfirmBar(
                        label = picker.confirmLabel,
                        count = uiState.selection.size,
                        onConfirm = { picker.onConfirm(uiState.selection.values.toList()) },
                    )
                }
            } else if (selecting) {
                SelectionBottomBar(
                    count = uiState.selection.size,
                    onCompose = { onComposeSelection(uiState.selection.values.toList()) },
                )
            } else if (!inAlbum) {
                LibraryNavBar(
                    selected = when (uiState.section) {
                        LibrarySection.TIMELINE -> LibraryNavDestination.TIMELINE
                        LibrarySection.ALBUMS -> LibraryNavDestination.ALBUMS
                    },
                    onSelect = { destination ->
                        when (destination) {
                            LibraryNavDestination.TIMELINE -> onSectionChange(LibrarySection.TIMELINE)
                            LibraryNavDestination.ALBUMS -> onSectionChange(LibrarySection.ALBUMS)
                            LibraryNavDestination.RECIPES -> onOpenRecipes()
                            LibraryNavDestination.COMPOSER -> onOpenComposer()
                        }
                    },
                    onOpenSettings = onOpenSettings,
                    onRefresh = onRefresh,
                )
            }
        },
    ) { padding ->
        val isRefreshing = assets.loadState.refresh is LoadState.Loading && assets.itemCount > 0 ||
            uiState.isLoadingNavigation && uiState.albums.isNotEmpty()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (uiState.section == LibrarySection.ALBUMS && uiState.selectedAlbum == null) {
                AlbumBrowser(state = uiState, onOpenAlbum = onOpenAlbum, onRetry = onRefresh)
            } else {
                PhotoBrowser(
                    selectedAssetId = selectedAssetId,
                    picker = picker,
                    selection = uiState.selection.keys,
                    selectionVisible = selectionVisible,
                    selecting = selecting,
                    onToggleSelection = onToggleSelection,
                    assets = assets,
                    filter = filter,
                    query = uiState.query,
                    gridState = gridState,
                    // While the catalogue is filling up, the map speaks of a library larger than the
                    // grid: the ruler would keep pointing at years that are not there yet.
                    timeline = if (catalogSync.syncing) LibraryTimeline(emptyList()) else timeline,
                    onOpenAsset = onOpenAsset,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
    if (datePicker) {
        JumpToDateDialog(
            onDismiss = { datePicker = false },
            onSelect = {
                datePicker = false
                onJumpToDate(it)
            },
        )
    }
}

/**
 * Choosing a date and jumping to it.
 *
 * Any past date will do, and that is the point. Before, only dates within the range the local
 * catalogue already covered could be chosen, which turned the tool inside out: jumping to a date was
 * only possible once one had already scrolled there, and in that case there was no need to jump. The
 * jump chooses the month, the month is a slice Immich knows how to make, and the mediator fetches it
 * from the server even if it was never cached.
 *
 * The future stays out: there are no photos there to jump to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JumpToDateDialog(
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = remember(today) {
            today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        },
        yearRange = FIRST_PHOTOGRAPH_YEAR..today.year,
        selectableDates = remember(today) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

                override fun isSelectableYear(year: Int) = year <= today.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let {
                        // O DatePicker devolve meia-noite UTC; ler noutro fuso deslocava o dia.
                        onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text(stringResource(Res.string.library_jump)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_cancel)) } },
    ) {
        DatePicker(state = state, title = { Text(stringResource(Res.string.library_jump_to_date), Modifier.padding(ImagoSpacing.Xxl)) })
    }
}

@Composable
private fun LibraryFilter.label() = when (this) {
    LibraryFilter.ALL -> stringResource(Res.string.library_filter_all)
    LibraryFilter.RECENT -> stringResource(Res.string.library_filter_recent)
    LibraryFilter.FAVORITES -> stringResource(Res.string.library_filter_favorites)
    LibraryFilter.EDITED -> stringResource(Res.string.library_filter_edited)
}

/**
 * The library bar: wordmark, search and menu.
 *
 * The wordmark comes from the official file and not from a `Text` with letter spacing — the brand
 * guidelines forbid composing the wordmark with live text.
 */
@Composable
private fun LibraryTopBar(
    isSearching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = ImagoSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSearching) {
            LibrarySearchField(
                query = query,
                onQueryChange = onQueryChange,
                onOpenDatePicker = onOpenDatePicker,
                onCloseSearch = onCloseSearch,
            )
        } else {
            Image(
                painter = rememberVectorPainter(ImagoBrand.Wordmark),
                contentDescription = "IMAGO",
                modifier = Modifier.height(17.dp),
            )
            eu.studio742.imago.core.designsystem.SyncIndicator(Modifier.padding(start = ImagoSpacing.Sm))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(Res.string.library_search),
                    tint = ImagoColors.TextPrimary,
                )
            }
            // Next to the magnifier, and not inside the menu: searching by name and jumping to a date
            // are the same intention — reaching a specific photo — and the second was hidden behind
            // two taps while the first was in plain view.
            IconButton(onClick = onOpenDatePicker) {
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = stringResource(Res.string.library_jump_to_date),
                    tint = ImagoColors.TextPrimary,
                )
            }
            LibrarySourceButton()
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(Res.string.library_more_options),
                        tint = ImagoColors.TextPrimary,
                    )
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.library_settings)) },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = { moreExpanded = false; onOpenSettings() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.library_refresh)) },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        onClick = {
                            moreExpanded = false
                            onRefresh()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The bar while photos are being chosen.
 *
 * It takes the place of the library's bar — and the album's — because what was there no longer
 * applies: searching or jumping to a date in the middle of a selection would undo it.
 */
@Composable
private fun SelectionTopBar(count: Int, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(Res.string.library_end_selection),
                tint = ImagoColors.TextPrimary,
            )
        }
        Text(
            text = pluralStringResource(Res.plurals.library_selected, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = ImagoColors.TextPrimary,
            modifier = Modifier.padding(start = ImagoSpacing.Xs),
        )
    }
}

/**
 * What is done with the chosen photos.
 *
 * It sits at the bottom, in place of the navigation bar, because that is where the thumb reaches —
 * the top bar is kept for saying how many there are and for dropping them.
 */
@Composable
private fun SelectionBottomBar(count: Int, onCompose: () -> Unit) {
    ImagoNavBarSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ImagoSizes.TouchTarget + ImagoSpacing.Xl)
                .padding(horizontal = ImagoSpacing.Lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(ImagoRadii.Pill))
                    .clickable(role = Role.Button, onClick = onCompose)
                    .sizeIn(minHeight = ImagoSizes.TouchTarget)
                    .padding(horizontal = ImagoSpacing.Xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.ViewCarousel,
                    contentDescription = null,
                    tint = ImagoColors.Ivory,
                    modifier = Modifier.size(ImagoSizes.IconDefault),
                )
                Text(
                    text = pluralStringResource(Res.plurals.library_add_to_composition, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = ImagoColors.Ivory,
                    modifier = Modifier.padding(start = ImagoSpacing.Sm),
                )
            }
        }
    }
}

/**
 * The search box, with the jump to a date next to it.
 *
 * A single definition for the two bars that show it — the library's and the picker's — because
 * searching by name and reaching a date are the same intention in both.
 */
@Composable
private fun RowScope.LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.library_file_name), color = ImagoColors.TextTertiary) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            Row {
                IconButton(onClick = onOpenDatePicker) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = stringResource(Res.string.library_jump_to_date))
                }
                LibrarySourceButton()
                IconButton(onClick = onCloseSearch) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.library_close_search))
                }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = ImagoColors.TextPrimary,
            unfocusedTextColor = ImagoColors.TextPrimary,
            cursorColor = ImagoColors.Ivory,
            focusedLeadingIconColor = ImagoColors.TextSecondary,
            unfocusedLeadingIconColor = ImagoColors.TextSecondary,
            focusedTrailingIconColor = ImagoColors.TextSecondary,
            unfocusedTrailingIconColor = ImagoColors.TextSecondary,
        ),
        modifier = Modifier.weight(1f).focusRequester(focusRequester),
    )
}

/**
 * The library's bar when it is serving as a picker.
 *
 * It says where what is chosen will go and how many are already in, and keeps the three ways of
 * searching the library has: the magnifier, the calendar and the albums. The "more" menu stays out —
 * refreshing the library or disconnecting the server are not things to do in the middle of a
 * selection.
 */
@Composable
private fun PickerTopBar(
    picker: PickerMode,
    album: AlbumUiModel?,
    selected: Int,
    section: LibrarySection,
    isSearching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onSectionChange: (LibrarySection) -> Unit,
    onCloseAlbum: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val inAlbum = album != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The arrow undoes one layer at a time — first the album, and only then the picker — so that
        // entering an album by mistake does not cost the selection already made.
        IconButton(onClick = if (inAlbum) onCloseAlbum else picker.onCancel) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = if (inAlbum) stringResource(Res.string.library_back_to_albums) else stringResource(Res.string.library_cancel),
                tint = ImagoColors.TextPrimary,
            )
        }
        if (isSearching) {
            LibrarySearchField(
                query = query,
                onQueryChange = onQueryChange,
                onOpenDatePicker = onOpenDatePicker,
                onCloseSearch = onCloseSearch,
            )
            return@Row
        }
        Column(Modifier.weight(1f).padding(start = ImagoSpacing.Xs)) {
            Text(
                text = album?.name ?: picker.title,
                style = MaterialTheme.typography.titleMedium,
                color = ImagoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val caption = when {
                selected > 0 -> pluralStringResource(Res.plurals.library_selected, selected, selected)
                else -> picker.subtitle
            }
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = ImagoColors.TextTertiary,
                )
            }
        }
        if (selected > 0) {
            IconButton(onClick = onClearSelection) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(Res.string.library_clear_selection),
                    tint = ImagoColors.TextPrimary,
                )
            }
        }
        IconButton(onClick = onOpenSearch) {
            Icon(Icons.Outlined.Search, contentDescription = stringResource(Res.string.library_search), tint = ImagoColors.TextPrimary)
        }
        IconButton(onClick = onOpenDatePicker) {
            Icon(
                Icons.Outlined.CalendarMonth,
                contentDescription = stringResource(Res.string.library_jump_to_date),
                tint = ImagoColors.TextPrimary,
            )
        }
        LibrarySourceButton()
        // Without the bottom bar navigating, the albums needed a door: this is the button the
        // library's bar does not have because there they are a navigation destination.
        if (!inAlbum) {
            val onAlbums = section == LibrarySection.ALBUMS
            IconButton(
                onClick = {
                    onSectionChange(if (onAlbums) LibrarySection.TIMELINE else LibrarySection.ALBUMS)
                },
            ) {
                Icon(
                    if (onAlbums) Icons.Filled.Photo else Icons.Outlined.Collections,
                    contentDescription = if (onAlbums) stringResource(Res.string.library_show_timeline) else stringResource(Res.string.library_show_albums),
                    tint = if (onAlbums) ImagoColors.Ivory else ImagoColors.TextPrimary,
                )
            }
        }
    }
}

/**
 * The button that closes the selection.
 *
 * It stays disabled while nothing is chosen, instead of disappearing: a bar that appears and goes as
 * tiles are tapped makes the grid jump in height on every tap.
 */
@Composable
private fun PickerConfirmBar(label: String, count: Int, onConfirm: () -> Unit) {
    ImagoNavBarSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ImagoSizes.TouchTarget + ImagoSpacing.Xl)
                .padding(horizontal = ImagoSpacing.Lg),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = count > 0
            val tint = if (enabled) ImagoColors.Ivory else ImagoColors.TextDisabled
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(ImagoRadii.Pill))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onConfirm)
                    .sizeIn(minHeight = ImagoSizes.TouchTarget)
                    .padding(horizontal = ImagoSpacing.Xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(ImagoSizes.IconDefault),
                )
                Text(
                    text = if (enabled) "$label ($count)" else label,
                    style = MaterialTheme.typography.titleSmall,
                    color = tint,
                    modifier = Modifier.padding(start = ImagoSpacing.Sm),
                )
            }
        }
    }
}

@Composable
private fun AlbumTopBar(album: AlbumUiModel?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.library_back_to_albums),
                tint = ImagoColors.TextPrimary,
            )
        }
        Column(Modifier.weight(1f).padding(start = ImagoSpacing.Xs)) {
            Text(
                text = album?.name.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = ImagoColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            album?.let {
                Text(
                    text = pluralStringResource(Res.plurals.library_items, it.assetCount.toInt(), it.assetCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = ImagoColors.TextTertiary,
                )
            }
        }
    }
}

/** The active month, when there is one: without this the filter becomes invisible once the dialog closes. */
@Composable
private fun SelectedMonthChip(month: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier.padding(start = ImagoSpacing.Lg, bottom = ImagoSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(ImagoRadii.Pill))
                .background(ImagoColors.Charcoal)
                .clickable(onClick = onClear)
                .padding(start = ImagoSpacing.Lg, end = ImagoSpacing.Md, top = ImagoSpacing.Sm, bottom = ImagoSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatMonth(month),
                style = MaterialTheme.typography.titleSmall,
                color = ImagoColors.TextSecondary,
            )
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(Res.string.library_clear_date_filter),
                tint = ImagoColors.TextTertiary,
                modifier = Modifier.padding(start = ImagoSpacing.Xs).size(ImagoSizes.IconSmall),
            )
        }
    }
}

@Composable
private fun PhotoBrowser(
    selectedAssetId: String?,
    picker: PickerMode?,
    selection: Set<String>,
    selectionVisible: Boolean,
    selecting: Boolean,
    onToggleSelection: (AssetUiModel) -> Unit,
    assets: LazyPagingItems<AssetUiModel>,
    filter: LibraryFilter,
    query: String,
    gridState: LazyStaggeredGridState,
    timeline: LibraryTimeline,
    onOpenAsset: (AssetUiModel, List<AssetUiModel>) -> Unit,
    onToggleFavorite: (AssetUiModel) -> Unit,
) {
    when {
        assets.loadState.refresh is LoadState.Loading && assets.itemCount == 0 -> LoadingState()
        assets.loadState.refresh is LoadState.Error && assets.itemCount == 0 -> {
            val message = (assets.loadState.refresh as LoadState.Error).error.toUiText(Res.string.library_load_failed).resolve()
            ErrorState(message = message, onRetry = assets::retry)
        }
        assets.itemCount == 0 && assets.loadState.refresh is LoadState.NotLoading ->
            EmptyState(filter = filter, query = query)
        else -> Box(Modifier.fillMaxSize()) {
            AssetGrid(
                assets = assets,
                gridState = gridState,
                selectedAssetId = selectedAssetId,
                picker = picker,
                selection = selection,
                selectionVisible = selectionVisible,
                selecting = selecting,
                onToggleSelection = onToggleSelection,
                onOpenAsset = onOpenAsset,
                onToggleFavorite = onToggleFavorite,
            )
            LibraryFastScroll(
                assets = assets,
                gridState = gridState,
                timeline = timeline,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * Brings the grid to show this tile in the middle of the screen.
 *
 * If it is already fully in view, it does nothing: with the detail beside it the open photo is
 * usually visible, and re-centring it would be a jump nobody asked for.
 *
 * There are two scrolls because the first is what reveals the measurement: `scrollToItem` puts the
 * tile at the top of the viewport, and only after it is placed is its height known to bring it down
 * to the middle — in a staggered grid every tile has its own.
 */
private suspend fun LazyStaggeredGridState.centreOn(index: Int) {
    val info = layoutInfo
    val alreadyVisible = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (alreadyVisible != null &&
        alreadyVisible.offset.y >= info.viewportStartOffset &&
        alreadyVisible.offset.y + alreadyVisible.size.height <= info.viewportEndOffset
    ) {
        return
    }
    scrollToItem(index)
    val placed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = layoutInfo.viewportSize.height -
        layoutInfo.beforeContentPadding - layoutInfo.afterContentPadding
    val centre = ((viewport - placed.size.height) / 2).coerceAtLeast(0)
    scrollBy((placed.offset.y - layoutInfo.viewportStartOffset - centre).toFloat())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetGrid(
    assets: LazyPagingItems<AssetUiModel>,
    gridState: LazyStaggeredGridState,
    selectedAssetId: String?,
    picker: PickerMode?,
    selection: Set<String>,
    selectionVisible: Boolean,
    selecting: Boolean,
    onToggleSelection: (AssetUiModel) -> Unit,
    onOpenAsset: (AssetUiModel, List<AssetUiModel>) -> Unit,
    onToggleFavorite: (AssetUiModel) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(gridColumns()),
        modifier = Modifier.fillMaxSize().padding(horizontal = GRID_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        verticalItemSpacing = GRID_GUTTER,
    ) {
        items(
            count = assets.itemCount,
            key = { index: Int -> assets.peek(index)?.id ?: "placeholder-$index" },
        ) { index: Int ->
            val asset = assets[index]
            if (asset == null) {
                // The catalogue knows this photo exists before its page leaves Room. The empty tile
                // holds its place during that instant; without it, the grid shrank and grew again on
                // every fast scroll.
                PlaceholderTile()
            } else {
                AssetTile(
                    asset = asset,
                    selected = asset.id == selectedAssetId,
                    checked = asset.id in selection,
                    showsSelection = selectionVisible,
                    // A video stays in view where it cannot be chosen — dimmed, and not responding to
                    // taps. Hiding it would make the picker's grid not match the library it came from.
                    enabled = picker == null || picker.allowsVideo || !asset.isVideo,
                    // In the middle of a selection, tapping is choosing: opening the photo would force
                    // leaving and starting over because of a tap on the wrong thumbnail.
                    onClick = {
                        when {
                            picker?.multiple == false -> picker.onConfirm(listOf(asset))
                            picker != null || selecting -> onToggleSelection(asset)
                            else -> onOpenAsset(asset, assets.itemSnapshotList.items)
                        }
                    },
                    onLongClick = { if (picker?.multiple != false) onToggleSelection(asset) },
                    onToggleFavorite = { onToggleFavorite(asset) },
                )
            }
        }
        // The grid's footer is what says whether there is more library below.
        //
        // Before, only the `Loading` state got through here: when an APPEND failed — the network
        // dropping in the middle of a long scroll is enough — nothing was drawn and the grid looked
        // exactly like a library that had ended. Worse, Paging does not retry a failed APPEND on its
        // own, so scrolling to the end again did not unblock it either: the library stayed stuck at
        // that point until the app was restarted.
        when (val append = assets.loadState.append) {
            is LoadState.Loading -> item(span = StaggeredGridItemSpan.FullLine) { AppendLoading() }
            is LoadState.Error -> item(span = StaggeredGridItemSpan.FullLine) {
                AppendError(message = append.error.toUiText(Res.string.library_load_more_failed).resolve(), onRetry = assets::retry)
            }
            is LoadState.NotLoading -> Unit
        }
    }
}

@Composable
private fun AppendLoading() = Box(
    modifier = Modifier.fillMaxWidth().padding(vertical = ImagoSpacing.Xxl),
    contentAlignment = Alignment.Center,
) {
    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(ImagoSizes.IconLarge))
}

/**
 * The end of the scroll when it was not the end of the library.
 *
 * It says what failed and gives the button that unblocks it — without it, the only way out was
 * reopening the app.
 */
@Composable
private fun AppendError(message: String?, onRetry: () -> Unit) = Column(
    modifier = Modifier.fillMaxWidth().padding(ImagoSpacing.Xl),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
) {
    Text(
        text = message ?: stringResource(Res.string.library_load_more_failed),
        style = MaterialTheme.typography.bodySmall,
        color = ImagoColors.TextSecondary,
        textAlign = TextAlign.Center,
    )
    TextButton(onClick = onRetry) { Text(stringResource(Res.string.library_try_again)) }
}

@Composable
private fun AlbumBrowser(state: LibraryUiState, onOpenAlbum: (AlbumUiModel) -> Unit, onRetry: () -> Unit) {
    when {
        state.isLoadingNavigation && state.albums.isEmpty() -> LoadingState()
        state.navigationError != null && state.albums.isEmpty() -> ErrorState(state.navigationError.resolve(), onRetry)
        state.albums.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.library_no_albums), color = ImagoColors.TextSecondary)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = ImagoSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Lg),
        ) {
            gridItems(state.albums, key = AlbumUiModel::id) { album ->
                AlbumTile(album, onClick = { onOpenAlbum(album) })
            }
        }
    }
}

@Composable
private fun AlbumTile(album: AlbumUiModel, onClick: () -> Unit) {
    val context = LocalPlatformContext.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .clickable(onClick = onClick)
            .padding(bottom = ImagoSpacing.Sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.18f)
                .clip(RoundedCornerShape(ImagoRadii.Small))
                .background(ImagoColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (album.thumbnailUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(album.thumbnailUrl)
                        .libraryAuth(album.apiKey)
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(Res.string.library_open_album, album.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Collections, contentDescription = null, tint = ImagoColors.TextTertiary)
            }
            if (album.shared) {
                PhotoOverlayIcon(
                    size = ImagoSizes.IconSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(ImagoSpacing.Sm),
                ) {
                    Icon(
                        Icons.Outlined.Group,
                        contentDescription = stringResource(Res.string.library_shared_album),
                        tint = ImagoColors.BrandWhite,
                    )
                }
            }
        }
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = ImagoSpacing.Sm),
        )
        Text(
            text = pluralStringResource(Res.plurals.library_items, album.assetCount.toInt(), album.assetCount),
            style = MaterialTheme.typography.bodySmall,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetTile(
    asset: AssetUiModel,
    selected: Boolean,
    checked: Boolean,
    /** Whether the tile shows the selection mark — and therefore hides the heart. */
    showsSelection: Boolean,
    /** False where the selection does not accept this media: it stays in view, dimmed and mute. */
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val context = LocalPlatformContext.current
    val haptics = LocalHapticFeedback.current
    // The recipe may rotate or crop: the box has to reserve the shape of the result, or the tile
    // opens with the original's shape and the edited thumbnail comes in cut off.
    val editedRatio = asset.recipe?.editedAspectRatio(asset.width ?: 0L, asset.height ?: 0L)
    val sourceRatio = if (asset.width != null && asset.height != null && asset.height > 0L) {
        asset.width.toFloat() / asset.height.toFloat()
    } else {
        1f
    }
    val aspectRatio = (editedRatio ?: sourceRatio).coerceIn(0.72f, 1.35f)
    // Semantics blocks are not composition: the texts are read here, before them.
    val selectedDescription = stringResource(Res.string.library_asset_selected, asset.fileName)
    val selectDescription = stringResource(Res.string.library_select_asset, asset.fileName)
    val openDescription = stringResource(Res.string.library_asset_open, asset.fileName)
    val favoriteDescription = stringResource(if (asset.isFavorite) Res.string.library_unfavorite else Res.string.library_favorite)
    val editDescription = stringResource(if (asset.hasLocalRecipe) Res.string.library_has_local_edit else Res.string.library_edited_in_immich)
    // A mouse or a trackpad — a tablet's keyboard cover has one — knows where it is before clicking,
    // and the tile responds to that. On a touch-only screen none of this lights up.
    val pointer = remember { MutableInteractionSource() }
    val hovered by pointer.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(TILE_RADIUS))
            .background(ImagoColors.SurfaceElevated)
            // With the detail beside it, the grid stops being just a way in and becomes a list with
            // an active row: without this mark there is no telling which thumbnail is the one open in
            // the pane. The pointer highlight is weaker on purpose — it says "this is where you
            // click", not "this is the one that is open".
            .then(
                when {
                    checked -> Modifier.border(3.dp, ImagoColors.Ivory, RoundedCornerShape(TILE_RADIUS))
                    selected -> Modifier.border(2.dp, ImagoColors.Ivory, RoundedCornerShape(TILE_RADIUS))
                    hovered -> Modifier.border(1.dp, ImagoColors.BorderStrong, RoundedCornerShape(TILE_RADIUS))
                    else -> Modifier
                }
            )
            .alpha(if (enabled) 1f else DISABLED_TILE_ALPHA)
            .hoverable(pointer)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    // The vibration is what separates a long press from a tap that took a while: without
                    // it, the selection opens with nothing saying it was the resting finger that opened it.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .semantics {
                if (showsSelection) {
                    contentDescription = if (checked) selectedDescription else selectDescription
                } else if (selected) {
                    contentDescription = openDescription
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(asset.thumbnailUrl)
                .libraryAuth(asset.apiKey)
                .crossfade(true)
                .withRecipe(asset.recipe)
                .build(),
            // The tile's description becomes the selection's while it lasts; two in a row would make
            // the screen reader say "open" for a thumbnail that no longer opens anything.
            contentDescription = when {
                showsSelection -> null
                asset.isVideo -> stringResource(Res.string.library_play_asset, asset.fileName)
                else -> stringResource(Res.string.library_open_asset, asset.fileName)
            },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // The chosen one darkens: the frame alone gets lost against a light photo, and the veil does
        // the same job as the disc behind the check mark.
        if (checked) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        }
        if (asset.isVideo) {
            VideoBadge(
                durationMs = asset.durationMs,
                modifier = Modifier.align(Alignment.TopEnd).padding(ImagoSpacing.Sm),
            )
        }
        if (showsSelection) {
            Icon(
                imageVector = if (checked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) ImagoColors.Ivory else ImagoColors.BrandWhite,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ImagoSpacing.Sm)
                    .size(ImagoSizes.IconLarge)
                    .shadow(elevation = 4.dp, shape = CircleShape),
            )
        }
        // The heart is on every tile, filled or hollow, and is a target itself: marking a favourite
        // should not require opening the photo. It goes away while photos are being chosen: there the
        // whole tile has a single meaning, and a second target on top of it would only give
        // favourites by mistake.
        if (!showsSelection) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(ImagoSizes.TouchTarget)
                    .clip(CircleShape)
                    .clickable(
                        onClick = onToggleFavorite,
                        role = Role.Checkbox,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = ImagoSizes.TouchTarget / 2),
                    )
                    .semantics {
                        contentDescription = favoriteDescription
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (asset.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = ImagoColors.BrandWhite,
                    // The shadow does the same job as PhotoOverlayIcon's dark disc without covering the
                    // photo: the icon appears on every tile, not only on favourites.
                    modifier = Modifier.size(ImagoSizes.IconLarge).shadow(elevation = 4.dp, shape = CircleShape),
                )
            }
        }
        // Videos do not go through the editor, so they never have the dot: what it promises is an
        // edit that can be opened.
        if (!asset.isVideo && (asset.isEdited || asset.hasLocalRecipe)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ImagoSpacing.Md)
                    .size(EDIT_DOT_SIZE)
                    .clip(CircleShape)
                    .background(ImagoColors.Gold)
                    .border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
                    .semantics {
                        contentDescription = editDescription
                    },
            )
        }
    }
}

@Composable
private fun PlaceholderTile() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(TILE_RADIUS))
            .background(ImagoColors.SurfaceElevated),
    )
}

/**
 * The notice that the timeline is still arriving.
 *
 * It counts months because that is the unit of the work, and disappears when it ends. It blocks
 * nothing: the library works anyway while it runs, it just does not reach every year yet.
 */
@Composable
private fun CatalogSyncBar(state: CatalogSyncState) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Sm),
    ) {
        Text(
            text = if (state.total == 0) stringResource(Res.string.library_refreshing) else stringResource(Res.string.library_syncing_timeline, state.done, state.total),
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextTertiary,
        )
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
            color = ImagoColors.Ivory,
            trackColor = ImagoColors.BorderSubtle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ImagoSpacing.Xs)
                .height(2.dp),
        )
    }
}

/**
 * What tells a video apart from a photo in the grid.
 *
 * The duration comes with the triangle because it is the information that decides whether it is
 * worth opening; without it the tile only says there is a video, not which.
 */
@Composable
private fun VideoBadge(durationMs: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = ImagoSpacing.Xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = ImagoColors.BrandWhite,
            modifier = Modifier.size(ImagoSizes.IconSmall),
        )
        formatDuration(durationMs)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = ImagoColors.BrandWhite,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** `1:07`, or `1:02:30` past the hour. Without a known duration none is invented. */
internal fun formatDuration(durationMs: Long?): String? {
    val total = durationMs?.takeIf { it > 0L }?.div(1000) ?: return null
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.getDefault(), hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.getDefault(), minutes, seconds)
    }
}

/**
 * The skeleton has to have the same grid as the result, or the library jumps in layout as soon as
 * the first thumbnails arrive. The proportions alternate so it does not look like a table.
 */
@Composable
private fun LoadingState() {
    val ratios = listOf(1f, 0.82f, 1.15f, 0.94f, 1.28f, 0.78f)
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(gridColumns()),
        modifier = Modifier.fillMaxSize().padding(horizontal = GRID_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        verticalItemSpacing = GRID_GUTTER,
        userScrollEnabled = false,
    ) {
        items(36) { index ->
            Box(
                Modifier
                    .aspectRatio(ratios[index % ratios.size])
                    .clip(RoundedCornerShape(TILE_RADIUS))
                    .background(ImagoColors.SurfaceElevated),
            )
        }
    }
}

@Composable
private fun ErrorState(message: String?, onRetry: () -> Unit) = Column(
    modifier = Modifier.fillMaxSize().padding(ImagoSpacing.Xxxl),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
) {
    Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = ImagoColors.TextTertiary)
    Text(
        message ?: stringResource(Res.string.library_load_failed),
        color = ImagoColors.TextSecondary,
        modifier = Modifier.padding(vertical = ImagoSpacing.Md),
    )
    TextButton(onClick = onRetry) { Text(stringResource(Res.string.library_retry)) }
}

@Composable
private fun EmptyState(filter: LibraryFilter, query: String) = Box(
    Modifier.fillMaxSize().padding(ImagoSpacing.Xxxl),
    contentAlignment = Alignment.Center,
) {
    Text(
        text = when {
            query.isNotBlank() -> stringResource(Res.string.library_no_match, query)
            filter == LibraryFilter.FAVORITES -> stringResource(Res.string.library_no_favorites)
            filter == LibraryFilter.EDITED -> stringResource(Res.string.library_no_edits)
            filter == LibraryFilter.RECENT -> stringResource(Res.string.library_no_recent)
            else -> stringResource(Res.string.library_empty_view)
        },
        color = ImagoColors.TextSecondary,
        textAlign = TextAlign.Center,
    )
}

private fun formatMonth(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}.getOrDefault(value)
