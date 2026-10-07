package eu.studio742.imago.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.LocalImagoWindow
import eu.studio742.imago.core.designsystem.withWidth
import eu.studio742.imago.feature.account.AccountRoute
import eu.studio742.imago.feature.account.AccountSettingsCard
import eu.studio742.imago.feature.account.WelcomeAccountPrompt
import eu.studio742.imago.feature.composer.AddToCompositionSheet
import eu.studio742.imago.feature.composer.ComposerEditorRoute
import eu.studio742.imago.feature.composer.ComposerHubRoute
import eu.studio742.imago.feature.composer.ComposerMedia
import eu.studio742.imago.feature.composer.toComposerMedia
import eu.studio742.imago.feature.detail.DetailAsset
import eu.studio742.imago.feature.detail.DetailRoute
import eu.studio742.imago.feature.editor.EditorAsset
import eu.studio742.imago.feature.editor.EditorRoute
import eu.studio742.imago.feature.editor.RecipeLibraryRoute
import eu.studio742.imago.feature.library.AssetUiModel
import eu.studio742.imago.feature.library.DeviceMediaActionHost
import eu.studio742.imago.feature.library.LibraryNavDestination
import eu.studio742.imago.feature.library.LibraryRoute
import eu.studio742.imago.feature.library.LibrarySection
import eu.studio742.imago.feature.library.LibrarySettingsRoute
import eu.studio742.imago.feature.library.WelcomeRoute

/**
 * Where the app is, and the chosen library.
 *
 * The destination lives here, not in a `remember` in the composition: rotating the screen destroys
 * and recreates the activity on Android, and with it everything the composition held in
 * `remember` — whoever was on a photo was sent back to the library on every rotation.
 *
 * It does not survive process death, on purpose: [AppDestination.Photo] carries the list of photos
 * loaded by the library, which can have thousands of entries. Writing it to the saved state would
 * exceed the Binder transaction limit; the cost of losing it on a cold start is far smaller than
 * the app crashing.
 */
open class ShellViewModel(private val configuration: ConfigurationRepository) : ViewModel() {
    val selectedLibrary = configuration.selectedLibraryId

    private var lastLibraryId = selectedLibrary.value

    internal fun libraryChanged(id: String) {
        if (id != lastLibraryId && destinationState.value is AppDestination.Photo) destinationState.value = AppDestination.Library()
        lastLibraryId = id
    }

    /** Whether the first launch is done; until it is, the account goes back to the welcome. */
    internal val welcomeCompleted = configuration.welcomeCompleted

    internal val destinationState: MutableState<AppDestination> = mutableStateOf(
        if (welcomeCompleted.value) AppDestination.Library() else AppDestination.Welcome,
    )

    /** The batch waiting for a composition, while the picker sheet is open. */
    internal val composerRequestState: MutableState<ComposerRequest?> = mutableStateOf(null)

    /**
     * What changed since the version last seen, shown once after an update; empty when there is
     * nothing to tell. With nothing to tell, the installed version is recorded right away — a new
     * install included — so that the next update knows where it came from.
     */
    internal val newsState: MutableState<List<Release>> = mutableStateOf(
        unseenReleases(configuration.lastSeenAppVersion, welcomeCompleted.value).also { unseen ->
            if (unseen.isEmpty()) configuration.lastSeenAppVersion = InstalledVersion.toString()
        },
    )

    internal fun newsSeen() {
        configuration.lastSeenAppVersion = InstalledVersion.toString()
        newsState.value = emptyList()
    }

    /** An account link (confirmation, recovery) opens the account, where the outcome is shown. */
    fun openAccount() { destinationState.value = AppDestination.Account() }
}

/** This platform's [ShellViewModel]: through Hilt on Android, through the app's data layer on desktop. */
@Composable
expect fun shellViewModel(): ShellViewModel

/**
 * Photos looking for a composition.
 *
 * [onAdded] is what settles things on the sender's side — the library drops its selection — and
 * it only runs once they are really in: giving up halfway leaves the screen as it was.
 */
data class ComposerRequest(
    val media: List<ComposerMedia>,
    val onAdded: () -> Unit = {},
)

/**
 * Where the app is.
 *
 * The library opens the detail and the detail opens the editor — all three share the same list of
 * photos and the same index, so that swiping from one to the next does not lose the position in
 * any of them.
 */
sealed interface AppDestination {
    /** The first launch: the device only, or an Immich server. */
    data object Welcome : AppDestination

    /**
     * The library, and where it has to be when it reappears.
     *
     * `focusAssetId` is the photo that was being viewed. On a narrow screen the grid leaves the
     * composition while the detail is open, and its scroll position with it: without this, going
     * back always jumped to the top and forced scrolling again to where one was.
     */
    data class Library(
        val focusAssetId: String? = null,
        /** The view requested by whoever navigated here, or null to leave the library where it was. */
        val section: LibrarySection? = null,
    ) : AppDestination
    data object Settings : AppDestination
    data class Account(val signUp: Boolean = false) : AppDestination
    data object Recipes : AppDestination
    data object Compositions : AppDestination
    data class Composer(val projectId: String) : AppDestination
    data class Photo(
        val assets: List<AssetUiModel>,
        val index: Int,
        val editing: Boolean,
    ) : AppDestination
}

/**
 * The whole app: where it is, and what that shows.
 *
 * It is the same body in both apps — the Android activity and the Windows window compose this and
 * nothing else. What changes per platform is the [ShellViewModel], which wires the features to each
 * side's data layer.
 */
@Composable
fun ImagoApp(
    viewModel: ShellViewModel = shellViewModel(),
    /** The sync state for the indicator; null in an app without an account. */
    syncIndicator: kotlinx.coroutines.flow.Flow<eu.studio742.imago.core.designsystem.SyncIndicatorState?>? = null,
) {
    val source = androidx.compose.runtime.remember(syncIndicator) {
        syncIndicator?.let { eu.studio742.imago.core.designsystem.SyncIndicatorSource(it) { viewModel.openAccount() } }
    }
    CompositionLocalProvider(eu.studio742.imago.core.designsystem.LocalSyncIndicator provides source) {
        ImagoAppContent(viewModel)
    }
}

@Composable
private fun ImagoAppContent(viewModel: ShellViewModel) {
    // The state lives in the ViewModel to survive rotation; this is just the delegate that reads and writes it.
    var destination by viewModel.destinationState
    val selectedLibrary by viewModel.selectedLibrary.collectAsStateWithLifecycle()
    LaunchedEffect(selectedLibrary) {
        viewModel.libraryChanged(selectedLibrary)
    }
    eu.studio742.imago.feature.library.DeviceMediaActionHost()
    var composerRequest by viewModel.composerRequestState
    val window = LocalImagoWindow.current
    val current = destination
    val libraryState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // With width to spare, the photo opens beside the grid instead of over it. Editing is the
    // exception: the editor still wants the whole screen until it has a side rail of its own.
    val sideBySide = window.prefersSidePanel && current is AppDestination.Photo && !current.editing

    when {
        // A single condition, not two branches, because `LibraryPane` must always be the same
        // call: two different places in the composition would be two different grids, and opening
        // a photo would send the scroll back to the top.
        current is AppDestination.Library || sideBySide -> libraryState.SaveableStateProvider("library") {
            LibraryPane(
                photo = current as? AppDestination.Photo,
                focusAssetId = (current as? AppDestination.Library)?.focusAssetId,
                // Only what still belongs to the library is cleared, and only the fulfilled field:
                // something else may have opened between the request and its fulfilment, and
                // rewriting the whole destination here would undo it — or erase the other request
                // before it was ever read.
                onFocusConsumed = {
                    (destination as? AppDestination.Library)?.let {
                        destination = it.copy(focusAssetId = null)
                    }
                },
                section = (current as? AppDestination.Library)?.section,
                onSectionConsumed = {
                    (destination as? AppDestination.Library)?.let {
                        destination = it.copy(section = null)
                    }
                },
                onDestination = { destination = it },
                onComposerRequest = { composerRequest = it },
            )
        }

        current is AppDestination.Welcome -> WelcomeRoute(
            onFinished = { destination = AppDestination.Library() },
            accountPrompt = { WelcomeAccountPrompt(onOpenAccount = { signUp -> destination = AppDestination.Account(signUp) }) },
        )

        current is AppDestination.Settings -> LibrarySettingsRoute(
            onBack = { destination = AppDestination.Library() },
            accountSection = { AccountSettingsCard(onOpenAccount = { signUp -> destination = AppDestination.Account(signUp) }) },
            aboutSection = { AboutSettingsCard() },
        )

        current is AppDestination.Account -> AccountRoute(
            startWithSignUp = current.signUp,
            // We came from the welcome — or from an account link before it finished — and that is
            // where we go back to: the library choice is still to be made.
            onBack = {
                destination = if (viewModel.welcomeCompleted.value) AppDestination.Settings else AppDestination.Welcome
            },
        )

        current is AppDestination.Recipes -> RecipeLibraryRoute(
            onBack = { destination = AppDestination.Library() },
            onNavigate = { destination = it.toAppDestination() },
            onOpenSettings = { destination = AppDestination.Settings },
        )

        current is AppDestination.Compositions -> ComposerHubRoute(
            onBack = { destination = AppDestination.Library() },
            onOpenProject = { projectId -> destination = AppDestination.Composer(projectId) },
            onNavigate = { destination = it.toAppDestination() },
            onOpenSettings = { destination = AppDestination.Settings },
        )

        current is AppDestination.Composer -> ComposerEditorRoute(
            projectId = current.projectId,
            onBack = { destination = AppDestination.Compositions },
        )

        current is AppDestination.Photo -> if (current.editing) {
            // The editor strip only has photos: videos are in the list because the library shows
            // them, but swiping to one inside the editor would have nothing to open.
            val editable = current.assets.filterNot(AssetUiModel::isVideo)
            val editableIndex = editable
                .indexOfFirst { it.id == current.assets[current.index].id }
                .coerceAtLeast(0)
            if (editable.isEmpty()) {
                LaunchedEffect(current) { destination = current.copy(editing = false) }
            } else {
                EditorRoute(
                    asset = editable[editableIndex].toEditorAsset(),
                    assets = editable.map(AssetUiModel::toEditorAsset),
                    selectedIndex = editableIndex,
                    onSelectIndex = { index ->
                        val target = editable.getOrNull(index) ?: return@EditorRoute
                        destination = current.copy(
                            index = current.assets.indexOfFirst { it.id == target.id }.coerceAtLeast(0),
                        )
                    },
                    // Going back from the editor returns to the detail of the same photo, not to
                    // the library: that is where we came from.
                    onBack = { destination = current.copy(editing = false) },
                )
            }
        } else {
            // Narrow screen: the detail takes all of it.
            PhotoDetail(
                current = current,
                onDestination = { destination = it },
                onComposerRequest = { composerRequest = it },
            )
        }
    }

    val news by viewModel.newsState
    if (news.isNotEmpty()) WhatsNewDialog(news, onDismiss = viewModel::newsSeen)

    // The sheet is composed here, not inside each screen, because the library and the detail make
    // the same request of it and it is at this level that we know where to go next: choosing the
    // composition always means opening it too.
    composerRequest?.let { request ->
        AddToCompositionSheet(
            media = request.media,
            onDismiss = { composerRequest = null },
            onOpenProject = { projectId ->
                request.onAdded()
                composerRequest = null
                destination = AppDestination.Composer(projectId)
            },
        )
    }
}

/** The fraction of the window the grid takes when it shares the screen with the detail. */
private const val LIST_PANE_FRACTION = 0.38f
private val ListPaneMin = 320.dp
private val ListPaneMax = 520.dp

/**
 * The library, with the detail beside it when a photo is open and there is width for it.
 *
 * The grid is always composed in the same place, with or without the detail: the `Modifier`
 * changes, not the call. Switching branches here would lose the scroll position every time a tile
 * was tapped, which is the opposite of what a side-by-side pane exists to give.
 */
@Composable
private fun LibraryPane(
    photo: AppDestination.Photo?,
    focusAssetId: String?,
    onFocusConsumed: () -> Unit,
    section: LibrarySection?,
    onSectionConsumed: () -> Unit,
    onDestination: (AppDestination) -> Unit,
    onComposerRequest: (ComposerRequest) -> Unit,
) {
    val window = LocalImagoWindow.current
    val listPaneWidth = (window.width * LIST_PANE_FRACTION).coerceIn(ListPaneMin, ListPaneMax)
    Row(Modifier.fillMaxSize()) {
        val listModifier = if (photo != null) Modifier.width(listPaneWidth) else Modifier.weight(1f)
        // Inside the pane, "the window" becomes the pane. Without this the grid would count its
        // columns by the tablet's width and squeeze six thumbnails into a 480dp pane.
        CompositionLocalProvider(
            LocalImagoWindow provides if (photo != null) window.withWidth(listPaneWidth) else window,
        ) {
            LibraryRoute(
                modifier = listModifier,
                selectedAssetId = photo?.let { it.assets.getOrNull(it.index)?.id },
                focusAssetId = focusAssetId,
                onFocusConsumed = onFocusConsumed,
                section = section,
                onSectionConsumed = onSectionConsumed,
                onOpenAsset = { selected, loadedAssets ->
                    val assets = loadedAssets.distinctBy(AssetUiModel::id).ifEmpty { listOf(selected) }
                    onDestination(
                        AppDestination.Photo(
                            assets = assets,
                            index = assets.indexOfFirst { it.id == selected.id }.coerceAtLeast(0),
                            editing = false,
                        ),
                    )
                },
                onOpenSettings = { onDestination(AppDestination.Settings) },
                onOpenRecipes = { onDestination(AppDestination.Recipes) },
                onOpenComposer = { onDestination(AppDestination.Compositions) },
                onComposeSelection = { selected, onAdded ->
                    onComposerRequest(
                        ComposerRequest(selected.map(AssetUiModel::toComposerMedia), onAdded),
                    )
                },
            )
        }
        if (photo != null) {
            Box(Modifier.fillMaxHeight().width(1.dp).background(ImagoColors.BorderSubtle))
            PhotoDetail(photo, onDestination, onComposerRequest, Modifier.weight(1f))
        }
    }
}

/**
 * The detail of a photo, full screen or inside a pane.
 *
 * Both views share this body because they share the decisions: which photo comes next after a
 * delete, where "back" goes, what the edit button does.
 */
@Composable
private fun PhotoDetail(
    current: AppDestination.Photo,
    onDestination: (AppDestination) -> Unit,
    onComposerRequest: (ComposerRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    DetailRoute(
        modifier = modifier,
        assets = current.assets.map(AssetUiModel::toDetailAsset),
        selectedIndex = current.index,
        onSelectIndex = { index ->
            onDestination(current.copy(index = index.coerceIn(0, current.assets.lastIndex)))
        },
        // Back returns to the grid centred on this photo, not to its top.
        onBack = {
            onDestination(AppDestination.Library(current.assets.getOrNull(current.index)?.id))
        },
        onEdit = { onDestination(current.copy(editing = true)) },
        onAddToComposition = {
            current.assets.getOrNull(current.index)?.let {
                onComposerRequest(ComposerRequest(listOf(it.toComposerMedia())))
            }
        },
        onDeleted = { deletedId ->
            // The photo no longer exists: it leaves the list and the screen moves to the next one.
            // If it was the only one, there is no detail left to show.
            val remaining = current.assets.filterNot { it.id == deletedId }
            onDestination(
                if (remaining.isEmpty()) {
                    AppDestination.Library()
                } else {
                    current.copy(
                        assets = remaining,
                        index = current.index.coerceIn(0, remaining.lastIndex),
                    )
                },
            )
        },
    )
}

private fun AssetUiModel.toEditorAsset() = EditorAsset(
    id = id,
    checksum = checksum,
    fileName = fileName,
    previewUrl = previewUrl,
    apiKey = apiKey,
    fileCreatedAt = fileCreatedAt,
)

private fun AssetUiModel.toDetailAsset() = DetailAsset(
    id = id,
    checksum = checksum,
    fileName = fileName,
    thumbnailUrl = thumbnailUrl,
    previewUrl = previewUrl,
    apiKey = apiKey,
    fileCreatedAt = fileCreatedAt,
    date = date,
    isFavorite = isFavorite,
    isVideo = isVideo,
    durationMs = durationMs,
    videoUrl = videoUrl,
    recipe = recipe,
)

/** Where each destination of the bottom bar takes the app. */
private fun LibraryNavDestination.toAppDestination(): AppDestination = when (this) {
    LibraryNavDestination.TIMELINE -> AppDestination.Library(section = LibrarySection.TIMELINE)
    LibraryNavDestination.ALBUMS -> AppDestination.Library(section = LibrarySection.ALBUMS)
    LibraryNavDestination.RECIPES -> AppDestination.Recipes
    LibraryNavDestination.COMPOSER -> AppDestination.Compositions
}
