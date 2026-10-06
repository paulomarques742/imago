@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.pluralStringResource
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.core.designsystem.i18n.uiText
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import eu.studio742.imago.core.render.libraryAuth
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.outlined.AlignHorizontalRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlignHorizontalCenter
import androidx.compose.material.icons.outlined.AlignVerticalBottom
import androidx.compose.material.icons.outlined.AlignVerticalCenter
import androidx.compose.material.icons.outlined.AlignVerticalTop
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.FrameStyle
import eu.studio742.imago.core.composition.MAX_BACKGROUND_BLUR
import eu.studio742.imago.core.composition.MAX_PAGE_DURATION_MS
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.backgroundBlurDivisor
import eu.studio742.imago.core.composition.PlacementCrop
import eu.studio742.imago.core.composition.ShapeKind
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.composition.StrokePoint
import eu.studio742.imago.core.composition.hasVariablePressure
import eu.studio742.imago.core.composition.strokeWidthAt
import eu.studio742.imago.core.designsystem.AutoSaveStatus
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import eu.studio742.imago.core.designsystem.ImagoColors
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.CompositionLocalProvider
import eu.studio742.imago.core.designsystem.FocusedAdjustmentScale
import eu.studio742.imago.core.designsystem.ImagoMotion
import eu.studio742.imago.core.designsystem.ImagoParameterSlider
import eu.studio742.imago.core.designsystem.imagoGlassBlur
import eu.studio742.imago.core.designsystem.imagoTween
import eu.studio742.imago.feature.library.AssetUiModel
import eu.studio742.imago.feature.library.LibraryPickerRoute
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSlider
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.LocalImagoWindow
import eu.studio742.imago.core.designsystem.SheetHandle
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.render.editedAspectRatio
import eu.studio742.imago.feature.editor.EditorAsset
import eu.studio742.imago.feature.editor.EditorRoute
import eu.studio742.imago.feature.editor.PhotoEditTarget

@Composable
fun ComposerEditorRoute(
    projectId: String,
    onBack: () -> Unit,
    viewModel: ComposerEditorViewModel = composerEditorViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalPlatformContext.current
    // Saveable because it is navigation, not decoration: losing it on a rotation closed the photo
    // editor and sent the user back to the stage without them asking for anything.
    var editingPhotoId by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(projectId) {
        viewModel.open(projectId)
        onDispose(viewModel::saveNow)
    }
    // Disabled while the photo editor is on top: otherwise "back" closed the whole composer instead of
    // closing the editor, and its `BackHandler` never got to see the event.
    BackHandler(enabled = editingPhotoId == null) { viewModel.saveNow(); onBack() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it.resolveNow()); viewModel.consumeNotice() } }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it.resolveNow()); viewModel.consumeError() } }
    val shareExports = rememberShareExports()
    LaunchedEffect(state.exportedUris) {
        if (state.exportedUris.isNotEmpty()) {
            shareExports(state.exportedUris, state.exportedMimeTypes)
            viewModel.consumeExportedUris()
        }
    }
    // The box is what guarantees the media picker really covers the composer: it is composed beside
    // the stage, and without a declared stacker it would be at the mercy of how the caller arranges
    // the children this screen emits.
    Box(Modifier.fillMaxSize()) {
        ComposerEditorScreen(state, snackbar, onBack, viewModel, editingPhotoId) { editingPhotoId = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerEditorScreen(
    state: ComposerEditorUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    actions: ComposerEditorViewModel,
    editingPhotoId: String?,
    onEditPhotoId: (String?) -> Unit,
) {
    // With room, the composer stops stacking everything at the bottom of the screen: the structure
    // goes to the left, the properties to the right and the stage keeps the middle. It is the only
    // layout where what is being changed is seen while changing it.
    val composerWindow = LocalImagoWindow.current
    val inspectorRail = composerWindow.prefersSidePanel
    val layersRail = inspectorRail && composerWindow.width >= LayersPaneMinWindow
    var mediaPicker by remember { mutableStateOf<MediaPickerTarget?>(null) }
    var pageTools by rememberSaveable { mutableStateOf(false) }
    // `drawing`, `positioningBackground` and `multiSelect` stay in `remember`: they are armed gesture
    // modes, and resuming one of them after a rotation — with the stage already another size — would
    // hand the finger back to a canvas that changed under it.
    var drawing by remember { mutableStateOf(false) }
    var drawingKind by remember { mutableStateOf(StrokeKind.PEN) }
    var positioningBackground by remember { mutableStateOf(false) }
    // The twin of the one above, on the elements' side: framing the photo inside the mask its sizing
    // gave it.
    var positioningMedia by remember { mutableStateOf(false) }
    // When on, a tap on the stage adds to and removes from the selection instead of replacing it. It
    // turns off on its own when the selection empties: a mode left on with nothing chosen is a forgotten mode.
    var multiSelect by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Read here because the snackbar calls run in coroutines, outside the composition.
    val undoLabel = stringResource(Res.string.composer_undo_action)
    val textPlaceholder = stringResource(Res.string.composer_text_placeholder)
    // While the hand works, the interface gets out of the way. A slider being dragged fades the chrome
    // and leaves the panel transparent; an element being moved on the stage fades the whole panel. In
    // a side column none of this applies: there the page is beside it, never underneath, and fading
    // the column would make what the finger rests on disappear.
    val focusState = remember { ComposerFocusState() }
    val adjusting = focusState.focus != null && !inspectorRail
    val stageBusy = state.stageGesture && !inspectorRail
    val chromeAlpha by animateFloatAsState(
        targetValue = if (adjusting || stageBusy) 0f else 1f,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "composerChrome",
    )
    // The panel only disappears entirely with an element moving. While dragging a value it stays —
    // without a background, but there — because that is where the finger is.
    val panelAlpha by animateFloatAsState(
        targetValue = if (stageBusy) 0f else 1f,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "composerPanel",
    )
    // Measured by the bars themselves, and not estimated: the top one has the status bar inside and
    // the bottom one grows with the inspector open. The initial values are just a seed for the first
    // frame — without them the page opened at the whole screen's height and shrank in front of
    // whoever was looking, as soon as the measurement arrived.
    val chromeDensity = LocalDensity.current
    var measuredChrome by remember { mutableStateOf(ComposerStageInsets(top = 88.dp, bottom = 132.dp)) }
    // While dragging a value, the insets go to zero: the page grows and takes the whole screen, which
    // is what makes the transparent panel worth it. With an element under the finger, no — changing
    // the page's height mid-drag would pull the floor from under whoever is dragging it.
    val stageTop by animateDpAsState(
        targetValue = if (adjusting) 0.dp else measuredChrome.top,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "composerStageTop",
    )
    val stageBottom by animateDpAsState(
        targetValue = if (adjusting) 0.dp else measuredChrome.bottom,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "composerStageBottom",
    )
    // Entering drawing mode is always the same pair of states; written by hand on each button, it was
    // a line where missing `positioningBackground = false` left two gestures armed.
    val startDrawing: (StrokeKind) -> Unit = { kind ->
        drawingKind = kind
        drawing = true
        positioningBackground = false
        positioningMedia = false
    }
    // The two framing modes exclude each other: one moves the page background, the other the element,
    // and both want the same finger on the same stage.
    val toggleBackgroundPositioning = {
        positioningBackground = !positioningBackground
        if (positioningBackground) { drawing = false; positioningMedia = false }
    }
    val toggleMediaPositioning = {
        positioningMedia = !positioningMedia
        if (positioningMedia) { drawing = false; positioningBackground = false }
    }
    var templateDialog by rememberSaveable { mutableStateOf(false) }
    var textDialog by rememberSaveable { mutableStateOf(false) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var layersSheet by rememberSaveable { mutableStateOf(false) }
    var groupDialog by rememberSaveable { mutableStateOf(false) }
    var brandKitDialog by rememberSaveable { mutableStateOf(false) }
    var brandEditor by rememberSaveable { mutableStateOf(false) }
    val project = state.project
    // Leaving the background (selecting something else, or nothing) has to disarm position mode —
    // otherwise the gesture stayed ready to move an image no longer in view in the inspector.
    LaunchedEffect(state.backgroundSelected) {
        if (!state.backgroundSelected) positioningBackground = false
    }
    LaunchedEffect(state.selectedElementIds.isEmpty()) {
        if (state.selectedElementIds.isEmpty()) multiSelect = false
    }
    // Switching element — or choosing more than one — disarms framing, for the same reason leaving the
    // background disarms its own: a mode armed over something else is a forgotten mode.
    LaunchedEffect(state.selectedElementId, state.selectedElementIds.size) {
        val target = project?.elements?.firstOrNull { it.id == state.selectedElementId }
        if (target?.placementCrop() == null || target.locked || state.selectedElementIds.size > 1) {
            positioningMedia = false
        }
    }
    val editingPhoto = project?.elements?.firstOrNull { it.id == editingPhotoId } as? CompositionElement.Photo
    if (editingPhoto != null) {
        val asset = EditorAsset(
            id = editingPhoto.media.assetId,
            checksum = editingPhoto.media.checksum,
            fileName = editingPhoto.media.fileName,
            previewUrl = actions.previewUrl(editingPhoto.media.assetId),
            apiKey = actions.apiKey(editingPhoto.media.assetId),
            fileCreatedAt = project.createdAt,
        )
        EditorRoute(
            asset = asset,
            target = PhotoEditTarget.Composition(asset, editingPhoto.recipe.recipe),
            onCompositionCommit = actions::updateSelectedPhotoRecipe,
            onBack = { onEditPhotoId(null) },
        )
        return
    }
    // The physical keyboard stops being decoration on a tablet with a cover. Focus is requested once so
    // that someone receives the keys; from then on events bubble up from any child to here, which
    // means a focused text box keeps receiving Delete and the arrows first.
    val keyboardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyboardFocus.requestFocus() } }
    // Every slider in the composer talks to the same state: it tells the screen which adjustment is
    // under the finger, and so it has to wrap the whole Scaffold — the top bar, the bottom panel and
    // the inspector column are all inside it.
    CompositionLocalProvider(LocalComposerFocus provides focusState) {
        Scaffold(
            modifier = Modifier
                .focusRequester(keyboardFocus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val hasSelection = state.selectedElementId != null
                    val step = if (event.isShiftPressed) NUDGE_STEP_COARSE else NUDGE_STEP
                    when {
                        event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> {
                            actions.redo(); true
                        }
                        event.isCtrlPressed && event.key == Key.Z -> { actions.undo(); true }
                        event.isCtrlPressed && event.key == Key.Y -> { actions.redo(); true }
                        !hasSelection -> false
                        event.key == Key.Delete || event.key == Key.Backspace -> {
                            actions.deleteSelected(); true
                        }
                        event.key == Key.DirectionLeft -> { actions.moveSelected(-step, 0f); true }
                        event.key == Key.DirectionRight -> { actions.moveSelected(step, 0f); true }
                        event.key == Key.DirectionUp -> { actions.moveSelected(0f, -step); true }
                        event.key == Key.DirectionDown -> { actions.moveSelected(0f, step); true }
                        else -> false
                    }
                },
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // The same bar as the photo editor: the same component, the same height, the same
                // status bar insets and the same content colours. It was a hand-made row with a fixed
                // 60dp, and moving from one editor to the other made the header jump in height.
                //
                // It fades, and does not shrink: taking it out of place would change the stage's
                // height mid-gesture, and the page would run away from the finger dragging it.
                TopAppBar(
                    modifier = Modifier.alpha(chromeAlpha).onSizeChanged { measured ->
                        val height = with(chromeDensity) { measured.height.toDp() }
                        if (height > 0.dp && height != measuredChrome.top) {
                            measuredChrome = measuredChrome.copy(top = height)
                        }
                    },
                    windowInsets = WindowInsets.statusBars,
                    title = {
                        Text(
                            project?.name ?: stringResource(Res.string.composer_composition),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { actions.saveNow(); onBack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.composer_back))
                        }
                    },
                    actions = {
                        AutoSaveStatus(
                            isSaving = state.isSaving,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        IconButton(onClick = actions::undo, enabled = state.canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(Res.string.composer_undo))
                        }
                        IconButton(onClick = actions::redo, enabled = state.canRedo) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(Res.string.composer_redo))
                        }
                        IconButton(onClick = { templateDialog = true }) {
                            Icon(Icons.Outlined.Save, stringResource(Res.string.composer_save_as_template))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ImagoColors.Surface,
                        titleContentColor = ImagoColors.TextPrimary,
                        navigationIconContentColor = ImagoColors.TextPrimary,
                        actionIconContentColor = ImagoColors.TextPrimary,
                    ),
                )
            },
            bottomBar = {
                ComposerPanelSurface(
                immersive = adjusting || stageBusy,
                contentAlpha = panelAlpha,
                onHeightChanged = { height ->
                    if (height != measuredChrome.bottom) measuredChrome = measuredChrome.copy(bottom = height)
                },
            ) {
                    // With the column mounted, the inspector lives there; here it would say the same
                    // thing twice and steal height from the stage.
                    if (!inspectorRail) {
                        ComposerInspector(
                            state = state,
                            actions = actions,
                            rail = false,
                            multiSelect = multiSelect,
                            positioningBackground = positioningBackground,
                            positioningMedia = positioningMedia,
                            onToggleMultiSelect = { multiSelect = !multiSelect },
                            onTogglePositioning = toggleBackgroundPositioning,
                            onTogglePositioningMedia = toggleMediaPositioning,
                            onPickImage = { currentPageOnly -> mediaPicker = MediaPickerTarget.Background(currentPageOnly) },
                            onEditText = { textDialog = true },
                            onEditPhoto = { onEditPhotoId(state.selectedElementId) },
                            onRequestGroup = { groupDialog = true },
                            onDeleted = { label ->
                                scope.launch {
                                    val result = snackbar.showSnackbar(
                                        message = label,
                                        actionLabel = undoLabel,
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) actions.undo()
                                }
                            },
                        )
                    }
                    // Two halves separated by the hairline: on the left what is inserted into the page,
                    // on the right what is done to the document. Mixed, "Export" came after "Circle"
                    // and the row read like a bag of buttons.
                    Row(
                        Modifier.fillMaxWidth().alpha(chromeAlpha).horizontalScroll(rememberScrollState())
                            .padding(horizontal = ImagoSpacing.Xs, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ToolButton(stringResource(Res.string.composer_media), Icons.Outlined.Image) {
                            mediaPicker = MediaPickerTarget.Element(
                                project?.elements?.firstOrNull { it.id == state.selectedElementId }
                                    ?.takeIf { it is CompositionElement.Photo || it is CompositionElement.Video || it is CompositionElement.MediaPlaceholder }
                                    ?.id,
                            )
                        }
                        ToolButton(stringResource(Res.string.composer_text), Icons.Outlined.TextFields, onClick = { actions.addText(textPlaceholder) })
                        ToolMenuButton(stringResource(Res.string.composer_shape), Icons.Outlined.Rectangle) { dismiss ->
                            ToolMenuItem(stringResource(Res.string.composer_rectangle), Icons.Outlined.Rectangle, dismiss) { actions.addShape(ShapeKind.RECTANGLE) }
                            ToolMenuItem(stringResource(Res.string.composer_circle), Icons.Outlined.Circle, dismiss) { actions.addShape(ShapeKind.ELLIPSE) }
                            ToolMenuItem(stringResource(Res.string.composer_arrow), Icons.AutoMirrored.Outlined.ArrowForward, dismiss) { actions.addShape(ShapeKind.ARROW) }
                            ToolMenuItem(stringResource(Res.string.composer_line), Icons.Outlined.Remove, dismiss) { actions.addShape(ShapeKind.LINE) }
                        }
                        ToolMenuButton(stringResource(Res.string.composer_drawing), Icons.Outlined.Brush) { dismiss ->
                            ToolMenuItem(stringResource(Res.string.composer_pen), Icons.Outlined.Brush, dismiss) { startDrawing(StrokeKind.PEN) }
                            ToolMenuItem(stringResource(Res.string.composer_marker), Icons.Outlined.Brush, dismiss) { startDrawing(StrokeKind.MARKER) }
                            ToolMenuItem(stringResource(Res.string.composer_eraser), Icons.Outlined.Remove, dismiss) { startDrawing(StrokeKind.ERASER) }
                        }
                        ToolGroupDivider()
                        // Only when the layers are not in view in a column of their own.
                        if (!layersRail) ToolButton(stringResource(Res.string.composer_layers), Icons.Outlined.Layers) { layersSheet = true }
                        ToolButton(stringResource(Res.string.composer_pages), Icons.Outlined.GridView, active = pageTools) { pageTools = !pageTools }
                        ToolButton(stringResource(Res.string.composer_background), Icons.Outlined.Wallpaper, active = state.backgroundSelected, onClick = actions::selectBackground)
                        ToolButton(stringResource(Res.string.composer_brand), Icons.Outlined.Palette) { brandKitDialog = true }
                        // Since Android 10 saving to the gallery through MediaStore asks for no
                        // permission, and `minSdk` is 31 — the WRITE_EXTERNAL_STORAGE request that was
                        // here was always skipped.
                        ToolButton(stringResource(Res.string.composer_export), Icons.Outlined.Download) { exportDialog = true }
                        if (project?.elements?.any { it is CompositionElement.Video && it.visible && it.pageIndex == state.currentPage } == true) {
                            ToolButton(stringResource(Res.string.composer_preview), Icons.Outlined.PlayArrow, onClick = actions::previewCurrentPage)
                        }
                    }
                    if (pageTools && project != null) {
                        PageControls(state, actions) { label ->
                            scope.launch {
                                val result = snackbar.showSnackbar(label, undoLabel, withDismissAction = true)
                                if (result == SnackbarResult.ActionPerformed) actions.undo()
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                project == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(Res.string.composer_unavailable)) }
                // A single call to the stage, with or without columns: it keeps the zoom and the scroll
                // position in `remember`, and splitting it into two branches put the document back at
                // the start every time a column appeared.
                // The stage does not take the Scaffold's inset: it draws edge to edge, under the bars,
                // and `stageInsets` tells it where to settle the page. The side columns take it,
                // because there the bars really are on top of them.
                else -> Row(Modifier.fillMaxSize()) {
                    val railPadding = Modifier.padding(
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding(),
                    )
                    if (layersRail) {
                        LayersPanel(
                            project = project,
                            state = state,
                            actions = actions,
                            onSelectBackground = actions::selectBackground,
                            modifier = railPadding
                                .width(LayersPaneWidth)
                                .fillMaxHeight()
                                .background(ImagoColors.Surface),
                        )
                        ComposerPaneDivider()
                    }
                    ComposerCanvas(
                        project = project,
                        state = state,
                        drawing = drawing,
                        drawingKind = drawingKind,
                        onCancelDrawing = { drawing = false },
                        multiSelect = multiSelect,
                        positioningBackground = positioningBackground,
                        positioningMedia = positioningMedia,
                        previewUrl = actions::previewUrl,
                        thumbnailUrl = actions::thumbnailUrl,
                        videoPlaybackUrl = actions::videoPlaybackUrl,
                        apiKey = actions::apiKey,
                        actions = actions,
                        onDrawing = {
                            if (drawingKind == StrokeKind.ERASER) actions.eraseDrawing(it) else actions.addDrawing(it, drawingKind)
                            drawing = false
                        },
                        onPickMediaFor = { mediaPicker = MediaPickerTarget.Element(it) },
                        onFrameMedia = { if (!positioningMedia) toggleMediaPositioning() },
                        stageInsets = ComposerStageInsets(top = stageTop, bottom = stageBottom),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    if (inspectorRail) {
                        ComposerPaneDivider()
                        Column(
                            railPadding
                                .width(InspectorPaneWidth)
                                .fillMaxHeight()
                                .background(ImagoColors.Surface)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            ComposerInspector(
                                state = state,
                                actions = actions,
                                rail = true,
                                multiSelect = multiSelect,
                                positioningBackground = positioningBackground,
                                positioningMedia = positioningMedia,
                                onToggleMultiSelect = { multiSelect = !multiSelect },
                                onTogglePositioning = toggleBackgroundPositioning,
                                onTogglePositioningMedia = toggleMediaPositioning,
                                onPickImage = { currentPageOnly -> mediaPicker = MediaPickerTarget.Background(currentPageOnly) },
                                onEditText = { textDialog = true },
                                onEditPhoto = { onEditPhotoId(state.selectedElementId) },
                                onRequestGroup = { groupDialog = true },
                                onDeleted = { label ->
                                    scope.launch {
                                        val result = snackbar.showSnackbar(
                                            message = label,
                                            actionLabel = undoLabel,
                                            withDismissAction = true,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) actions.undo()
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // With a value under the finger only this stays over the page — the adjustment's name, the
            // value and the scale. The same ruler as the photo editor, for the same reason.
            AnimatedVisibility(
                visible = adjusting,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ImagoSpacing.Xxl),
                enter = fadeIn(imagoTween(ImagoMotion.Instant)),
                exit = fadeOut(imagoTween(ImagoMotion.Instant)),
            ) {
                focusState.focus?.let { focus ->
                    FocusedAdjustmentScale(
                        label = focus.label,
                        valueText = focus.value.formatOne(),
                        value = focus.value,
                        range = focus.range,
                        formatBound = { it.formatOne() },
                    )
                }
            }
            }
        }
    }
    mediaPicker?.let { target ->
        ComposerMediaPicker(
            target = target,
            onDismiss = { mediaPicker = null },
            onPicked = { chosen ->
                when (target) {
                    is MediaPickerTarget.Element -> actions.addMedia(chosen, target.replaceId)
                    is MediaPickerTarget.Background ->
                        chosen.firstOrNull()?.let { actions.setPhotoBackground(it, target.currentPageOnly) }
                }
                mediaPicker = null
            },
        )
    }
    // The sheet only exists when the layers have no column. Without this guard, rotating the tablet
    // with the sheet open left it over the column that now shows exactly the same.
    if (layersSheet && !layersRail && project != null) {
        LayersSheet(project, state, actions) { layersSheet = false }
    }
    if (brandKitDialog) BrandKitDialog(state, actions, onEditBrand = { brandEditor = true }) { brandKitDialog = false }
    if (brandEditor) BrandKitOverlay(onClose = { brandEditor = false })
    if (templateDialog) TemplateDialog(
        onDismiss = { templateDialog = false },
        onSave = { name, include -> actions.saveAsTemplate(name, include); templateDialog = false },
    )
    if (exportDialog) ExportDialog(
        onDismiss = { exportDialog = false },
        onExport = { format, currentOnly, sendToImmich ->
            exportDialog = false
            actions.export(format, currentOnly, sendToImmich)
        },
    )
    if (state.isExporting) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.composer_exporting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(
                    progress = { if (state.exportTotal == 0) 0f else state.exportCompleted.toFloat() / state.exportTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(pluralStringResource(Res.plurals.composer_export_progress, state.exportTotal, state.exportCompleted, state.exportTotal))
            }
        },
        confirmButton = { TextButton(onClick = actions::cancelExport) { Text(stringResource(Res.string.composer_cancel)) } },
    )
    if (state.isGeneratingProxy) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.composer_preparing_preview)) },
        text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        confirmButton = {},
    )
    state.previewProxyUri?.let { path ->
        AlertDialog(
            onDismissRequest = actions::consumePreview,
            title = { Text(stringResource(Res.string.composer_page_preview)) },
            text = { ProxyVideoPlayer(path, Modifier.fillMaxWidth().height(420.dp)) },
            confirmButton = { TextButton(onClick = actions::consumePreview) { Text(stringResource(Res.string.composer_close)) } },
        )
    }
    val selectedForGroup = project?.elements?.firstOrNull { it.id == state.selectedElementId }
    if (groupDialog && project != null && selectedForGroup != null) AlertDialog(
        onDismissRequest = { groupDialog = false },
        title = { Text(stringResource(Res.string.composer_group_with)) },
        text = {
            LazyColumn {
                items(project.elements.filterNot { it.id == selectedForGroup.id }, key = { it.id }) { element ->
                    TextButton(
                        onClick = { actions.groupSelectedWith(element.id); groupDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(element.accessibilityLabel()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { groupDialog = false }) { Text(stringResource(Res.string.composer_cancel)) } },
    )
    val selectedText = project?.elements?.firstOrNull { it.id == state.selectedElementId } as? CompositionElement.Text
    if (textDialog && selectedText != null) {
        var value by remember(selectedText.id) { mutableStateOf(selectedText.text) }
        AlertDialog(
            onDismissRequest = { textDialog = false }, title = { Text(stringResource(Res.string.composer_edit_text)) },
            text = { TextField(value, { value = it }, minLines = 3) },
            confirmButton = { TextButton(onClick = { actions.updateSelectedText(value); textDialog = false }) { Text(stringResource(Res.string.composer_apply)) } },
            dismissButton = { TextButton(onClick = { textDialog = false }) { Text(stringResource(Res.string.composer_cancel)) } },
        )
    }
}

/** The hairline that separates a column from the stage. */
@Composable
private fun ComposerPaneDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(ImagoColors.BorderSubtle))
}

/**
 * The properties of what is chosen — the background, or the element.
 *
 * A single definition, mounted in two places: in the bottom bar when the screen is narrow, and in the
 * right column when there is width. What changes between the two is [rail], which opens the
 * adjustment drawers by default: collapsed they make sense in a bar that steals height from the
 * composition, but in a tall empty column they only hide what one came there to do.
 */
@Composable
private fun ComposerInspector(
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    rail: Boolean,
    multiSelect: Boolean,
    positioningBackground: Boolean,
    positioningMedia: Boolean,
    onToggleMultiSelect: () -> Unit,
    onTogglePositioning: () -> Unit,
    onTogglePositioningMedia: () -> Unit,
    onPickImage: (Boolean) -> Unit,
    onEditText: () -> Unit,
    onEditPhoto: () -> Unit,
    onRequestGroup: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    val selected = state.project?.elements?.firstOrNull { it.id == state.selectedElementId }
    when {
        state.backgroundSelected -> BackgroundInspector(
            state,
            actions,
            rail = rail,
            onPickImage = onPickImage,
            positioning = positioningBackground,
            onTogglePositioning = onTogglePositioning,
        )

        selected != null -> SelectedInspector(
            state,
            actions,
            rail = rail,
            multiSelect = multiSelect,
            positioning = positioningMedia,
            onToggleMultiSelect = onToggleMultiSelect,
            onTogglePositioning = onTogglePositioningMedia,
            onEditText = onEditText,
            onEditPhoto = onEditPhoto,
            onRequestGroup = onRequestGroup,
            onDeleted = onDeleted,
        )

        // With nothing chosen there are no properties to show. In a bottom bar that goes unnoticed —
        // it simply shrinks — but a blank 320dp column in the middle of the screen looks broken, and
        // so here it says what is left to do.
        rail -> Text(
            text = stringResource(Res.string.composer_inspector_hint),
            style = MaterialTheme.typography.bodySmall,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.padding(ImagoSpacing.Lg),
        )
    }
}


/**
 * The brand, to use in the composition: the colours and fonts apply to what is selected, and the
 * logos are inserted. Editing the brand is done in its editor — "Edit brand" opens it on top.
 */
@Composable
private fun BrandKitDialog(
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    onEditBrand: () -> Unit,
    onDismiss: () -> Unit,
) {
    val kit = state.brandKit ?: BrandKit(updatedAt = "")
    val selected = state.project?.elements?.firstOrNull { it.id == state.selectedElementId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.composer_brand)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (selected == null) stringResource(Res.string.composer_brand_tap_background) else stringResource(Res.string.composer_brand_tap_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextTertiary,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val applyBrandColor = stringResource(Res.string.composer_apply_brand_color)
                    kit.paletteArgb.forEach { argb ->
                        Box(
                            Modifier.size(42.dp).clip(CircleShape).background(Color(argb)).border(1.dp, Color.Gray, CircleShape)
                                .clickable { actions.applyBrandColor(argb) }
                                .semantics { contentDescription = applyBrandColor },
                        )
                    }
                }
                // Fonts only serve a text: otherwise they do not even appear, instead of buttons that do nothing.
                if (selected is CompositionElement.Text) {
                    listOf(stringResource(Res.string.composer_brand_primary_font) to kit.primaryFont, stringResource(Res.string.composer_brand_secondary_font) to kit.secondaryFont).forEach { (label, name) ->
                        val font = CompositionFonts.resolve(name)
                        TextButton(onClick = { actions.applyBrandFont(font.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "$label · ${font.displayName}",
                                fontFamily = rememberCompositionFontFamily(font.id, 400),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (selected is CompositionElement.Photo) {
                    TextButton(onClick = { actions.addSelectedPhotoAsLogo() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.composer_save_photo_as_logo), Modifier.fillMaxWidth())
                    }
                }
                kit.logos.forEach { logo ->
                    TextButton(onClick = { actions.addBrandLogo(logo); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.composer_insert_logo, logo.fileName), Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_close)) } },
        dismissButton = { TextButton(onClick = { onDismiss(); onEditBrand() }) { Text(stringResource(Res.string.composer_edit_brand)) } },
    )
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onExport: (StaticExportFormat, Boolean, String?) -> Unit,
) {
    var sendToImmich by remember { mutableStateOf(false) }
    var targetLibraryId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.composer_export)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.composer_export_hint))
                TextButton(onClick = { sendToImmich = !sendToImmich }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sendToImmich) stringResource(Res.string.composer_send_to_immich_on) else stringResource(Res.string.composer_send_to_immich))
                }
                if (sendToImmich) eu.studio742.imago.feature.library.ExportLibrarySelector(onSelected = { targetLibraryId = it })
                Button(enabled = !sendToImmich || targetLibraryId != null, onClick = { onExport(StaticExportFormat.JPEG, false, targetLibraryId.takeIf { sendToImmich }) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.composer_export_all_jpeg)) }
                Button(enabled = !sendToImmich || targetLibraryId != null, onClick = { onExport(StaticExportFormat.PNG, false, targetLibraryId.takeIf { sendToImmich }) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.composer_export_all_png)) }
                TextButton(enabled = !sendToImmich || targetLibraryId != null, onClick = { onExport(StaticExportFormat.JPEG, true, targetLibraryId.takeIf { sendToImmich }) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.composer_export_current_page)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_cancel)) } },
    )
}

/**
 * A pending colour request: the dialog's title, the colour it starts from, and where to put it.
 *
 * A single nullable state per inspector, instead of one boolean per button. There are six different
 * places asking for the same box — fill, shape outline, frame outline, text, and the two ends of the
 * gradient — and each of them with its own `var showDialog` was a guarantee that sooner or later two
 * would be open at the same time.
 */
internal data class PendingColor(val title: UiText, val initial: Long, val onPick: (Long) -> Unit)

/**
 * The background inspector, which appears in the same place as the elements' inspector.
 *
 * The background stopped being a separate dialog: it is a selectable layer like the others, and so
 * its controls live where the elements' do.
 */
@Composable
private fun BackgroundInspector(
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    rail: Boolean,
    onPickImage: (Boolean) -> Unit,
    positioning: Boolean,
    onTogglePositioning: () -> Unit,
) {
    val project = state.project ?: return
    val pageOverride = project.pages.getOrNull(state.currentPage)?.backgroundOverride
    val background = pageOverride ?: project.background
    var currentPageOnly by remember(state.currentPage) { mutableStateOf(pageOverride != null) }
    val hasSelectedPhoto = project.elements.firstOrNull { it.id == state.selectedElementId } is CompositionElement.Photo
    // Collapsed by default, as in the element inspector — the same space is worth more to the
    // composition than to a shadow that rarely changes. Not in a column: there the space comes from
    // nowhere, and a tall closed column is just empty space.
    var expanded by remember(rail) { mutableStateOf(rail) }
    var pendingColor by remember { mutableStateOf<PendingColor?>(null) }
    pendingColor?.let { pending ->
        ColorPickerDialog(
            title = pending.title.resolve(),
            initialArgb = pending.initial,
            palette = state.brandKit?.paletteArgb.orEmpty(),
            onDismiss = { pendingColor = null },
            onConfirm = pending.onPick,
        )
    }
    val chrome = inspectorChromeAlpha(rail)
    Column(Modifier.fillMaxWidth().padding(horizontal = ImagoSpacing.Sm)) {
        Row(Modifier.alpha(chrome), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Wallpaper, null, Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.Gold)
            Text(
                "  " + if (currentPageOnly) {
                    stringResource(Res.string.composer_background_page, state.currentPage + 1)
                } else {
                    stringResource(Res.string.composer_background_all)
                },
                style = MaterialTheme.typography.labelMedium,
                color = ImagoColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { currentPageOnly = !currentPageOnly }) {
                Text(if (currentPageOnly) stringResource(Res.string.composer_apply_to_all) else stringResource(Res.string.composer_this_page_only), fontSize = 11.sp)
            }
            IconButton(onClick = actions::clearSelection) { Icon(Icons.Outlined.Check, stringResource(Res.string.composer_close_background)) }
        }
        Row(
            Modifier.fillMaxWidth().alpha(chrome).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(stringResource(Res.string.composer_image), Icons.Outlined.Image) { onPickImage(currentPageOnly) }
            ToolButton(stringResource(Res.string.composer_dark), Icons.Outlined.Circle) { actions.setSolidBackground(0xFF111111, currentPageOnly) }
            ToolButton(stringResource(Res.string.composer_light), Icons.Outlined.Circle) { actions.setSolidBackground(0xFFF2EFE8, currentPageOnly) }
            ToolButton(stringResource(Res.string.composer_gradient), Icons.Outlined.Gradient) {
                actions.setGradientBackground(0xFF111111, 0xFFD4AF37, currentPageOnly)
            }
            when (background) {
                is CompositionBackground.Solid -> ToolButton(stringResource(Res.string.composer_color), Icons.Outlined.Palette) {
                    pendingColor = PendingColor(uiText(Res.string.composer_background_color), background.argb, actions::setBackgroundSolidColor)
                }
                is CompositionBackground.Gradient -> {
                    ToolButton(stringResource(Res.string.composer_start_color), Icons.Outlined.Palette) {
                        pendingColor = PendingColor(uiText(Res.string.composer_gradient_start), background.startArgb, actions::setBackgroundGradientStart)
                    }
                    ToolButton(stringResource(Res.string.composer_end_color), Icons.Outlined.Palette) {
                        pendingColor = PendingColor(uiText(Res.string.composer_gradient_end), background.endArgb, actions::setBackgroundGradientEnd)
                    }
                }
                is CompositionBackground.Photo -> Unit
            }
            if (hasSelectedPhoto) {
                ToolButton(stringResource(Res.string.composer_from_selection), Icons.Outlined.Image) { actions.useSelectedPhotoAsBackground(currentPageOnly) }
            }
            if (pageOverride != null) {
                ToolButton(stringResource(Res.string.composer_reset), Icons.Outlined.Delete, onClick = actions::clearCurrentPageBackground)
            }
            if (background is CompositionBackground.Photo) {
                // A mode of its own, and not one more slider: position is two-dimensional, and dragging
                // over the image itself is how a framing is adjusted — two X/Y sliders required looking
                // at two numbers instead of at the photo.
                ToolButton(
                    label = if (positioning) stringResource(Res.string.composer_positioning) else stringResource(Res.string.composer_position),
                    icon = if (positioning) Icons.Outlined.Check else Icons.Outlined.OpenWith,
                    active = positioning,
                    onClick = onTogglePositioning,
                )
            }
            ToolButton(
                label = if (expanded) stringResource(Res.string.composer_close) else stringResource(Res.string.composer_adjust),
                icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.Tune,
                active = expanded,
            ) { expanded = !expanded }
        }
        if (positioning && background is CompositionBackground.Photo) {
            Text(
                stringResource(Res.string.composer_background_position_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.Gold,
                modifier = Modifier.padding(top = ImagoSpacing.Xs),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(imagoTween(ImagoMotion.Fast)) + expandVertically(imagoTween(ImagoMotion.Fast)),
            exit = fadeOut(imagoTween(ImagoMotion.Instant)) + shrinkVertically(imagoTween(ImagoMotion.Instant)),
        ) {
            when (background) {
                is CompositionBackground.Photo -> Column {
                    InspectorSlider(stringResource(Res.string.composer_blur), background.blurRadius, 0f..MAX_BACKGROUND_BLUR, 0f, actions, actions::setBackgroundBlur)
                    InspectorSlider(stringResource(Res.string.composer_scale), background.crop.scale, .5f..3f, 1f, actions, actions::setBackgroundCropScale)
                }
                is CompositionBackground.Gradient -> InspectorSlider(
                    stringResource(Res.string.composer_angle), background.angleDegrees, 0f..360f, 0f, actions, actions::setBackgroundGradientAngle,
                )
                is CompositionBackground.Solid -> Text(
                    stringResource(Res.string.composer_background_color_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextTertiary,
                    modifier = Modifier.padding(vertical = ImagoSpacing.Xs),
                )
            }
        }
    }
}

/**
 * An inspector slider.
 *
 * It is the same component as the photo editor's ([ImagoParameterSlider]), and with it come the same
 * manners: the value in gold when it leaves neutral, double tap to reset it, vibration when passing
 * neutral and the extremes, and — what is noticed most — while this one is dragged all the others
 * leave the scene and the active row gets a background of its own to be read over the page.
 *
 * It opens and closes a gesture in [ComposerEditorViewModel] so that sweeping the slider is **one**
 * edit in the history. Without it, dragging from 0 to 48 stacked one entry per frame and filled the
 * hundred slots at once, leaving "undo" stepping back a thousandth at a time.
 */
@Composable
internal fun InspectorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    neutral: Float,
    actions: ComposerEditorViewModel,
    onValueChange: (Float) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val focusState = LocalComposerFocus.current
    // The label is enough to tell them apart: only one inspector is in view at a time, and inside it
    // there are no two adjustments with the same name.
    val key = label
    val commit = { if (editing) { editing = false; focusState.focus = null; actions.endGesture(stage = false) } }
    ImagoParameterSlider(
        label = label,
        value = value.coerceIn(range),
        neutral = neutral,
        range = range,
        pointerKey = key,
        editingKey = focusState.focus?.key,
        onEditing = { active -> if (active == null) focusState.focus = null },
        valueText = { it.formatOne() },
        onValueChange = { next ->
            if (!editing) { editing = true; actions.beginGesture(stage = false) }
            // The focused mode's ruler reads the value from here and not from the state: the model only
            // answers on the next frame, and the ruler would always be one step behind the finger.
            focusState.focus = SliderFocus(key, label, next, range, neutral)
            onValueChange(next)
        },
        onValueChangeFinished = commit,
        onReset = {
            actions.beginGesture(stage = false)
            onValueChange(neutral)
            actions.endGesture(stage = false)
        },
    )
}

/**
 * The composer's bottom panel: the row of tools, the inspector and the page controls.
 *
 * It is the same sheet as the photo editor's — blurred glass, rounded top corners, a separating
 * hairline — and not the opaque navigation bar that used to be here. The difference that is noticed
 * is in [immersive]: while dragging a value or moving an element, the sheet becomes transparent and
 * the page shows under it, whole.
 */
@Composable
private fun ComposerPanelSurface(
    immersive: Boolean,
    contentAlpha: Float,
    onHeightChanged: (Dp) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(topStart = ImagoRadii.Panel, topEnd = ImagoRadii.Panel)
    val panelColor by animateColorAsState(
        targetValue = if (immersive) Color.Transparent else ImagoColors.Glass,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "composerPanelColor",
    )
    val borderColor by animateColorAsState(
        targetValue = if (immersive) Color.Transparent else ImagoColors.BorderSubtle,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "composerPanelBorder",
    )
    Box(
        // The measured height tells the stage how much this sheet covers, so the page settles above it
        // instead of being hidden underneath.
        Modifier.fillMaxWidth().onSizeChanged { measured ->
            val height = with(density) { measured.height.toDp() }
            if (height > 0.dp) onHeightChanged(height)
        },
    ) {
        // The blur lives on a layer underneath: applied to the sheet, it would blur the sliders too.
        if (!immersive) Box(Modifier.matchParentSize().clip(shape).imagoGlassBlur().background(Color.Transparent))
        Surface(
            color = panelColor,
            shape = shape,
            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, shape),
        ) {
            Column(Modifier.navigationBarsPadding().alpha(contentAlpha), content = content)
        }
    }
}

/**
 * The margin between the edge of the stage and the first page.
 *
 * It stopped being a loose number: it enters the calculation that turns the scroll position into a
 * page number, and the two have to agree to the pixel or the page changes half a page too early.
 */
private val StageHorizontalPadding = 18.dp

/** The stroke's thickness while it is being made, before it becomes an element. */
private const val LIVE_STROKE_WIDTH = 5f

/**
 * How far an element moves per arrow press, as a fraction of the page.
 *
 * With Shift it moves ten times further. The fine step goes through the same snapping as the drag, so
 * pushing an element against a guide holds it there — which is what is wanted from a keyboard nudge.
 */
private const val NUDGE_STEP = 0.004f
private const val NUDGE_STEP_COARSE = 0.04f

/** The column of the properties of what is chosen. */
private val InspectorPaneWidth = 320.dp

/** A coluna da estrutura do documento. */
private val LayersPaneWidth = 264.dp

/**
 * The window from which the layers also move out to a column.
 *
 * The inspector opens at 840dp, but opening both at the same time there left the stage with less than
 * 300dp — narrower than the phone one is escaping from. The layers wait until there is room for all
 * three.
 */
private val LayersPaneMinWindow = 1040.dp

/** How many pages on each side of the current one are composed. */
private const val PAGE_WINDOW = 1

/**
 * The pages worth composing.
 *
 * A project can have twenty pages, and all of them used to be composed at the same time — twenty
 * pages of `AsyncImage` and live video players at once. The window keeps the neighbours ready for the
 * scroll without paying for the whole project.
 */
private fun visiblePages(project: CompositionProject, currentPage: Int): IntRange {
    val last = project.pages.lastIndex
    val centre = currentPage.coerceIn(0, last)
    return (centre - PAGE_WINDOW).coerceAtLeast(0)..(centre + PAGE_WINDOW).coerceAtMost(last)
}

@Composable
internal fun ComposerCanvas(
    project: CompositionProject,
    state: ComposerEditorUiState,
    drawing: Boolean,
    drawingKind: StrokeKind,
    onCancelDrawing: () -> Unit,
    multiSelect: Boolean,
    positioningBackground: Boolean,
    /** The chosen element is being framed: the finger slides the image instead of moving the box. */
    positioningMedia: Boolean,
    previewUrl: (String) -> String,
    thumbnailUrl: (String) -> String,
    videoPlaybackUrl: (String) -> String,
    apiKey: (String) -> String,
    actions: ComposerEditorViewModel,
    onDrawing: (List<StrokePoint>) -> Unit,
    /** Opens the library to fill this element. */
    onPickMediaFor: (String) -> Unit,
    /** Entering framing — a double tap on a media item is the shortcut for the bar's button. */
    onFrameMedia: () -> Unit,
    /**
     * What the bars cover of the stage, at the top and bottom.
     *
     * The stage draws under them — otherwise, a transparent panel would have nothing underneath — and
     * this is what keeps the page from being hidden by them: it fits what is left. At zero, the page
     * grows and uses the whole screen.
     */
    stageInsets: ComposerStageInsets,
    modifier: Modifier = Modifier,
) {
    // Before any element asks for anything: photos with a recipe are processed in the background, so
    // the stage finds them ready instead of having them made one by one in front of whoever is looking.
    WarmComposerPhotos(project, previewUrl, thumbnailUrl, apiKey)
    // The double tap is counted here, and not inside the element nor the frame, because the two taps
    // almost always land in different places: the first selects the media and, from then on, the
    // selection frame receives the taps. A counter in each of them never saw the pair.
    var lastTapId by remember(project.id) { mutableStateOf<String?>(null) }
    var lastTapAt by remember(project.id) { mutableLongStateOf(0L) }
    val onMediaTap: (String) -> Unit = { id ->
        val target = project.elements.firstOrNull { it.id == id }
        val now = System.currentTimeMillis()
        when {
            // Only photos and videos have framing, and a locked element does not move.
            target?.placementCrop() == null || target.locked ||
                multiSelect || state.selectedElementIds.size > 1 -> lastTapId = null
            id == lastTapId && now - lastTapAt <= DoubleTapWindowMs -> {
                lastTapId = null
                onFrameMedia()
            }
            else -> {
                lastTapId = id
                lastTapAt = now
            }
        }
    }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    var zoom by remember(project.id) { mutableStateOf(1f) }
    val selectedElementId = state.selectedElementId
    // The element whose context menu is open. It lives here, and not inside each element, because the
    // menu has to position itself in stage coordinates.
    var contextMenuFor by remember(project.id) { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.background(ImagoColors.Background), contentAlignment = Alignment.CenterStart) {
        val pageHeight = (maxHeight - 32.dp - stageInsets.top - stageInsets.bottom).coerceAtLeast(220.dp) * zoom
        val pageWidth = pageHeight * project.format.pixelWidth / project.format.pixelHeight
        val density = LocalDensity.current
        val pageWidthPx = with(density) { pageWidth.toPx() }
        val pageHeightPx = with(density) { pageHeight.toPx() }
        val strokes = remember(drawing, project.id) { mutableStateListOf<StrokePoint>() }
        // An immutable copy per composition. Reading the live list inside the `Canvas` was a race: the
        // `isNotEmpty()` guard ran in the composition and `first()` in the draw phase, already after
        // `onDragEnd` had cleared the list — hence the `NoSuchElementException`.
        val liveStroke = strokes.toList()
        val visible = visiblePages(project, state.currentPage)
        val stagePaddingPx = with(density) { StageHorizontalPadding.toPx() }
        // The background in force on this page, so the position mode's drag knows how much slack it has.
        val positionedBackground by rememberUpdatedState(
            (project.pages.getOrNull(state.currentPage)?.backgroundOverride ?: project.background)
                as? CompositionBackground.Photo,
        )
        // While the stage takes itself to the chosen page, the reverse path stays quiet. Without this,
        // every frame of the animation passed a new page, sent it to the state, and that state
        // re-triggered the animation for that page — the scroll got stuck halfway.
        var followingSelection by remember { mutableStateOf(false) }
        // "Current page" now means the page being viewed. It used to be only what the "Pages" panel
        // buttons had last said, and the stage scrolls freely: whoever scrolled to page 3 and asked for
        // a "this page only" background wrote it on page 1, and page 3 kept showing the continuous
        // background — hence the impression that the per-page background did nothing. The same
        // `currentPage` decides `visiblePages`, so the elements of the pages scrolled to were not
        // composed either.
        LaunchedEffect(pageWidthPx, project.pages.size) {
            snapshotFlow { horizontal.value + horizontal.viewportSize / 2 }
                .map { centre -> ((centre - stagePaddingPx) / pageWidthPx).toInt().coerceIn(project.pages.indices) }
                .distinctUntilChanged()
                .collect { page -> if (!followingSelection) actions.selectPage(page) }
        }
        LaunchedEffect(state.currentPage, pageWidthPx, project.pages.size) {
            val centred = stagePaddingPx + state.currentPage * pageWidthPx + pageWidthPx / 2f - horizontal.viewportSize / 2f
            // Half a page of tolerance: when it was the scroll that chose the page, it is already in
            // view and there is nothing to do. Only a jump coming from the buttons goes through here.
            if (abs(centred - horizontal.value) > pageWidthPx / 2f) {
                followingSelection = true
                try {
                    horizontal.animateScrollTo(centred.roundToInt().coerceIn(0, horizontal.maxValue))
                } finally {
                    followingSelection = false
                }
            }
        }
        Box(
            Modifier
                // Scrolling is off while drawing or positioning the background: being earlier in the
                // chain, it consumed the drag and the stroke came out broken or not at all.
                .horizontalScroll(horizontal, enabled = !drawing && !positioningBackground)
                .verticalScroll(vertical, enabled = !drawing && !positioningBackground)
                .padding(
                    start = StageHorizontalPadding,
                    end = StageHorizontalPadding,
                    top = stageInsets.top + 16.dp,
                    bottom = stageInsets.bottom + 16.dp,
                )
                .width(pageWidth * project.pages.size).height(pageHeight)
                .pointerInput(drawing, project.pages.size) {
                    if (drawing) detectDragGestures(
                        onDragStart = { point ->
                            actions.beginGesture()
                            strokes.clear()
                            strokes += StrokePoint(point.x / pageWidthPx, point.y / pageHeightPx)
                        },
                        onDragEnd = { actions.endGesture(); onDrawing(strokes.toList()); strokes.clear() },
                        onDragCancel = { actions.endGesture(); strokes.clear() },
                    ) { change, _ ->
                        change.consume()
                        // Only the stylus has pressure worth following. A finger reports whatever the
                        // contact area gives, and that made the stroke thicken on its own in the middle of
                        // a curve; it stays at full force, which is how it always drew.
                        val pressure = if (change.type == PointerType.Stylus) change.pressure else 1f
                        // `onDragStart` receives a position and not an event, so the first point is born
                        // with no pressure to read. This is where it gets it — otherwise the stroke
                        // always started at full width and thinned afterwards.
                        if (strokes.size == 1) strokes[0] = strokes[0].copy(pressure = pressure)
                        strokes += StrokePoint(
                            x = change.position.x / pageWidthPx,
                            y = change.position.y / pageHeightPx,
                            pressure = pressure,
                        )
                    }
                }
                // A second `pointerInput`, and not an `if` inside the one above: the two keys
                // (`drawing`, `positioningBackground`) can no longer both be true at the same time —
                // whoever turns one on turns the other off — but keeping them separate avoids turning
                // one on restarting the other's gesture halfway.
                .pointerInput(positioningBackground, project.pages.size) {
                    if (positioningBackground) detectDragGestures(
                        onDragStart = { actions.beginGesture() },
                        onDragEnd = { actions.endGesture() },
                        onDragCancel = { actions.endGesture() },
                    ) { change, dragAmount ->
                        change.consume()
                        // Read every frame, and not captured: changing the background's scale in the
                        // middle of the mode has to change the slack without restarting the gesture.
                        val photo = positionedBackground ?: return@detectDragGestures
                        val slack = placementSlack(pageWidthPx, pageHeightPx, photo.contentAspect(), photo.crop.scale)
                        actions.nudgeBackgroundCrop(
                            framingFraction(dragAmount.x, slack.x),
                            framingFraction(dragAmount.y, slack.y),
                        )
                    }
                }
                // It is not `.clickable`, on purpose. A selected element has, over this same stage, a
                // fillMaxSize `ComposerSelectionOverlay` and its own `composerElementGestures` — both
                // descendants, both processed before this `Box`, which is the ancestor. `clickable`
                // knows how to back off when the touch was already consumed by a child, but exactly
                // how it does so is not something to trust from memory when there have already been
                // two gesture arbitration bugs on this canvas. Writing its own
                // `awaitFirstDown(requireUnconsumed = true)` — the same guard that already protects
                // `composerElementGestures` and the overlay — guarantees, and not just makes likely,
                // that tapping inside an element or on a handle never gets here.
                .pointerInput(drawing, positioningBackground) {
                    awaitEachGesture {
                        if (drawing || positioningBackground) return@awaitEachGesture
                        awaitFirstDown(requireUnconsumed = true)
                        if (waitForUpOrCancellation() != null) actions.selectBackground()
                    }
                },
        ) {
            project.pages.forEach { page ->
                // Each page paints its own background, even when the background is the project's.
                //
                // There used to be a single `fillMaxSize` layer behind everything — and "everything"
                // is the whole stage, with the width of all the pages. A background photo was stretched
                // across the pages like a panorama, and a gradient was born on the first page and died
                // on the last: however much "this page only" was asked for, what was seen kept crossing
                // all of them. The exporter never did that — `renderPage` draws the whole background on
                // every page —, so the stage showed one thing and the file brought another. The
                // exporter was the one in charge, and it is what the stage now follows.
                Box(Modifier.offset(x = pageWidth * page.index).size(pageWidth, pageHeight)) {
                    Background(page.backgroundOverride ?: project.background, previewUrl, apiKey, Modifier.fillMaxSize())
                }
                Box(
                    Modifier.offset(x = pageWidth * page.index).size(pageWidth, pageHeight)
                        .border(
                            1.dp,
                            if (state.backgroundSelected && page.index == state.currentPage) {
                                ImagoColors.Gold
                            } else {
                                Color.White.copy(alpha = .32f)
                            },
                        ),
                ) {
                    if (project.safeZonesVisible) Box(Modifier.fillMaxSize().padding(pageWidth * .06f).border(1.dp, Color.White.copy(alpha = .2f)))
                    Text("${page.index + 1}", color = Color.White.copy(alpha = .5f), fontSize = 10.sp, modifier = Modifier.padding(4.dp))
                }
            }
            project.elements.filter(CompositionElement::visible).sortedBy(CompositionElement::zIndex).forEach { element ->
                val pageTargets = element.repeatOnPages.takeIf { it.isNotEmpty() }
                    ?: setOf(element.transform.bounds.x.toInt().coerceIn(project.pages.indices))
                pageTargets.filter { it in visible }.forEach { page ->
                    val originalPage = element.transform.bounds.x.toInt()
                    val translatedX = if (element.repeatOnPages.isEmpty()) element.transform.bounds.x else page + (element.transform.bounds.x - originalPage)
                    CompositionElementView(
                        element, translatedX, pageWidth, pageHeight, pageWidthPx, pageHeightPx,
                        selected = element.id in state.selectedElementIds,
                        multiSelect = multiSelect,
                        // Framing is always of a single element: the chosen one. An element repeated on
                        // several pages shows several times, and only the copy in view receives the
                        // finger — the others follow it anyway, because it is the same `crop` that changes.
                        positioning = positioningMedia && element.id == state.selectedElementId,
                        onLongPress = { contextMenuFor = element.id },
                        // A media slot with a "+" drawn on it promised that tapping it was good for
                        // something, and it was not: it selected it and everything stayed the same.
                        // Whoever taps the "+" wants the library, and that is what opens — except in
                        // multiple selection, where the tap is for adding to the set, and in the drawing
                        // and background modes, where the stage belongs to another gesture.
                        onPickMedia = if (
                            element is CompositionElement.MediaPlaceholder &&
                            !element.locked &&
                            !multiSelect &&
                            !drawing &&
                            !positioningBackground
                        ) {
                            { onPickMediaFor(element.id) }
                        } else {
                            null
                        },
                        onTap = { onMediaTap(element.id) },
                        previewUrl = previewUrl, thumbnailUrl = thumbnailUrl, videoPlaybackUrl = videoPlaybackUrl, apiKey = apiKey,
                        actions = actions,
                    )
                }
            }
            // In a multiple selection the secondaries only get an outline: the handles belong to the
            // primary, which is what the gestures move and resize.
            if (state.selectedElementIds.size > 1) {
                project.elements
                    .filter { it.id in state.selectedElementIds && it.id != selectedElementId && it.visible }
                    .forEach { companion ->
                        val cb = companion.transform.bounds
                        Box(
                            Modifier
                                .offset(x = pageWidth * cb.x, y = pageHeight * cb.y)
                                .size(pageWidth * cb.width, pageHeight * cb.height)
                                .border(1.dp, ImagoColors.Gold.copy(alpha = .8f)),
                        )
                    }
            }
            // The frame comes after all the elements so the handles are always on top.
            val selected = project.elements.firstOrNull { it.id == selectedElementId }
            val block = project.elements
                .filter { it.id in state.selectedElementIds && it.visible }
                .takeIf { it.size > 1 }
            if (selected != null && selected.visible && !drawing) {
                // With several chosen the frame wraps the set, and not the last one tapped: it is the
                // set the handles resize, and a frame around a single one promised something else.
                // Without rotation, because the block has no angle of its own.
                val frame = block?.let { members ->
                    val left = members.minOf { it.transform.bounds.x }
                    val top = members.minOf { it.transform.bounds.y }
                    NormalizedRect(
                        x = left,
                        y = top,
                        width = members.maxOf { it.transform.bounds.right } - left,
                        height = members.maxOf { it.transform.bounds.bottom } - top,
                    )
                }
                ComposerSelectionOverlay(
                    elementId = block?.joinToString { it.id } ?: selected.id,
                    bounds = frame ?: selected.transform.bounds,
                    rotationDegrees = if (frame != null) 0f else selected.transform.rotationDegrees,
                    locked = selected.locked,
                    positioning = positioningMedia,
                    pageWidthPx = pageWidthPx,
                    pageHeightPx = pageHeightPx,
                    onBeginGesture = actions::beginGesture,
                    onEndGesture = actions::endGesture,
                    onResize = actions::resizeSelectedFromCorner,
                    onRotate = { actions.setSelectedRotation(it, snap = true) },
                    // An already selected element is covered by this box: it, and not the element, is
                    // what receives the move touch and the pinch from now on.
                    onMove = actions::moveSelected,
                    onScale = actions::scaleSelected,
                    onRotateBy = actions::rotateSelectedBy,
                    onTap = { onMediaTap(selected.id) },
                )
            }
            contextMenuFor?.let { id ->
                val target = project.elements.firstOrNull { it.id == id }
                if (target == null) {
                    contextMenuFor = null
                } else {
                    val tb = target.transform.bounds
                    Box(Modifier.offset(x = pageWidth * tb.x, y = pageHeight * (tb.y + tb.height))) {
                        ElementContextMenu(
                            element = target,
                            actions = actions,
                            onDismiss = { contextMenuFor = null },
                        )
                    }
                }
            }
            if (liveStroke.size > 1) {
                Canvas(Modifier.fillMaxSize()) {
                    // The stroke being born already shows the thickness it will keep: seeing the
                    // pressure only after letting go of the stylus took away half its usefulness.
                    if (liveStroke.hasVariablePressure()) {
                        liveStroke.zipWithNext { from, to ->
                            drawLine(
                                color = Color.White,
                                start = Offset(from.x * pageWidthPx, from.y * pageHeightPx),
                                end = Offset(to.x * pageWidthPx, to.y * pageHeightPx),
                                strokeWidth = strokeWidthAt(LIVE_STROKE_WIDTH, (from.pressure + to.pressure) / 2f),
                                cap = StrokeCap.Round,
                            )
                        }
                    } else {
                        val path = Path().apply {
                            moveTo(liveStroke.first().x * pageWidthPx, liveStroke.first().y * pageHeightPx)
                            liveStroke.drop(1).forEach { lineTo(it.x * pageWidthPx, it.y * pageHeightPx) }
                        }
                        drawPath(path, Color.White, style = Stroke(LIVE_STROKE_WIDTH))
                    }
                }
            }
            if (drawing) {
                // The notice is also the way out. Before, the mode could only be left by drawing a
                // stroke: whoever tapped the pen by mistake had no way back without dirtying the page.
                Surface(
                    color = Color.Black.copy(alpha = .7f),
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = stageInsets.top + 8.dp, start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onCancelDrawing),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(Res.string.composer_drawing_hint, drawingKind.label()), fontSize = 12.sp)
                        Icon(
                            Icons.Outlined.Close,
                            stringResource(Res.string.composer_exit_drawing),
                            Modifier.padding(start = 6.dp).size(14.dp),
                        )
                    }
                }
            }
        }
        Surface(
            color = Color.Black.copy(alpha = .72f),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(top = stageInsets.top + 8.dp, start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { zoom = (zoom - .2f).coerceAtLeast(.5f) }) { Text("−") }
                Text("${(zoom * 100).roundToInt()}%", fontSize = 12.sp)
                TextButton(onClick = { zoom = (zoom + .2f).coerceAtMost(2.5f) }) { Text("+") }
            }
        }
    }
}

@Composable
internal fun Background(background: CompositionBackground, previewUrl: (String) -> String, apiKey: (String) -> String, modifier: Modifier) {
    when (background) {
        is CompositionBackground.Solid -> Box(modifier.background(Color(background.argb)))
        is CompositionBackground.Gradient -> Box(
            modifier.drawWithCache {
                // The angle was ignored here and respected on export: the stage's gradient was not the
                // file's gradient.
                val radians = Math.toRadians(background.angleDegrees.toDouble())
                val dx = kotlin.math.cos(radians).toFloat() * size.width / 2f
                val dy = kotlin.math.sin(radians).toFloat() * size.height / 2f
                val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(Color(background.startArgb), Color(background.endArgb)),
                    start = Offset(size.width / 2f - dx, size.height / 2f - dy),
                    end = Offset(size.width / 2f + dx, size.height / 2f + dy),
                )
                onDrawBehind { drawRect(brush) }
            },
        )
        // `Modifier.blur` is a `RenderEffect`, which requires Android 12 — guaranteed by `minSdk` 31.
        is CompositionBackground.Photo -> AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(previewUrl(background.media.assetId))
                .libraryAuth(apiKey(background.media.assetId))
                .build(),
            contentDescription = null,
            // The same framing pair as the photo/video elements — without it, the "Scale" slider
            // saved the right value (and the export respected it), but the stage always drew the image
            // at 1:1 and the slider seemed to do nothing.
            alignment = placementAlignment(background.crop),
            modifier = (if (background.blurRadius > 0f) modifier.blur(background.blurRadius.dp) else modifier)
                .placementZoom(background.crop),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun CompositionElementView(
    element: CompositionElement,
    translatedX: Float,
    pageWidth: androidx.compose.ui.unit.Dp,
    pageHeight: androidx.compose.ui.unit.Dp,
    pageWidthPx: Float,
    pageHeightPx: Float,
    selected: Boolean,
    multiSelect: Boolean,
    positioning: Boolean,
    onLongPress: () -> Unit,
    /**
     * Opening the library. Only media slots bring it — for the others a tap is just choosing, and
     * `null` is what says so.
     */
    onPickMedia: (() -> Unit)?,
    /** A short tap on the element, always. It is the stage that counts the two for the double tap. */
    onTap: () -> Unit,
    previewUrl: (String) -> String,
    thumbnailUrl: (String) -> String,
    videoPlaybackUrl: (String) -> String,
    apiKey: (String) -> String,
    actions: ComposerEditorViewModel,
) {
    val b = element.transform.bounds
    val context = LocalPlatformContext.current
    // The aspect ratio the media is really drawn with, told by the image itself when it arrives.
    //
    // The dimensions declared in the reference are what the server said, and it did not always say:
    // in an old composition they came as `null`, and without them framing did not know which side
    // overflows — it gave zero slack on both axes and the finger moved nothing. They stay as the first
    // answer, so there is slack before the image loads; the measurement replaces them once it exists.
    var measuredAspect by remember(element.id) { mutableStateOf<Float?>(null) }
    // Everything the framing gesture reads changes while it runs — the pinch changes the zoom, and it
    // is the zoom that decides the drag's slack. The `pointerInput` block only starts once, so the
    // values have to reach it by reading and not by copying.
    val liveElement by rememberUpdatedState(element)
    val liveAspect by rememberUpdatedState(measuredAspect ?: element.contentAspect())
    val liveBoxWidth by rememberUpdatedState(pageWidthPx * b.width)
    val liveBoxHeight by rememberUpdatedState(pageHeightPx * b.height)
    // In multiple selection mode a tap adds and removes instead of replacing: that is what allows
    // picking up three things to align them without having to group them first.
    val onSelect = {
        if (multiSelect) actions.toggleElementInSelection(element.id) else actions.selectElement(element.id)
    }
    // The semantics block is not a composition: the texts are read here, before it.
    val elementLabel = element.accessibilityLabel()
    val pickMediaLabel = stringResource(Res.string.composer_choose_media)
    val selectLabel = stringResource(Res.string.composer_select)
    val common = Modifier
        .offset { IntOffset((translatedX * pageWidthPx).roundToInt(), (b.y * pageHeightPx).roundToInt()) }
        .size(pageWidth * b.width, pageHeight * b.height)
        // The graphics layer wraps the gestures, on purpose: that way the touch is tested against the
        // shape that is seen (a tilted element catches the finger where it is drawn) and Compose
        // delivers the offset already in the element's frame of reference. The old bug was not the
        // order — it was missing the conversion back to the screen, which `composerElementGestures`
        // does with `rotateVector`. Without it, dragging an element at 45° made it run off diagonally.
        .graphicsLayer {
            rotationZ = element.transform.rotationDegrees
            // The flip was only applied on export: the stage showed one thing and the file brought
            // another.
            scaleX = if (element.transform.mirrorHorizontal) -1f else 1f
            scaleY = if (element.transform.mirrorVertical) -1f else 1f
            alpha = element.transform.opacity
        }
        .then(
            if (positioning) {
                Modifier.mediaFramingGestures(
                    elementId = element.id,
                    boxWidthPx = { liveBoxWidth },
                    boxHeightPx = { liveBoxHeight },
                    contentAspect = { liveAspect },
                    cropScale = { liveElement.placementCrop()?.scale ?: 1f },
                    mirrorHorizontal = { liveElement.transform.mirrorHorizontal },
                    mirrorVertical = { liveElement.transform.mirrorVertical },
                    onBeginGesture = actions::beginGesture,
                    onEndGesture = actions::endGesture,
                    onNudge = actions::nudgeSelectedCrop,
                    onZoomBy = actions::scaleSelectedCropBy,
                )
            } else {
                Modifier.composerElementGestures(
                    elementId = element.id,
                    locked = { element.locked },
                    rotationDegrees = { element.transform.rotationDegrees },
                    mirrorHorizontal = { element.transform.mirrorHorizontal },
                    mirrorVertical = { element.transform.mirrorVertical },
                    pageWidthPx = { pageWidthPx },
                    pageHeightPx = { pageHeightPx },
                    onSelect = onSelect,
                    onLongPress = onLongPress,
                    onTap = { onPickMedia?.invoke() ?: onTap() },
                    onBeginGesture = actions::beginGesture,
                    onEndGesture = actions::endGesture,
                    onMove = actions::moveSelected,
                    onScale = actions::scaleSelected,
                    onRotate = { actions.setSelectedRotation(it) },
                )
            },
        )
        // `clickable` competed with `composerElementGestures` for the same touch: both install a
        // `pointerInput` on the same node, and being the innermost in the chain `clickable` consumed
        // the `down` in the Main pass before `composerElementGestures` could see it — whose
        // `awaitFirstDown(requireUnconsumed = true)` guard exists precisely so as not to steal the
        // touch from the selection frame, and saw the same danger in it. Without a free `down`,
        // `awaitEachGesture` never started: selecting by tap kept working (the `clickable`'s
        // `onClick` is independent), but moving stopped responding.
        // Selection already happens inside `composerElementGestures`, which calls `onSelect()` as soon
        // as the finger lands — so `clickable` was redundant, and it is what goes; the semantic action
        // stays, so TalkBack can still select by double tap.
        .semantics {
            contentDescription = elementLabel
            role = Role.Button
            // In a media slot TalkBack's double tap is worth what the tap on the stage is worth:
            // opening the library. Choosing an empty slot leads nowhere.
            if (onPickMedia != null) {
                onClick(label = pickMediaLabel) { onPickMedia(); true }
            } else {
                onClick(label = selectLabel) { onSelect(); true }
            }
        }
    when (element) {
        is CompositionElement.MediaPlaceholder -> Box(
            common.frameDecoration(element.frame).background(Color.White.copy(alpha = .08f))
                .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, stringResource(Res.string.composer_add_media), tint = Color.White.copy(alpha = .7f)) }
        // The element's recipe comes in here for the same reason it comes in on export: the stage has
        // to show the photo with the look the final file will have.
        is CompositionElement.Photo -> Box(common.frameDecoration(element.frame)) {
            var unavailable by remember(element.media.assetId) { mutableStateOf(false) }
            // The requests live in `remember` and have a fixed size. Building them loose here made Coil
            // restart loading on every frame of a gesture, and requesting them at the element's size
            // made each step of a resize apply the recipe again. The reason for both is in
            // [ComposerPhotoImages].
            val requests = rememberComposerPhotoRequests(
                element.media.assetId, element.recipe.recipe, previewUrl, thumbnailUrl, apiKey,
            )
            // The draft stays underneath: the recipe applied to the thumbnail arrives in a fraction of
            // the time of the large version, and it is what fills the box until that one comes. Once
            // both are cached this layer costs a texture the one above covers entirely.
            requests.draft?.let { draft ->
                AsyncImage(
                    model = draft,
                    contentDescription = null,
                    // The draft arrives first, and its aspect ratio is the same: whoever frames does
                    // not have to wait for the large version for the finger to start responding.
                    onSuccess = { measuredAspect = it.painter.intrinsicSize.aspectOrNull() },
                    contentScale = ContentScale.Crop,
                    alignment = placementAlignment(element.crop),
                    modifier = Modifier.fillMaxSize().placementZoom(element.crop),
                )
            }
            AsyncImage(
                model = requests.full,
                contentDescription = element.media.fileName,
                onError = { unavailable = true },
                onSuccess = {
                    unavailable = false
                    measuredAspect = it.painter.intrinsicSize.aspectOrNull()
                },
                contentScale = ContentScale.Crop,
                alignment = placementAlignment(element.crop),
                modifier = Modifier.fillMaxSize().placementZoom(element.crop),
            )
            if (unavailable) {
                Text(
                    unavailableMediaText(element.media.assetId),
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
        }
        is CompositionElement.Video -> Box(common.frameDecoration(element.frame)) {
            if (selected) {
                ElementVideoPlayer(element, videoPlaybackUrl(element.media.assetId), apiKey(element.media.assetId), Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    ImageRequest.Builder(context).data(thumbnailUrl(element.media.assetId)).libraryAuth(apiKey(element.media.assetId)).build(),
                    element.media.fileName,
                    Modifier.fillMaxSize().placementZoom(element.crop),
                    onSuccess = { measuredAspect = it.painter.intrinsicSize.aspectOrNull() },
                    alignment = placementAlignment(element.crop),
                    contentScale = ContentScale.Crop,
                )
                Surface(color = Color.Black.copy(alpha = .55f), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.padding(8.dp))
                }
            }
        }
        // The same painter and the same scale as this platform's exporter. The Compose `Text` that was
        // here measured the letter in fixed sp, and not as a fraction of the page: with the stage
        // zoomed in the text did not grow, and the file came out at another size, without outline,
        // shadow or background.
        is CompositionElement.Text -> {
            val fontBytes = rememberCompositionFontBytes(element.fontFamily)
            Canvas(common) { drawCompositionText(element, pageWidthPx / REFERENCE_PAGE_WIDTH, fontBytes) }
        }
        // Fill **and** outline, like the exporter. The stage painted lines and arrows with `fillArgb`
        // and ignored `strokeArgb`, so the shape changed colour on export.
        is CompositionElement.Shape -> Canvas(common) {
            val fill = Color(element.fillArgb)
            val stroke = Color(element.strokeArgb)
            // The thickness is measured on a page of [REFERENCE_PAGE_WIDTH], like the text size, and
            // not in pixels of this canvas: only that way is the outline seen the outline exported.
            val strokePx = element.strokeWidth * pageWidthPx / REFERENCE_PAGE_WIDTH
            val strokeStyle = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val head = kotlin.math.min(size.width, size.height) * .22f
            when (element.kind) {
                ShapeKind.RECTANGLE -> { drawRect(fill); drawRect(stroke, style = strokeStyle) }
                ShapeKind.ELLIPSE -> { drawOval(fill); drawOval(stroke, style = strokeStyle) }
                ShapeKind.LINE -> drawLine(
                    stroke, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokePx, StrokeCap.Round,
                )
                ShapeKind.ARROW -> {
                    drawLine(stroke, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokePx, StrokeCap.Round)
                    drawLine(stroke, Offset(size.width, size.height / 2), Offset(size.width - head, size.height / 2 - head), strokePx, StrokeCap.Round)
                    drawLine(stroke, Offset(size.width, size.height / 2), Offset(size.width - head, size.height / 2 + head), strokePx, StrokeCap.Round)
                }
            }
        }
        is CompositionElement.Drawing -> Canvas(common) {
            if (element.points.size > 1) {
                val strokeColor =
                    if (element.kind == StrokeKind.ERASER) Color.Transparent else Color(element.colorArgb)
                val strokeAlpha = if (element.kind == StrokeKind.MARKER) .45f else 1f
                if (element.points.hasVariablePressure()) {
                    // Without a single width for the whole stroke, no `Path` will do: it is drawn
                    // segment by segment, each with the width of the average pressure of its ends.
                    // Only stylus strokes go through here — the others stay in a single `Path`, which
                    // is cheaper and gives exactly the same result.
                    element.points.zipWithNext { from, to ->
                        drawLine(
                            color = strokeColor,
                            start = Offset(from.x * size.width, from.y * size.height),
                            end = Offset(to.x * size.width, to.y * size.height),
                            strokeWidth = strokeWidthAt(element.strokeWidth, (from.pressure + to.pressure) / 2f),
                            cap = StrokeCap.Round,
                            alpha = strokeAlpha,
                        )
                    }
                } else {
                    val path = Path().apply {
                        moveTo(element.points.first().x * size.width, element.points.first().y * size.height)
                        element.points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                    }
                    drawPath(
                        path,
                        strokeColor,
                        alpha = strokeAlpha,
                        // Round caps and joins, as the exporter always drew them. Here they were square
                        // by default, and a curved stroke came out faceted on screen and smooth in the
                        // file — the same composition with two looks.
                        style = Stroke(element.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

/**
 * Corners, shadow and outline, as the exporter draws them.
 *
 * The frame was only applied on export — on the stage the outline and shadow buttons did nothing
 * visible, and the final file came out with a decoration nobody had seen.
 */
@Composable
private fun Modifier.frameDecoration(frame: FrameStyle): Modifier {
    val shape = RoundedCornerShape((frame.cornerRadius * 100).coerceIn(0f, 50f).toInt())
    return this
        .then(if (frame.shadowRadius > 0f) Modifier.shadow(frame.shadowRadius.dp, shape, clip = false) else Modifier)
        .clip(shape)
        .then(
            if (frame.borderWidth > 0f) {
                Modifier.border(frame.borderWidth.dp, Color(frame.borderArgb), shape)
            } else {
                Modifier
            },
        )
}

/** How much time separates two taps for them to count as a double tap. The same threshold as the system. */
private const val DoubleTapWindowMs = 300L

/**
 * The framing mode's gesture: one finger slides the image inside the mask.
 *
 * It replaces `composerElementGestures` instead of living alongside it. The two would compete for the
 * same `down` on the same node, and this canvas has already had two gesture arbitration bugs — while
 * framing, the element simply does not move or rotate.
 *
 * It runs **inside** the graphics layer that rotates and flips the element, so the offset arrives
 * already in its frame of reference and the rotation does not need undoing. The flip does: it inverts
 * the axis without rotating it, and without this the image ran the opposite way to the finger on a
 * flipped element.
 *
 * The keys cover everything the block reads that does not change during a drag — the box, the crop
 * zoom. The `offset`, which changes every frame, is not read here: the model is what accumulates it.
 */
private fun Modifier.mediaFramingGestures(
    elementId: String,
    boxWidthPx: () -> Float,
    boxHeightPx: () -> Float,
    contentAspect: () -> Float?,
    cropScale: () -> Float,
    /** The flip is on the same layer, so the local offset comes inverted on that axis. */
    mirrorHorizontal: () -> Boolean,
    mirrorVertical: () -> Boolean,
    onBeginGesture: () -> Unit,
    onEndGesture: () -> Unit,
    onNudge: (Float, Float) -> Unit,
    onZoomBy: (Float) -> Unit,
): Modifier = pointerInput(elementId) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = true).consume()
        // A whole gesture is one edit in the history, as with moving and the sliders: without this,
        // undo stepped back one frame at a time.
        onBeginGesture()
        try {
            do {
                val event = awaitPointerEvent()
                // The pinch first: it changes the zoom, and it is the zoom that decides how much slack
                // this same frame's drag has to cover.
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    // This frame's ratio, of the order of 1.005 — it accumulates. Exactly 1 is a frame
                    // with no pinch at all, and sending it to the model was a history entry that changed
                    // nothing.
                    if (zoom != 1f) onZoomBy(zoom)
                }
                val pan = event.calculatePan()
                if (pan != Offset.Zero) {
                    val slack = placementSlack(
                        boxWidthPx = boxWidthPx(),
                        boxHeightPx = boxHeightPx(),
                        contentAspect = contentAspect(),
                        scale = cropScale(),
                    )
                    val flipX = if (mirrorHorizontal()) -1f else 1f
                    val flipY = if (mirrorVertical()) -1f else 1f
                    // Each axis on its own: a photo wider than the mask slides horizontally even with no
                    // slack at all vertically, and the other way round too.
                    val dx = framingFraction(pan.x * flipX, slack.x)
                    val dy = framingFraction(pan.y * flipY, slack.y)
                    if (dx != 0f || dy != 0f) onNudge(dx, dy)
                }
                event.changes.forEach(PointerInputChange::consume)
            } while (event.changes.any { it.pressed })
        } finally {
            onEndGesture()
        }
    }
}

/** The aspect ratio of an intrinsic size, when it says anything. */
private fun androidx.compose.ui.geometry.Size.aspectOrNull(): Float? =
    takeIf { it.isSpecified && it.width >= 1f && it.height >= 1f }
        ?.let { (it.width / it.height).takeIf(Float::isFinite) }

/** This element's framing, when it has one. Only photos and videos have. */
private fun CompositionElement.placementCrop(): PlacementCrop? = when (this) {
    is CompositionElement.Photo -> crop
    is CompositionElement.Video -> crop
    else -> null
}

/**
 * Where the media looks inside its box.
 *
 * `ContentScale.Crop` makes the image **cover** the box: the side the aspect ratio does not use
 * overflows and is left out. Changing an element's size or shape changes the box, not the image — and
 * that is why the same part, the middle, was always what remained.
 *
 * The alignment is the only place where that choice can be made. A translation in the graphics layer
 * would not do: when it runs, the image has already been drawn and cropped, and pushing it only left
 * an empty strip on the side it came from.
 *
 * The convention is that of the exporter's `drawCover`, which is in charge: `+1` looks at the right
 * (or bottom) side of the image, which is the same as sliding it to the left (or up).
 */
private fun placementAlignment(crop: PlacementCrop) =
    BiasAlignment(crop.offsetX.coerceIn(-1f, 1f), crop.offsetY.coerceIn(-1f, 1f))

/**
 * The framing's zoom, on top of the alignment above.
 *
 * The alignment covers the aspect ratio's slack; what the zoom adds is missing, and this translation
 * pays for it. The calculation comes from the difference between `drawCover`'s two slacks — the one it
 * has at the requested scale and the one it would have at 1× — and gives, once simplified, the exact
 * opposite of what this function used to do: moving the window right is moving the image left.
 *
 * Added together, the two halves reproduce `drawCover` at any scale: the stage and the file can no
 * longer disagree about which part of the photo is in view.
 */
private fun Modifier.placementZoom(crop: PlacementCrop): Modifier {
    val scale = crop.scale.coerceAtLeast(.01f)
    if (scale == 1f) return this
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = -crop.offsetX.coerceIn(-1f, 1f) * size.width * (scale - 1f) / 2f
        translationY = -crop.offsetY.coerceIn(-1f, 1f) * size.height * (scale - 1f) / 2f
    }
}

/**
 * How far the image can slide inside the box, in stage pixels, from one end to the other counting
 * from the middle.
 *
 * It is the sum of the two slacks [placementAlignment] and [placementZoom] cover — the aspect ratio's
 * (the image covers the box and one side is left over) and the zoom's —, and simplifies to the zoomed
 * cover size minus the box. It is also the drag's exchange rate: a finger pixel divided by this gives
 * the fraction by which `nudged` moves the `offset`.
 *
 * Without [contentAspect] — media whose dimensions the server did not give — only the zoom's slack is
 * left, which is what the stage could do before there was framing.
 */
private fun placementSlack(
    boxWidthPx: Float,
    boxHeightPx: Float,
    contentAspect: Float?,
    scale: Float,
): Offset {
    val coverWidth = if (contentAspect == null) boxWidthPx else max(boxWidthPx, boxHeightPx * contentAspect)
    val coverHeight = if (contentAspect == null) boxHeightPx else max(boxHeightPx, boxWidthPx / contentAspect)
    return Offset(
        (coverWidth * scale - boxWidthPx) / 2f,
        (coverHeight * scale - boxHeightPx) / 2f,
    )
}

/**
 * The fraction of slack a drag of [deltaPx] is worth.
 *
 * Zero when the axis has nowhere to go — an image that fits the box to the pixel does not slide.
 * Without this guard, dividing by a zero slack sent the `offset` to the extreme on the first pass:
 * nothing changed on screen, but the value was saved, and zooming in later was enough for the photo
 * to jump to the corner.
 */
private fun framingFraction(deltaPx: Float, slackPx: Float): Float =
    if (slackPx < .5f) 0f else deltaPx / slackPx

/**
 * The aspect ratio this element's media reaches the stage with — already with the recipe's geometry,
 * which may rotate or crop, and which is the same the exporter decodes.
 *
 * `null` when the server did not give the dimensions: then there is no way to know which side
 * overflows.
 */
private fun CompositionElement.contentAspect(): Float? {
    val media = when (this) {
        is CompositionElement.Photo -> media
        is CompositionElement.Video -> media
        else -> return null
    }
    val width = media.width?.takeIf { it > 0L } ?: return null
    val height = media.height?.takeIf { it > 0L } ?: return null
    return when (this) {
        is CompositionElement.Photo -> recipe.recipe.editedAspectRatio(width, height)
        else -> (width.toFloat() / height).takeIf { it.isFinite() && it > 0f }
    }
}

/** The same for the background, which the exporter draws without any recipe. */
private fun CompositionBackground.Photo.contentAspect(): Float? {
    val width = media.width?.takeIf { it > 0L } ?: return null
    val height = media.height?.takeIf { it > 0L } ?: return null
    return (width.toFloat() / height).takeIf { it.isFinite() && it > 0f }
}

@Composable
private fun SelectedInspector(
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    rail: Boolean,
    multiSelect: Boolean,
    positioning: Boolean,
    onToggleMultiSelect: () -> Unit,
    onTogglePositioning: () -> Unit,
    onEditText: () -> Unit,
    onEditPhoto: () -> Unit,
    onRequestGroup: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    val selected = state.project?.elements?.firstOrNull { it.id == state.selectedElementId } ?: return
    val chosen = state.selectedElementIds.size
    val isMedia = selected is CompositionElement.Photo ||
        selected is CompositionElement.Video ||
        selected is CompositionElement.MediaPlaceholder
    // Collapsed by default: the composition is what is being edited, and a fine adjustment is asked for
    // less often than the result is looked at. It reopens per element — switching selection should not
    // inherit the previous one's open drawer.
    var expanded by remember(selected.id, rail) { mutableStateOf(rail) }
    var pendingColor by remember(selected.id) { mutableStateOf<PendingColor?>(null) }
    var choosingFont by remember(selected.id) { mutableStateOf(false) }
    if (choosingFont && selected is CompositionElement.Text) {
        TextFontPicker(selected, state.brandKit, onDismiss = { choosingFont = false }) { font ->
            actions.setTextFont(font.id)
            choosingFont = false
        }
    }
    pendingColor?.let { pending ->
        ColorPickerDialog(
            title = pending.title.resolve(),
            initialArgb = pending.initial,
            palette = state.brandKit?.paletteArgb.orEmpty(),
            onDismiss = { pendingColor = null },
            onConfirm = pending.onPick,
        )
    }
    val chrome = inspectorChromeAlpha(rail)
    Column(Modifier.padding(horizontal = ImagoSpacing.Sm)) {
        Text(
            when {
                chosen > 1 -> stringResource(Res.string.composer_many_selected_hint, chosen)
                multiSelect -> stringResource(Res.string.composer_multi_select_hint, selected.accessibilityLabel())
                else -> stringResource(Res.string.composer_selected_hint, selected.accessibilityLabel())
            },
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextTertiary,
            maxLines = 1,
            modifier = Modifier.alpha(chrome).padding(top = ImagoSpacing.Xs),
        )
        // Six buttons, not twenty-five. What is continuous is a slider in the drawer; what belongs to
        // the same subject — align, arrange — sits behind a menu. What is left in the row is what is
        // used on every element one taps.
        Row(
            Modifier.fillMaxWidth().alpha(chrome).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // First of all: it is the drawer that has opacity, rotation and the frame, and it is where
            // one goes right after placing the element.
            ToolButton(
                label = if (expanded) stringResource(Res.string.composer_close) else stringResource(Res.string.composer_adjust),
                icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.Tune,
                active = expanded,
            ) { expanded = !expanded }
            if (chosen <= 1) {
                if (selected is CompositionElement.Text) {
                    ToolButton(stringResource(Res.string.composer_edit), Icons.Outlined.Edit, onClick = onEditText)
                    // The font stays in the row, and not in the drawer: it is the first thing changed in
                    // a new text, right after the words.
                    ToolButton(stringResource(Res.string.composer_font), Icons.Outlined.FontDownload) { choosingFont = true }
                }
                if (selected is CompositionElement.Photo) {
                    ToolButton(stringResource(Res.string.composer_develop), Icons.Outlined.PhotoFilter, onClick = onEditPhoto)
                }
                // The same button the background has, and for the same reason: sizing an element
                // changes the mask and not the image, and choosing which part of it stays in view is
                // two-dimensional. Two X/Y sliders would force looking at numbers instead of at the
                // photo.
                if (selected.placementCrop() != null && !selected.locked) {
                    ToolButton(
                        label = if (positioning) stringResource(Res.string.composer_reframing) else stringResource(Res.string.composer_reframe),
                        icon = if (positioning) Icons.Outlined.Check else Icons.Outlined.OpenWith,
                        active = positioning,
                        onClick = onTogglePositioning,
                    )
                }
            }
            // The colours. They stay in the row, and not in a menu, because choosing a shape's colour
            // is one of the first things done after inserting it — it is not a touch-up. With several
            // chosen they go: each type has its own, and there is no colour that serves all.
            if (chosen <= 1) when (selected) {
                is CompositionElement.Shape -> {
                    ToolButton(stringResource(Res.string.composer_fill), Icons.Outlined.FormatColorFill) {
                        pendingColor = PendingColor(uiText(Res.string.composer_shape_fill), selected.fillArgb, actions::setShapeFill)
                    }
                    ToolButton(stringResource(Res.string.composer_outline), Icons.Outlined.BorderColor) {
                        pendingColor = PendingColor(uiText(Res.string.composer_shape_outline), selected.strokeArgb, actions::setShapeStroke)
                    }
                }
                is CompositionElement.Text -> ToolButton(stringResource(Res.string.composer_color), Icons.Outlined.Colorize) {
                    pendingColor = PendingColor(uiText(Res.string.composer_text_color), selected.colorArgb, actions::setElementColor)
                }
                is CompositionElement.Drawing -> ToolButton(stringResource(Res.string.composer_color), Icons.Outlined.Colorize) {
                    pendingColor = PendingColor(uiText(Res.string.composer_stroke_color), selected.colorArgb, actions::setElementColor)
                }
                else -> if (isMedia) {
                    val frame = selected.frameStyle() ?: FrameStyle()
                    ToolButton(stringResource(Res.string.composer_outline), Icons.Outlined.BorderColor) {
                        pendingColor = PendingColor(uiText(Res.string.composer_frame_outline), frame.borderArgb, actions::setFrameBorderColor)
                    }
                }
            }
            ToolMenuButton(stringResource(Res.string.composer_align), Icons.Outlined.AlignHorizontalCenter) { dismiss ->
                ToolMenuItem(stringResource(Res.string.composer_align_left), Icons.AutoMirrored.Outlined.AlignHorizontalLeft, dismiss) {
                    actions.alignSelectedHorizontal(-1)
                }
                ToolMenuItem(stringResource(Res.string.composer_center_horizontally), Icons.Outlined.AlignHorizontalCenter, dismiss) {
                    actions.alignSelectedHorizontal(0)
                }
                ToolMenuItem(stringResource(Res.string.composer_align_right), Icons.AutoMirrored.Outlined.AlignHorizontalRight, dismiss) {
                    actions.alignSelectedHorizontal(1)
                }
                ToolMenuItem(stringResource(Res.string.composer_align_top), Icons.Outlined.AlignVerticalTop, dismiss) {
                    actions.alignSelectedVertical(-1)
                }
                ToolMenuItem(stringResource(Res.string.composer_center_vertically), Icons.Outlined.AlignVerticalCenter, dismiss) {
                    actions.alignSelectedVertical(0)
                }
                ToolMenuItem(stringResource(Res.string.composer_align_bottom), Icons.Outlined.AlignVerticalBottom, dismiss) {
                    actions.alignSelectedVertical(1)
                }
                // With two things there is nothing in the middle to space; from three on, there is.
                if (chosen > 2 || selected.groupId != null) {
                    HorizontalDivider()
                    ToolMenuItem(stringResource(Res.string.composer_distribute_horizontally), Icons.Outlined.AlignHorizontalCenter, dismiss) {
                        actions.distributeSelected(true)
                    }
                    ToolMenuItem(stringResource(Res.string.composer_distribute_vertically), Icons.Outlined.AlignVerticalCenter, dismiss) {
                        actions.distributeSelected(false)
                    }
                }
            }
            ToolMenuButton(stringResource(Res.string.composer_arrange), Icons.Outlined.Layers) { dismiss ->
                ToolMenuItem(
                    if (multiSelect) stringResource(Res.string.composer_end_multi_select) else stringResource(Res.string.composer_select_multiple),
                    if (multiSelect) Icons.Outlined.Check else Icons.Outlined.SelectAll,
                    dismiss,
                    onToggleMultiSelect,
                )
                HorizontalDivider()
                ToolMenuItem(stringResource(Res.string.composer_bring_forward), Icons.AutoMirrored.Outlined.ArrowForward, dismiss, actions::bringForward)
                ToolMenuItem(stringResource(Res.string.composer_send_backward), Icons.AutoMirrored.Outlined.ArrowBack, dismiss, actions::sendBackward)
                HorizontalDivider()
                when {
                    // With several in hand the question "with which one?" is already answered.
                    chosen > 1 -> ToolMenuItem(stringResource(Res.string.composer_group), Icons.Outlined.Layers, dismiss, actions::groupSelection)
                    selected.groupId == null -> ToolMenuItem(stringResource(Res.string.composer_group_with_ellipsis), Icons.Outlined.Layers, dismiss, onRequestGroup)
                    else -> ToolMenuItem(stringResource(Res.string.composer_ungroup), Icons.Outlined.Layers, dismiss, actions::ungroupSelected)
                }
                ToolMenuItem(
                    if (selected.locked) stringResource(Res.string.composer_unlock) else stringResource(Res.string.composer_lock),
                    if (selected.locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    dismiss,
                    actions::toggleLocked,
                )
                ToolMenuItem(
                    if (selected.visible) stringResource(Res.string.composer_hide) else stringResource(Res.string.composer_show),
                    if (selected.visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    dismiss,
                    actions::toggleVisible,
                )
                // A video repeated on every page would be the same clip playing several times on the
                // same timeline — the exporter has no way to resolve that.
                if (selected !is CompositionElement.Video) {
                    ToolMenuItem(stringResource(Res.string.composer_repeat_all_pages), Icons.Outlined.Repeat, dismiss, actions::repeatOnAllPages)
                }
            }
            ToolButton(stringResource(Res.string.composer_duplicate), Icons.Outlined.ContentCopy, onClick = actions::duplicateSelected)
            // The caption is read before deleting: afterwards there is no element to take it from.
            val deletedLabel = if (chosen > 1) {
                stringResource(Res.string.composer_elements_deleted, chosen)
            } else {
                stringResource(Res.string.composer_element_deleted, selected.accessibilityLabel())
            }
            ToolButton(stringResource(Res.string.composer_delete), Icons.Outlined.Delete, tint = ImagoColors.Danger) {
                val label = deletedLabel
                actions.deleteSelected()
                onDeleted(label)
            }
        }
        if (positioning) {
            Text(
                stringResource(Res.string.composer_mask_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.Gold,
                modifier = Modifier.padding(top = ImagoSpacing.Xs),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(imagoTween(ImagoMotion.Fast)) + expandVertically(imagoTween(ImagoMotion.Fast)),
            exit = fadeOut(imagoTween(ImagoMotion.Instant)) + shrinkVertically(imagoTween(ImagoMotion.Instant)),
        ) {
            Column {
                InspectorSlider(stringResource(Res.string.composer_opacity), selected.transform.opacity, .05f..1f, 1f, actions, actions::setSelectedOpacity)
                InspectorSlider(stringResource(Res.string.composer_rotation), selected.transform.rotationDegrees, 0f..360f, 0f, actions) { actions.setSelectedRotation(it) }
                // The flips live next to rotation because they are the same thing: they change the
                // element's orientation, not its place on the page nor the order of the layers.
                Row(
                    Modifier.fillMaxWidth().alpha(chrome).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ToolButton(
                        stringResource(Res.string.composer_flip_h),
                        Icons.Outlined.Flip,
                        active = selected.transform.mirrorHorizontal,
                        onClick = actions::toggleMirrorHorizontal,
                    )
                    ToolButton(
                        stringResource(Res.string.composer_flip_v),
                        Icons.Outlined.Flip,
                        active = selected.transform.mirrorVertical,
                        onClick = actions::toggleMirrorVertical,
                    )
                }
                // The zoom inside the mask. It lives next to framing because it is what gives it slack:
                // with the image's aspect ratio equal to the box's there is nothing left to slide.
                selected.placementCrop()?.let { crop ->
                    InspectorSlider(
                        stringResource(Res.string.composer_zoom), crop.scale, MIN_PLACEMENT_SCALE..MAX_PLACEMENT_SCALE, 1f, actions, actions::setSelectedCropScale,
                    )
                }
                if (isMedia) {
                    val frame = selected.frameStyle() ?: FrameStyle()
                    InspectorSlider(stringResource(Res.string.composer_corners), frame.cornerRadius, 0f..MAX_FRAME_CORNER, 0f, actions, actions::setFrameCorner)
                    InspectorSlider(stringResource(Res.string.composer_outline), frame.borderWidth, 0f..MAX_FRAME_BORDER, 0f, actions, actions::setFrameBorder)
                    InspectorSlider(stringResource(Res.string.composer_shadow), frame.shadowRadius, 0f..MAX_FRAME_SHADOW, 0f, actions, actions::setFrameShadow)
                }
                if (selected is CompositionElement.Text) {
                    TextStyleControls(selected, actions, rail, onPickColor = { pendingColor = it })
                }
                if (selected is CompositionElement.Shape) {
                    InspectorSlider(stringResource(Res.string.composer_thickness), selected.strokeWidth, MIN_SHAPE_STROKE..MAX_SHAPE_STROKE, 2f, actions, actions::setShapeStrokeWidth)
                }
                if (selected is CompositionElement.Video) {
                    VideoTimeline(selected, state.project.pages[selected.pageIndex].durationMs, actions)
                }
            }
        }
    }
}

private fun CompositionElement.frameStyle(): FrameStyle? = when (this) {
    is CompositionElement.MediaPlaceholder -> frame
    is CompositionElement.Photo -> frame
    is CompositionElement.Video -> frame
    else -> null
}

/**
 * The menu a long press opens over the element.
 *
 * It brings what is done **to** an element, not what is done **with** it: colour and text stay in the
 * bar, where there is room to show them. Here is what is wanted without lifting the finger from the
 * page.
 */
@Composable
private fun ElementContextMenu(
    element: CompositionElement,
    actions: ComposerEditorViewModel,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        ToolMenuItem(stringResource(Res.string.composer_duplicate), Icons.Outlined.ContentCopy, onDismiss, actions::duplicateSelected)
        ToolMenuItem(stringResource(Res.string.composer_bring_forward), Icons.AutoMirrored.Outlined.ArrowForward, onDismiss, actions::bringForward)
        ToolMenuItem(stringResource(Res.string.composer_send_backward), Icons.AutoMirrored.Outlined.ArrowBack, onDismiss, actions::sendBackward)
        HorizontalDivider()
        ToolMenuItem(
            if (element.locked) stringResource(Res.string.composer_unlock) else stringResource(Res.string.composer_lock),
            if (element.locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
            onDismiss,
            actions::toggleLocked,
        )
        ToolMenuItem(stringResource(Res.string.composer_hide), Icons.Outlined.VisibilityOff, onDismiss, actions::toggleVisible)
        if (element.groupId != null) {
            ToolMenuItem(stringResource(Res.string.composer_ungroup), Icons.Outlined.Layers, onDismiss, actions::ungroupSelected)
        }
        HorizontalDivider()
        ToolMenuItem(stringResource(Res.string.composer_delete), Icons.Outlined.Delete, onDismiss, actions::deleteSelected)
    }
}

@Composable
private fun VideoTimeline(video: CompositionElement.Video, pageDurationMs: Long, actions: ComposerEditorViewModel) {
    val timing = video.timing
    val clipLength = minOf(video.media.durationMs ?: MAX_PAGE_DURATION_MS, MAX_PAGE_DURATION_MS)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(
            stringResource(
                Res.string.composer_clip_summary,
                (timing.trimStartMs / 1000f).formatOne(),
                (timing.trimEndMs / 1000f).formatOne(),
                (timing.startOffsetMs / 1000f).formatOne(),
                (pageDurationMs / 1000f).formatOne(),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextSecondary,
        )
        // Both trim handles move on the same ruler, because it is a window over the same clip. With
        // half-second buttons, trimming two seconds cost eight taps and what was being left out could
        // not be seen.
        TimelineRangeRow(
            label = stringResource(Res.string.composer_trim),
            start = timing.trimStartMs,
            end = timing.trimEndMs,
            limit = clipLength,
            actions = actions,
            onStart = { actions.adjustVideoTrimStart(it - timing.trimStartMs) },
            onEnd = { actions.adjustVideoTrimEnd(it - timing.trimEndMs) },
        )
        InspectorSlider(
            stringResource(Res.string.composer_start),
            timing.startOffsetMs.toFloat(),
            0f..MAX_PAGE_DURATION_MS.toFloat(),
            0f,
            actions,
        ) { actions.adjustVideoOffset(it.toLong() - timing.startOffsetMs) }
        InspectorSlider(stringResource(Res.string.composer_page), pageDurationMs.toFloat(), 1_000f..MAX_PAGE_DURATION_MS.toFloat(), 5_000f, actions) {
            actions.adjustPageDuration(it.toLong() - pageDurationMs)
        }
        InspectorSlider(stringResource(Res.string.composer_volume), timing.volume, 0f..1f, 1f, actions) { actions.adjustVideoVolume(it - timing.volume) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(
                if (timing.muted) stringResource(Res.string.composer_muted) else stringResource(Res.string.composer_sound),
                if (timing.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                active = timing.muted,
                onClick = actions::toggleVideoMute,
            )
            ToolButton(stringResource(Res.string.composer_solo), Icons.Outlined.Headphones, active = timing.solo, onClick = actions::toggleVideoSolo)
            ToolButton(stringResource(Res.string.composer_loop), Icons.Outlined.Repeat, active = timing.loop, onClick = actions::toggleVideoLoop)
        }
    }
}

/**
 * The two trim handles, one per row but on the same scale.
 *
 * A `RangeSlider` would be the right gesture, but the two handles share one `onValueChange` and the
 * ViewModel clamps each end against the other — the still handle jumped when the other passed the
 * limit. Two rulers with the same domain say the same thing without that dispute.
 */
@Composable
private fun TimelineRangeRow(
    label: String,
    start: Long,
    end: Long,
    limit: Long,
    actions: ComposerEditorViewModel,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
) {
    InspectorSlider("$label ⟨", start.toFloat(), 0f..limit.toFloat(), 0f, actions) { onStart(it.toLong()) }
    InspectorSlider("$label ⟩", end.toFloat(), 0f..limit.toFloat(), limit.toFloat(), actions) { onEnd(it.toLong()) }
}

private fun Float.formatOne(): String = String.format(java.util.Locale.getDefault(), "%.1f", this)

private val LayerThumbnailSize = 44.dp

/**
 * The layers sheet.
 *
 * It is reordered by dragging the handle, not by "Back"/"Forward" buttons. As those buttons were also
 * the accessibility path, TalkBack's promise is now kept by custom actions on each row — the screen
 * reader announces them without needing to see them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(
    project: CompositionProject,
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ImagoColors.Surface, dragHandle = { SheetHandle() }) {
        LayersPanel(
            project = project,
            state = state,
            actions = actions,
            onSelectBackground = { actions.selectBackground(); onDismiss() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The document's structure: the layers in order, and the background at the end.
 *
 * A single definition for the two places it appears — the narrow screen's modal sheet and the fixed
 * left column. What changes is the container, not the list.
 */
@Composable
private fun LayersPanel(
    project: CompositionProject,
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    onSelectBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = remember(project.revision) { project.elements.sortedByDescending { it.zIndex } }
    // The index the dragged row would go to, if the finger let go now.
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { (LayerThumbnailSize + ImagoSpacing.Md).toPx() }
    Column(modifier.padding(horizontal = ImagoSpacing.Md)) {
        Text(stringResource(Res.string.composer_layers), style = MaterialTheme.typography.titleLarge, color = ImagoColors.TextPrimary)
        Text(
            stringResource(Res.string.composer_layers_hint),
            style = MaterialTheme.typography.bodySmall,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.padding(bottom = ImagoSpacing.Sm),
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(ordered, key = { _, element -> element.id }) { index, element ->
                LayerRow(
                    element = element,
                    index = index,
                    lastIndex = ordered.lastIndex,
                    selected = element.id == state.selectedElementId,
                    dragging = element.id == draggingId,
                    dragOffset = if (element.id == draggingId) dragOffset else 0f,
                    thumbnailUrl = actions::thumbnailUrl,
                    apiKey = actions::apiKey,
                    actions = actions,
                    onDragStart = { draggingId = element.id; dragOffset = 0f },
                    onDrag = { delta ->
                        dragOffset += delta
                        val steps = (dragOffset / rowHeightPx).roundToInt()
                        if (steps != 0) {
                            val target = (index + steps).coerceIn(0, ordered.lastIndex)
                            if (target != index) {
                                actions.reorderLayer(index, target)
                                dragOffset -= steps * rowHeightPx
                            }
                        }
                    },
                    onDragEnd = { draggingId = null; dragOffset = 0f },
                )
            }
        }
        BackgroundLayerRow(project, state, onSelectBackground)
    }
}

@Composable
private fun LayerRow(
    element: CompositionElement,
    index: Int,
    lastIndex: Int,
    selected: Boolean,
    dragging: Boolean,
    dragOffset: Float,
    thumbnailUrl: (String) -> String,
    apiKey: (String) -> String,
    actions: ComposerEditorViewModel,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val label = element.accessibilityLabel()
    // The semantics block is not a composition: the texts are read here, before it.
    val layerForward = stringResource(Res.string.composer_layer_forward)
    val layerBackward = stringResource(Res.string.composer_layer_backward)
    val moveLeft = stringResource(Res.string.composer_move_left)
    val moveRight = stringResource(Res.string.composer_move_right)
    val moveUp = stringResource(Res.string.composer_move_up)
    val moveDown = stringResource(Res.string.composer_move_down)
    val reorderLabel = stringResource(Res.string.composer_reorder_named, label)
    // The gesture block has `element.id` as its key and does not restart when the row changes position.
    // Without these, it would stay holding the `index` that existed when the drag started and the
    // second change of position would be computed from an already stale number.
    val startDrag by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val endDrag by rememberUpdatedState(onDragEnd)
    Row(
        Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(
                when {
                    dragging -> ImagoColors.Graphite
                    selected -> ImagoColors.Gold.copy(alpha = .14f)
                    else -> ImagoColors.SurfaceElevated
                },
            )
            .clickable { actions.selectElement(element.id) }
            .padding(ImagoSpacing.Sm)
            // The drag handle does not exist for TalkBack, and the "Back"/"Forward" buttons that played
            // that role left the row. The custom actions keep the promise: the screen reader announces
            // them without them taking up space.
            .semantics {
                contentDescription = label
                customActions = listOf(
                    CustomAccessibilityAction(layerForward) {
                        val possible = index > 0
                        if (possible) actions.reorderLayer(index, index - 1)
                        possible
                    },
                    CustomAccessibilityAction(layerBackward) {
                        val possible = index < lastIndex
                        if (possible) actions.reorderLayer(index, index + 1)
                        possible
                    },
                    CustomAccessibilityAction(moveLeft) { actions.moveElement(element.id, -.02f, 0f); true },
                    CustomAccessibilityAction(moveRight) { actions.moveElement(element.id, .02f, 0f); true },
                    CustomAccessibilityAction(moveUp) { actions.moveElement(element.id, 0f, -.02f); true },
                    CustomAccessibilityAction(moveDown) { actions.moveElement(element.id, 0f, .02f); true },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerThumbnail(element, thumbnailUrl, apiKey)
        Column(Modifier.weight(1f).padding(horizontal = ImagoSpacing.Sm)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (element.visible) ImagoColors.TextPrimary else ImagoColors.TextDisabled,
                maxLines = 1,
            )
            Text(
                element.layerSubtitle(),
                style = MaterialTheme.typography.labelSmall,
                color = ImagoColors.TextTertiary,
                maxLines = 1,
            )
        }
        IconButton(onClick = { actions.toggleElementLocked(element.id) }, modifier = Modifier.size(ImagoSizes.TouchTarget)) {
            Icon(
                if (element.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                if (element.locked) stringResource(Res.string.composer_unlock) else stringResource(Res.string.composer_lock),
                Modifier.size(ImagoSizes.IconSmall),
                tint = if (element.locked) ImagoColors.Gold else ImagoColors.TextSecondary,
            )
        }
        IconButton(onClick = { actions.toggleElementVisible(element.id) }, modifier = Modifier.size(ImagoSizes.TouchTarget)) {
            Icon(
                if (element.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                if (element.visible) stringResource(Res.string.composer_hide) else stringResource(Res.string.composer_show),
                Modifier.size(ImagoSizes.IconSmall),
                tint = ImagoColors.TextSecondary,
            )
        }
        IconButton(onClick = { actions.deleteElement(element.id) }, modifier = Modifier.size(ImagoSizes.TouchTarget)) {
            Icon(Icons.Outlined.Delete, stringResource(Res.string.composer_delete_element), Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.TextSecondary)
        }
        Box(
            Modifier
                .size(ImagoSizes.TouchTarget)
                .pointerInput(element.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startDrag() },
                        onDragEnd = { endDrag() },
                        onDragCancel = { endDrag() },
                    ) { change, delta -> change.consume(); drag(delta.y) }
                }
                .semantics { contentDescription = reorderLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.DragHandle, null, Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.TextTertiary)
        }
    }
}

/** The layer's thumbnail: the real image when there is one, otherwise a drawn summary of the element. */
@Composable
private fun LayerThumbnail(element: CompositionElement, thumbnailUrl: (String) -> String, apiKey: (String) -> String) {
    val shape = RoundedCornerShape(ImagoRadii.Small)
    Box(
        Modifier.size(LayerThumbnailSize).clip(shape).background(ImagoColors.Charcoal)
            .border(1.dp, ImagoColors.BorderSubtle, shape),
        contentAlignment = Alignment.Center,
    ) {
        when (element) {
            is CompositionElement.Photo, is CompositionElement.Video -> {
                val media = (element as? CompositionElement.Photo)?.media
                    ?: (element as CompositionElement.Video).media
                AsyncImage(
                    ImageRequest.Builder(LocalPlatformContext.current).data(thumbnailUrl(media.assetId))
                        .libraryAuth(apiKey(media.assetId)).build(),
                    null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
                if (element is CompositionElement.Video) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(ImagoSizes.IconSmall), tint = Color.White)
                }
            }
            is CompositionElement.Text -> Text(
                "T", style = MaterialTheme.typography.titleMedium, color = Color(element.colorArgb),
                fontFamily = rememberCompositionFontFamily(element.fontFamily, element.fontWeight),
            )
            is CompositionElement.Shape -> Canvas(Modifier.fillMaxSize().padding(ImagoSpacing.Sm)) {
                val colour = Color(element.strokeArgb)
                when (element.kind) {
                    ShapeKind.RECTANGLE -> drawRect(colour, style = Stroke(2.dp.toPx()))
                    ShapeKind.ELLIPSE -> drawOval(colour, style = Stroke(2.dp.toPx()))
                    ShapeKind.LINE -> drawLine(colour, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
                    ShapeKind.ARROW -> {
                        drawLine(colour, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
                        drawLine(colour, Offset(size.width, size.height / 2), Offset(size.width * .6f, size.height * .2f), 2.dp.toPx())
                        drawLine(colour, Offset(size.width, size.height / 2), Offset(size.width * .6f, size.height * .8f), 2.dp.toPx())
                    }
                }
            }
            is CompositionElement.Drawing -> Canvas(Modifier.fillMaxSize().padding(ImagoSpacing.Xs)) {
                if (element.points.size > 1) {
                    val path = Path().apply {
                        moveTo(element.points.first().x * size.width, element.points.first().y * size.height)
                        element.points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                    }
                    drawPath(path, Color(element.colorArgb), style = Stroke(2.dp.toPx()))
                }
            }
            is CompositionElement.MediaPlaceholder -> Canvas(Modifier.fillMaxSize().padding(ImagoSpacing.Xs)) {
                drawRect(
                    ImagoColors.TextTertiary,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    ),
                )
            }
        }
    }
}

/** The background layer, fixed at the end of the list just as on the stage it sits under everything. */
@Composable
private fun BackgroundLayerRow(project: CompositionProject, state: ComposerEditorUiState, onSelect: () -> Unit) {
    val background = project.pages.getOrNull(state.currentPage)?.backgroundOverride ?: project.background
    val shape = RoundedCornerShape(ImagoRadii.Medium)
    Row(
        Modifier.fillMaxWidth().padding(vertical = ImagoSpacing.Sm).clip(shape)
            .background(if (state.backgroundSelected) ImagoColors.Gold.copy(alpha = .14f) else ImagoColors.SurfaceElevated)
            .clickable(onClick = onSelect).padding(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LayerThumbnailSize).clip(RoundedCornerShape(ImagoRadii.Small))
                .background(
                    when (background) {
                        is CompositionBackground.Solid -> Color(background.argb)
                        is CompositionBackground.Gradient -> Color(background.startArgb)
                        is CompositionBackground.Photo -> ImagoColors.Charcoal
                    },
                )
                .border(1.dp, ImagoColors.BorderSubtle, RoundedCornerShape(ImagoRadii.Small)),
            contentAlignment = Alignment.Center,
        ) {
            if (background is CompositionBackground.Photo) {
                Icon(Icons.Outlined.Image, null, Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.TextSecondary)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = ImagoSpacing.Sm)) {
            Text(stringResource(Res.string.composer_background), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextPrimary)
            Text(
                when (background) {
                    is CompositionBackground.Solid -> stringResource(Res.string.composer_solid_color)
                    is CompositionBackground.Gradient -> stringResource(Res.string.composer_gradient_angle, background.angleDegrees.formatOne())
                    is CompositionBackground.Photo -> {
                        val blur = background.blurRadius
                        if (blur > 0f) {
                            stringResource(Res.string.composer_photo_blur, blur.formatOne())
                        } else {
                            stringResource(Res.string.composer_photo)
                        }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = ImagoColors.TextTertiary,
            )
        }
        Icon(Icons.Outlined.Wallpaper, null, Modifier.size(ImagoSizes.IconSmall), tint = ImagoColors.TextTertiary)
    }
}

@Composable
private fun CompositionElement.layerSubtitle(): String {
    val page = stringResource(Res.string.composer_page_n, transform.bounds.x.toInt() + 1)
    val flags = listOfNotNull(
        Res.string.composer_flag_hidden.takeIf { !visible },
        Res.string.composer_flag_locked.takeIf { locked },
        Res.string.composer_flag_grouped.takeIf { groupId != null },
        Res.string.composer_flag_repeated.takeIf { repeatOnPages.isNotEmpty() },
    ).map { stringResource(it) }
    return (listOf(page) + flags).joinToString(" · ")
}

@Composable
private fun PageControls(
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    onPageDeleted: (String) -> Unit,
) {
    val project = state.project ?: return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            project.pages.forEach { page ->
                PageThumbnail(
                    project = project,
                    page = page,
                    current = page.index == state.currentPage,
                    actions = actions,
                    onClick = { actions.selectPage(page.index) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(stringResource(Res.string.composer_new), Icons.Outlined.Add, onClick = actions::addPage)
            ToolButton(stringResource(Res.string.composer_move_back), Icons.AutoMirrored.Outlined.ArrowBack) { actions.movePage(-1) }
            ToolButton(stringResource(Res.string.composer_move_forward), Icons.AutoMirrored.Outlined.ArrowForward) { actions.movePage(1) }
            ToolButton(
                stringResource(Res.string.composer_safe_zones),
                if (project.safeZonesVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                active = project.safeZonesVisible,
                onClick = actions::toggleSafeZones,
            )
            val deletedLabel = stringResource(Res.string.composer_page_deleted, state.currentPage + 1)
            ToolButton(stringResource(Res.string.composer_delete), Icons.Outlined.Delete, tint = ImagoColors.Danger) {
                actions.deleteCurrentPage()
                onPageDeleted(deletedLabel)
            }
        }
    }
}

/** The height of the page strip. The width comes from the project's format. */
private val PageStripHeight = 72.dp

/**
 * A page in miniature: its background, and what is on top.
 *
 * It is not the exporter's rasteriser running small — that would cost one export per page just to
 * draw a strip. It is the real background with the elements' patches in the right places, which is
 * enough to recognise the page one is looking for. A number inside a button did not tell page 7 from 9.
 */
@Composable
private fun PageThumbnail(
    project: CompositionProject,
    page: CompositionPage,
    current: Boolean,
    actions: ComposerEditorViewModel,
    onClick: () -> Unit,
) {
    val width = PageStripHeight * project.format.pixelWidth / project.format.pixelHeight
    val pageLabel = stringResource(Res.string.composer_page_n, page.index + 1)
    Box(
        Modifier
            .size(width, PageStripHeight)
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .border(
                width = if (current) 2.dp else 1.dp,
                color = if (current) ImagoColors.Gold else ImagoColors.BorderVisible,
                shape = RoundedCornerShape(ImagoRadii.Small),
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = pageLabel },
    ) {
        CompositionPageCanvas(
            project = project,
            page = page,
            width = width,
            height = PageStripHeight,
            thumbnailUrl = actions::thumbnailUrl,
            apiKey = actions::apiKey,
        )
        Surface(
            color = Color.Black.copy(alpha = .6f),
            shape = RoundedCornerShape(topStart = ImagoRadii.Small),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Text(
                "${page.index + 1}",
                fontSize = 10.sp,
                color = ImagoColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** On which pages this element appears — its own, plus those it was repeated on. */
internal fun CompositionElement.pagesOccupied(): Set<Int> =
    repeatOnPages.takeIf { it.isNotEmpty() } ?: setOf(transform.bounds.x.toInt())

/** Where the chosen media goes. The background stopped requiring a pass through an element. */
internal sealed interface MediaPickerTarget {
    data class Element(val replaceId: String?) : MediaPickerTarget
    data class Background(val currentPageOnly: Boolean) : MediaPickerTarget
}

/**
 * Where the media comes from: the whole library, over the composer.
 *
 * It is not a sheet nor a drawer — it is the same library screen, with the albums, the chips, the
 * search and the year ruler. Choosing a photo for a composition is the same work as finding it in the
 * library, and the three-column grid that used to be here gave none of the tools that is done with.
 *
 * It is overlaid instead of replacing the composer so the editor is not torn down and rebuilt on every
 * trip to the picker — the armed drawing mode, the page on stage and the selection stay where they
 * were. As it is composed after it, it is also the first to hear "back", which is what lets the picker
 * close the album, the search and the selection before closing itself.
 */
@Composable
private fun ComposerMediaPicker(
    target: MediaPickerTarget,
    onDismiss: () -> Unit,
    onPicked: (List<ComposerMedia>) -> Unit,
) {
    val forBackground = target is MediaPickerTarget.Background
    Surface(
        color = ImagoColors.Background,
        modifier = Modifier
            .fillMaxSize()
            // Opaque is not enough: without something consuming the touches, those falling outside the
            // grid hit the composer buttons left underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LibraryPickerRoute(
            title = if (forBackground) stringResource(Res.string.composer_choose_background) else stringResource(Res.string.composer_add_media),
            subtitle = if (forBackground) stringResource(Res.string.composer_background_photos_only) else null,
            allowsVideo = !forBackground,
            multiple = !forBackground,
            onConfirm = { chosen -> onPicked(chosen.map(AssetUiModel::toComposerMedia)) },
            onCancel = onDismiss,
        )
    }
}

@Composable
private fun TemplateDialog(onDismiss: () -> Unit, onSave: (String, Boolean) -> Unit) {
    val defaultName = stringResource(Res.string.composer_new_template)
    var name by remember { mutableStateOf(defaultName) }
    var includeMedia by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(Res.string.composer_save_as_template)) },
        text = {
            Column {
                TextField(name, { name = it }, label = { Text(stringResource(Res.string.composer_name)) }, singleLine = true)
                Row(Modifier.fillMaxWidth().clickable { includeMedia = !includeMedia }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (includeMedia) Icons.Outlined.Check else Icons.Outlined.Image, null)
                    Text(if (includeMedia) stringResource(Res.string.composer_include_media) else stringResource(Res.string.composer_replace_media_placeholders), Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(name, includeMedia) }) { Text(stringResource(Res.string.composer_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_cancel)) } },
    )
}

@Composable
internal fun ToolButton(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    tint: Color = ImagoColors.TextSecondary,
    onClick: () -> Unit,
) {
    // The filled capsule marks what is on — drawing mode, the adjustments drawer. Over a stage of
    // arbitrary colour, a difference in opacity alone does not read.
    val content = if (active) ImagoColors.TextPrimary else tint
    Column(
        Modifier.clip(RoundedCornerShape(ImagoRadii.Small))
            .background(if (active) ImagoColors.Graphite else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = content)
        Text(label, fontSize = 10.sp, color = content)
    }
}

/**
 * A bar button that opens a menu.
 *
 * It exists because a row of twenty-five buttons is not a toolbar — it is a list lying on its side, in
 * which what one is looking for is almost always off screen. What belongs to the same subject now
 * fits behind a name, and the row reads at once again.
 */
@Composable
private fun ToolMenuButton(
    label: String,
    icon: ImageVector,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(label, icon, active = open) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

/** An entry of a [ToolMenuButton]. It closes the menu on its own: none of these actions is repeatable. */
@Composable
private fun ToolMenuItem(
    label: String,
    icon: ImageVector,
    dismiss: () -> Unit,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        onClick = { dismiss(); onClick() },
    )
}

/** The cut between two groups of the same row. Without it, insert and export read as neighbours. */
@Composable
private fun ToolGroupDivider() {
    Box(
        Modifier
            .padding(horizontal = ImagoSpacing.Sm, vertical = ImagoSpacing.Sm)
            .width(1.dp)
            .height(28.dp)
            .background(ImagoColors.BorderVisible),
    )
}

@Composable
private fun CompositionElement.accessibilityLabel(): String = when (this) {
    is CompositionElement.MediaPlaceholder -> stringResource(Res.string.composer_media_slot)
    is CompositionElement.Photo -> stringResource(Res.string.composer_photo_named, media.fileName)
    is CompositionElement.Video -> stringResource(Res.string.composer_video_named, media.fileName)
    is CompositionElement.Text -> stringResource(Res.string.composer_text_named, text)
    is CompositionElement.Shape -> stringResource(
        when (kind) {
            ShapeKind.RECTANGLE -> Res.string.composer_rectangle
            ShapeKind.ELLIPSE -> Res.string.composer_circle
            ShapeKind.LINE -> Res.string.composer_line
            ShapeKind.ARROW -> Res.string.composer_arrow
        },
    )
    is CompositionElement.Drawing -> stringResource(Res.string.composer_freehand)
}

/** What the stroke is called in the stage's notice. */
@Composable
private fun StrokeKind.label(): String = when (this) {
    StrokeKind.PEN -> stringResource(Res.string.composer_pen)
    StrokeKind.MARKER -> stringResource(Res.string.composer_marker)
    StrokeKind.ERASER -> stringResource(Res.string.composer_eraser)
}

/**
 * A photo that came from another device and has not been found here yet is not an error. It stays in
 * the project, and appears when the library is linked or the photo reaches this device.
 */
@Composable
internal fun unavailableMediaText(assetId: String): String =
    if (runCatching { eu.studio742.imago.core.model.AssetReference.parse(assetId).isRemote }.getOrDefault(false)) {
        stringResource(Res.string.composer_media_elsewhere)
    } else {
        stringResource(Res.string.composer_media_unavailable)
    }
