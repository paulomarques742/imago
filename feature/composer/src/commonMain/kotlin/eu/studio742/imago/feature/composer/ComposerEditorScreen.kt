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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.CropPortrait
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
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Slider
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.isSpecified
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
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
import eu.studio742.imago.core.composition.boundsOnPage
import eu.studio742.imago.core.composition.repeatOriginPage
import eu.studio742.imago.core.composition.visualBounds
import eu.studio742.imago.core.composition.renderElements
import eu.studio742.imago.core.composition.memberIds
import eu.studio742.imago.core.composition.adjustDivider
import eu.studio742.imago.core.composition.mediaFrame
import eu.studio742.imago.core.composition.boundingBox
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.FrameStyle
import eu.studio742.imago.core.composition.MAX_BACKGROUND_BLUR
import eu.studio742.imago.core.composition.CompositionLayout
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.backgroundBlurDivisor
import eu.studio742.imago.core.composition.PlacementCrop
import eu.studio742.imago.core.composition.ShapeKind
import eu.studio742.imago.core.composition.usedColors
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.composition.StrokePoint
import eu.studio742.imago.core.composition.hasVariablePressure
import eu.studio742.imago.core.composition.strokeWidthAt
import eu.studio742.imago.core.composition.paintedBounds
import eu.studio742.imago.core.composition.withColorAlpha
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
import eu.studio742.imago.core.designsystem.ImagoUi
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

/**
 * Owns the eyedropper request and hands it to every colour picker below through [LocalEyedropper]:
 * the pickers sit in dialogs and inspectors all over the screen, and none of them can reach the
 * stage by itself.
 */
@Composable
internal fun ComposerEditorScreen(
    state: ComposerEditorUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    actions: ComposerEditorViewModel,
    editingPhotoId: String?,
    onEditPhotoId: (String?) -> Unit,
) {
    var request by remember { mutableStateOf<((Long?) -> Unit)?>(null) }
    val requestEyedropper: (((Long?) -> Unit)) -> Unit = remember { { callback -> request = callback } }
    CompositionLocalProvider(LocalEyedropper provides requestEyedropper) {
        ComposerEditorScreenBody(
            state, snackbar, onBack, actions, editingPhotoId, onEditPhotoId,
            eyedropperActive = request != null,
            onEyedropperResult = { color -> request?.invoke(color); request = null },
        )
    }
}

private enum class ComposerContextKind { INSERT, PAGES, INSPECTOR, SELECTION, NONE }
private data class ComposerContext(val kind: ComposerContextKind, val state: ComposerEditorUiState)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerEditorScreenBody(
    state: ComposerEditorUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    actions: ComposerEditorViewModel,
    editingPhotoId: String?,
    onEditPhotoId: (String?) -> Unit,
    eyedropperActive: Boolean,
    onEyedropperResult: (Long?) -> Unit,
) {
    val uiColors = ImagoUi.colors
    // With room, the composer stops stacking everything at the bottom of the screen: the structure
    // goes to the left, the properties to the right and the stage keeps the middle. It is the only
    // layout where what is being changed is seen while changing it.
    val composerWindow = LocalImagoWindow.current
    val inspectorRail = composerWindow.prefersSidePanel
    val layersRail = inspectorRail && composerWindow.width >= LayersPaneMinWindow
    var mediaPicker by remember { mutableStateOf<MediaPickerTarget?>(null) }
    var addingElements by rememberSaveable { mutableStateOf(false) }
    var pageTools by rememberSaveable { mutableStateOf(false) }
    var selectionProperties by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedElementIds, state.editingGroupId) { selectionProperties = false }
    // `drawing`, `positioningBackground` and `multiSelect` stay in `remember`: they are armed gesture
    // modes, and resuming one of them after a rotation — with the stage already another size — would
    // hand the finger back to a canvas that changed under it.
    var drawing by remember { mutableStateOf(false) }
    var drawingKind by remember { mutableStateOf(StrokeKind.PEN) }
    var drawingColor by rememberSaveable { mutableLongStateOf(0xFFFFFFFF) }
    var drawingWidth by rememberSaveable { mutableStateOf(6f) }
    var choosingDrawingColor by remember { mutableStateOf(false) }
    // Shapes work like drawing: choosing one arms the stage, and the shape is born where the finger
    // taps or drags. It is a mode of its own, in `remember` for the same reason as `drawing`.
    var shapeKind by remember { mutableStateOf<ShapeKind?>(null) }
    var shapeFill by rememberSaveable { mutableLongStateOf(0xFFFFFFFF) }
    var shapeStroke by rememberSaveable { mutableLongStateOf(0xFFFFFFFF) }
    // A rectangle's outline starts off; a line's thickness is the line, and starts visible.
    var shapeOutlineWidth by rememberSaveable { mutableStateOf(0f) }
    var shapeLineWidth by rememberSaveable { mutableStateOf(4f) }
    // Which of the two colours the picker is choosing: null for none, true for the fill.
    var choosingShapeFill by remember { mutableStateOf<Boolean?>(null) }
    var shapeOptionsOpen by remember { mutableStateOf(false) }
    // The mode bubble can be dragged, to draw where it would otherwise sit.
    var bubbleOffset by remember { mutableStateOf(Offset.Zero) }
    var bubbleSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var stageBoxSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var outlining by remember(state.project?.id, state.selectedElementId) { mutableStateOf(false) }
    var outlineOffset by remember(state.project?.id) { mutableStateOf(Offset.Zero) }
    var outlineSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(drawing, drawingKind, drawingColor, drawingWidth, state.currentPage, state.project?.id) { actions.finishDrawingSession() }
    DisposableEffect(actions) { onDispose { actions.finishDrawingSession() } }
    choosingShapeFill?.let { forFill ->
        ColorPickerDialog(
            title = stringResource(if (forFill) Res.string.composer_shape_fill else Res.string.composer_shape_outline),
            initialArgb = if (forFill && (shapeFill ushr 24 and 255) == 0L) shapeFill.withColorAlpha(1f) else if (forFill) shapeFill else shapeStroke,
            palette = state.brandKit?.paletteArgb.orEmpty(),
            compositionColors = state.project?.usedColors().orEmpty(),
            onDismiss = { choosingShapeFill = null },
            onConfirm = { if (forFill) shapeFill = it else shapeStroke = it; choosingShapeFill = null },
        )
    }
    if (choosingDrawingColor) {
        ColorPickerDialog(
            title = stringResource(Res.string.composer_stroke_color),
            initialArgb = drawingColor,
            palette = state.brandKit?.paletteArgb.orEmpty(),
            compositionColors = state.project?.usedColors().orEmpty(),
            onDismiss = { choosingDrawingColor = false },
            onConfirm = { drawingColor = it; choosingDrawingColor = false },
        )
    }
    var positioningBackground by remember { mutableStateOf(false) }
    // The twin of the one above, on the elements' side: framing the photo inside the mask its sizing
    // gave it.
    var positioningMedia by remember { mutableStateOf(false) }
    // When on, a tap on the stage adds to and removes from the selection instead of replacing it. It
    // turns off on its own when the selection empties: a mode left on with nothing chosen is a forgotten mode.
    val multiSelect = state.multiSelect
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
    // Insets describe the space available for fitting the page. The viewport preserves the current
    // scale and position while tools change or fade, so a gesture never moves its own reference frame.
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
    val stopDrawing = {
        actions.finishDrawingSession()
        drawing = false
        shapeKind = null
        outlining = false
    }
    val toggleOutline = {
        val next = !outlining
        stopDrawing()
        positioningBackground = false
        positioningMedia = false
        outlining = next
    }
    val startDrawing: (StrokeKind) -> Unit = { kind ->
        outlining = false
        addingElements = false
        drawingKind = kind
        drawing = true
        shapeKind = null
        positioningBackground = false
        positioningMedia = false
    }
    val startShape: (ShapeKind) -> Unit = { kind ->
        outlining = false
        addingElements = false
        actions.finishDrawingSession()
        drawing = false
        shapeKind = kind
        positioningBackground = false
        positioningMedia = false
    }
    // The two framing modes exclude each other: one moves the page background, the other the element,
    // and both want the same finger on the same stage.
    val toggleBackgroundPositioning = {
        positioningBackground = !positioningBackground
        if (positioningBackground) { drawing = false; shapeKind = null; positioningMedia = false }
    }
    val toggleMediaPositioning = {
        positioningMedia = !positioningMedia
        if (positioningMedia) { drawing = false; shapeKind = null; positioningBackground = false }
    }
    var saveMenu by remember { mutableStateOf(false) }
    var templateDialog by rememberSaveable { mutableStateOf(false) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var layersSheet by rememberSaveable { mutableStateOf(false) }
    var groupDialog by rememberSaveable { mutableStateOf(false) }
    var brandKitDialog by rememberSaveable { mutableStateOf(false) }
    var brandEditor by rememberSaveable { mutableStateOf(false) }
    val project = state.displayProject()
    BackHandler(enabled = state.textSession != null) { actions.cancelTextEditing() }
    val requestedContext = ComposerContext(when {
        drawing || shapeKind != null -> ComposerContextKind.NONE
        addingElements -> ComposerContextKind.INSERT
        pageTools -> ComposerContextKind.PAGES
        state.hasSelectionTools() -> ComposerContextKind.SELECTION
        !inspectorRail && (state.backgroundSelected || state.selectedElementId != null) -> ComposerContextKind.INSPECTOR
        else -> ComposerContextKind.NONE
    }, state)
    var retainedContext by remember { mutableStateOf(requestedContext) }
    if (requestedContext.kind != ComposerContextKind.NONE) SideEffect { retainedContext = requestedContext }
    val shownContext = if (requestedContext.kind == ComposerContextKind.NONE) retainedContext else requestedContext
    // Leaving the background (selecting something else, or nothing) has to disarm position mode —
    // otherwise the gesture stayed ready to move an image no longer in view in the inspector.
    LaunchedEffect(state.backgroundSelected) {
        if (!state.backgroundSelected) positioningBackground = false
    }
    // Switching element — or choosing more than one — disarms framing, for the same reason leaving the
    // background disarms its own: a mode armed over something else is a forgotten mode.
    LaunchedEffect(state.selectedElementId, state.selectedElementIds.size) {
        if (state.selectedElementId != null) addingElements = false
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
                .imePadding()
                .focusRequester(keyboardFocus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (state.textSession != null) {
                        return@onKeyEvent when {
                            event.key == Key.Escape -> { actions.cancelTextEditing(); true }
                            event.isCtrlPressed && event.key == Key.Enter -> { actions.confirmTextEditing(); true }
                            else -> false
                        }
                    }
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
                Column(Modifier.alpha(chromeAlpha).onSizeChanged { measured ->
                        val height = with(chromeDensity) { measured.height.toDp() }
                        if (height > 0.dp && height != measuredChrome.top) {
                            measuredChrome = measuredChrome.copy(top = height)
                        }
                    },
                ) {
                TopAppBar(
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
                        if (state.saveFailed) {
                            TextButton(onClick = actions::saveDraft) { Text(stringResource(Res.string.composer_retry_save), color = uiColors.error) }
                        } else if (state.textSession != null) {
                            Text(stringResource(Res.string.composer_text_draft), color = uiColors.secondaryText, style = MaterialTheme.typography.labelSmall)
                        } else AutoSaveStatus(
                            isSaving = state.isSaving,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        IconButton(onClick = actions::undo, enabled = state.canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(Res.string.composer_undo))
                        }
                        IconButton(onClick = actions::redo, enabled = state.canRedo) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(Res.string.composer_redo))
                        }
                        Box {
                            IconButton(onClick = { stopDrawing(); saveMenu = true }) {
                                Icon(Icons.Outlined.Save, stringResource(Res.string.composer_save))
                            }
                            DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                                ToolMenuItem(stringResource(Res.string.composer_export), Icons.Outlined.Download, { saveMenu = false }) { exportDialog = true }
                                ToolMenuItem(stringResource(Res.string.composer_save_draft), Icons.Outlined.Save, { saveMenu = false }, actions::saveDraft)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = uiColors.surface,
                        titleContentColor = uiColors.text,
                        navigationIconContentColor = uiColors.text,
                        actionIconContentColor = uiColors.text,
                    ),
                )
                }
            },
            bottomBar = {
                ComposerNavigationChrome(
                    contextVisible = requestedContext.kind != ComposerContextKind.NONE,
                    immersive = adjusting || stageBusy,
                    contentAlpha = panelAlpha,
                    onHeightChanged = { height ->
                        if (height != measuredChrome.bottom) measuredChrome = measuredChrome.copy(bottom = height)
                    },
                    context = {
                        when (shownContext.kind) {
                            ComposerContextKind.SELECTION -> {
                                Column {
                                    ComposerSelectionToolbar(shownContext.state, actions, selectionProperties,
                                        if (inspectorRail) null else { { selectionProperties = !selectionProperties } },
                                        onAddGridMedia = { mediaPicker = MediaPickerTarget.Grid(it) })
                                    if (selectionProperties && !inspectorRail) {
                                        ComposerInspector(state = shownContext.state, actions = actions, rail = false,
                                            multiSelect = multiSelect, positioningBackground = false, positioningMedia = false,
                                            onToggleMultiSelect = actions::toggleMultiSelect,
                                            onTogglePositioning = toggleBackgroundPositioning,
                                            onTogglePositioningMedia = toggleMediaPositioning,
                                            onPickImage = { mediaPicker = MediaPickerTarget.Background(it) },
                                            onEditText = { actions.beginTextEditing() }, onEditPhoto = { onEditPhotoId(state.selectedElementId) },
                                            outlineActive = outlining, onOutline = toggleOutline,
                                            onRequestGroup = { groupDialog = true }, onDeleted = {})
                                    }
                                }
                            }
                            ComposerContextKind.INSERT -> {
                                ComposerToolRow(
                                    Modifier.alpha(chromeAlpha),
                                    contentPadding = PaddingValues(horizontal = ImagoSpacing.Xs, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ToolButton(stringResource(Res.string.composer_media), Icons.Outlined.Image) {
                                        stopDrawing()
                                        mediaPicker = MediaPickerTarget.Element(null)
                                    }
                                    ToolButton(stringResource(Res.string.composer_grid), Icons.Outlined.GridView) {
                                        stopDrawing()
                                        mediaPicker = MediaPickerTarget.NewGrid(state.currentPage)
                                    }
                                    ToolButton(stringResource(Res.string.composer_text), Icons.Outlined.TextFields, onClick = { stopDrawing(); actions.addText(textPlaceholder, edit = true) })
                                    ToolMenuButton(stringResource(Res.string.composer_shape), Icons.Outlined.Rectangle, onOpen = stopDrawing) { dismiss ->
                                        ToolMenuItem(stringResource(Res.string.composer_rectangle), Icons.Outlined.Rectangle, dismiss) { startShape(ShapeKind.RECTANGLE) }
                                        ToolMenuItem(stringResource(Res.string.composer_circle), Icons.Outlined.Circle, dismiss) { startShape(ShapeKind.ELLIPSE) }
                                        ToolMenuItem(stringResource(Res.string.composer_arrow), Icons.AutoMirrored.Outlined.ArrowForward, dismiss) { startShape(ShapeKind.ARROW) }
                                        ToolMenuItem(stringResource(Res.string.composer_line), Icons.Outlined.Remove, dismiss) { startShape(ShapeKind.LINE) }
                                    }
                                    ToolMenuButton(stringResource(Res.string.composer_drawing), Icons.Outlined.Brush) { dismiss ->
                                        ToolMenuItem(stringResource(Res.string.composer_pen), Icons.Outlined.Brush, dismiss) { startDrawing(StrokeKind.PEN) }
                                        ToolMenuItem(stringResource(Res.string.composer_marker), Icons.Outlined.Brush, dismiss) { startDrawing(StrokeKind.MARKER) }
                                        ToolMenuItem(stringResource(Res.string.composer_eraser), Icons.Outlined.Remove, dismiss) { startDrawing(StrokeKind.ERASER) }
                                    }
                                }
                            }
                            ComposerContextKind.INSPECTOR -> {
                                ComposerInspector(
                                    state = shownContext.state,
                                    actions = actions,
                                    rail = false,
                                    multiSelect = multiSelect,
                                    positioningBackground = positioningBackground,
                                    positioningMedia = positioningMedia,
                                    onToggleMultiSelect = actions::toggleMultiSelect,
                                    onTogglePositioning = toggleBackgroundPositioning,
                                    onTogglePositioningMedia = toggleMediaPositioning,
                                    onPickImage = { currentPageOnly -> mediaPicker = MediaPickerTarget.Background(currentPageOnly) },
                                    onEditText = { actions.beginTextEditing() },
                                    onEditPhoto = { onEditPhotoId(state.selectedElementId) },
                                    outlineActive = outlining,
                                    onOutline = toggleOutline,
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
                            ComposerContextKind.PAGES -> PageControls(shownContext.state, actions) { label ->
                                scope.launch {
                                    val result = snackbar.showSnackbar(label, undoLabel, withDismissAction = true)
                                    if (result == SnackbarResult.ActionPerformed) actions.undo()
                                }
                            }
                            ComposerContextKind.NONE -> Unit
                        }
                    },
                    navigation = {
                        if (state.textSession?.contentEditing != true) ComposerNavigationBar(
                            onAdd = { stopDrawing(); pageTools = false; addingElements = !addingElements },
                            immersive = adjusting || stageBusy,
                            modifier = Modifier.alpha(chromeAlpha),
                            layers = {
                                if (!layersRail) ToolButton(stringResource(Res.string.composer_layers), Icons.Outlined.Layers) {
                                    stopDrawing(); addingElements = false; pageTools = false; layersSheet = true
                                }
                            },
                            background = {
                                ToolButton(stringResource(Res.string.composer_background), Icons.Outlined.Wallpaper, active = state.backgroundSelected) {
                                    stopDrawing(); addingElements = false; pageTools = false; actions.selectBackground()
                                }
                            },
                            pages = {
                                ToolButton(stringResource(Res.string.composer_pages), Icons.Outlined.GridView, active = pageTools) {
                                    stopDrawing(); addingElements = false; pageTools = !pageTools
                                }
                            },
                            more = {
                                ToolMenuButton(stringResource(Res.string.composer_more), Icons.Outlined.MoreHoriz, onOpen = stopDrawing) { dismiss ->
                                    ToolMenuItem(stringResource(Res.string.composer_save_as_template), Icons.Outlined.Save, dismiss) { templateDialog = true }
                                    ToolMenuItem(stringResource(Res.string.composer_brand), Icons.Outlined.Palette, dismiss) { brandKitDialog = true }
                                    val selected = project?.elements?.firstOrNull { it.id == state.selectedElementId }
                                    if (selected is CompositionElement.Photo || selected is CompositionElement.Video || selected is CompositionElement.MediaPlaceholder) {
                                        ToolMenuItem(stringResource(Res.string.composer_replace_media), Icons.Outlined.Image, dismiss) { mediaPicker = MediaPickerTarget.Element(selected.id) }
                                    }
                                    if (selected != null && selected !is CompositionElement.Video) {
                                        val repeating = actions.selectionRepeatsOnAllPages()
                                        ToolMenuItem(stringResource(if (repeating) Res.string.composer_stop_repeating else Res.string.composer_repeat_all_pages),
                                            Icons.Outlined.Repeat, dismiss,
                                            if (repeating) actions::stopRepeatingSelected else actions::repeatOnAllPages)
                                    }
                                    ToolMenuItem(
                                        if (multiSelect) stringResource(Res.string.composer_end_multi_select) else stringResource(Res.string.composer_select_multiple),
                                        Icons.Outlined.SelectAll, dismiss,
                                    ) { addingElements = false; actions.toggleMultiSelect() }
                                    if (project?.elements?.any { it is CompositionElement.Video && it.visible && it.pageIndex == state.currentPage } == true) {
                                        ToolMenuItem(stringResource(Res.string.composer_preview), Icons.Outlined.PlayArrow, dismiss) { actions.previewCurrentPage() }
                                    }
                                }
                            },
                        )
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().onSizeChanged { stageBoxSize = it }) {
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
                                .background(uiColors.surface),
                        )
                        ComposerPaneDivider()
                    }
                    ComposerCanvas(
                        project = project,
                        state = state,
                        drawing = drawing,
                        drawingKind = drawingKind,
                        drawingColor = drawingColor,
                        drawingWidth = drawingWidth,
                        eyedropper = eyedropperActive,
                        onSample = onEyedropperResult,
                        shapeKind = shapeKind,
                        shapeFill = shapeFill,
                        shapeStroke = shapeStroke,
                        shapeStrokeWidth = if (shapeKind == ShapeKind.LINE || shapeKind == ShapeKind.ARROW) shapeLineWidth else shapeOutlineWidth,
                        onShape = { kind, bounds, rotation ->
                            actions.addShape(
                                kind, bounds, rotation, shapeFill, shapeStroke,
                                if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) shapeLineWidth else shapeOutlineWidth,
                            )
                            // One shape per choice: it is selected next, ready for the inspector's size
                            // and colour, and the stage goes back to selecting.
                            shapeKind = null
                        },
                        multiSelect = multiSelect,
                        positioningBackground = positioningBackground,
                        positioningMedia = positioningMedia,
                        previewUrl = actions::previewUrl,
                        thumbnailUrl = actions::thumbnailUrl,
                        videoPlaybackUrl = actions::videoPlaybackUrl,
                        apiKey = actions::apiKey,
                        actions = actions,
                        onDrawing = {
                            if (drawingKind == StrokeKind.ERASER) actions.eraseDrawing(it) else actions.addDrawing(it, drawingKind, drawingColor, drawingWidth)
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
                                .background(uiColors.surface)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            ComposerInspector(
                                state = state,
                                actions = actions,
                                rail = true,
                                multiSelect = multiSelect,
                                positioningBackground = positioningBackground,
                                positioningMedia = positioningMedia,
                                onToggleMultiSelect = actions::toggleMultiSelect,
                                onTogglePositioning = toggleBackgroundPositioning,
                                onTogglePositioningMedia = toggleMediaPositioning,
                                onPickImage = { currentPageOnly -> mediaPicker = MediaPickerTarget.Background(currentPageOnly) },
                                onEditText = { actions.beginTextEditing() },
                                onEditPhoto = { onEditPhotoId(state.selectedElementId) },
                                outlineActive = outlining,
                                onOutline = toggleOutline,
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
            if ((state.multiSelect || state.editingGroupId != null) && !drawing && shapeKind == null && !outlining && !eyedropperActive) {
                ComposerMultiSelectionHint(Modifier.align(Alignment.TopCenter)
                    .padding(top = measuredChrome.top + 8.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 420.dp).alpha(chromeAlpha), state.editingGroupId != null, actions::finishEditingGroup, editingGrid = state.editedLayout() != null)
            }
            if (outlining && state.outlineSelection().isNotEmpty()) {
                val outlineDensity = LocalDensity.current
                val leftPx = with(outlineDensity) { if (layersRail) LayersPaneWidth.toPx() else 0f }
                val rightPx = with(outlineDensity) { if (inspectorRail) InspectorPaneWidth.toPx() else 0f }
                val rootWidth = stageBoxSize.width.takeIf { it > 0 }?.toFloat() ?: with(outlineDensity) { composerWindow.width.toPx() }
                val usableWidth = (rootWidth - leftPx - rightPx).coerceAtLeast(1f)
                val top = measuredChrome.top + 8.dp
                val usableHeight = (stageBoxSize.height - with(outlineDensity) { (top + measuredChrome.bottom).toPx() }).coerceAtLeast(1f)
                val availableSize = androidx.compose.ui.unit.IntSize(usableWidth.roundToInt(), usableHeight.roundToInt())
                val visibleOffset = clampBubble(outlineOffset, availableSize, outlineSize).let { it.copy(y = it.y.coerceAtLeast(0f)) }
                ComposerOutlineBubble(state, actions, visible = !eyedropperActive,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = top)
                        .widthIn(max = with(outlineDensity) { minOf(360.dp.toPx(), (usableWidth - 24.dp.toPx()).coerceAtLeast(1f)).toDp() })
                        .offset { IntOffset(((leftPx - rightPx) / 2f + visibleOffset.x).roundToInt(), visibleOffset.y.roundToInt()) }
                        .alpha(if (state.stageGesture) 0f else 1f),
                    onSize = { outlineSize = it },
                    onDrag = { drag ->
                        outlineOffset = clampBubble(visibleOffset + drag, availableSize, outlineSize)
                            .let { it.copy(y = it.y.coerceAtLeast(0f)) }
                    }, onClose = { outlining = false })
            }
            AnimatedVisibility(
                visible = (drawing || shapeKind != null) && project != null,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = measuredChrome.top + 8.dp, start = 12.dp, end = 12.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(bubbleOffset.x.roundToInt(), bubbleOffset.y.roundToInt()) }
                    .alpha(chromeAlpha),
                enter = fadeIn(imagoTween(ImagoMotion.Instant)),
                exit = fadeOut(imagoTween(ImagoMotion.Instant)),
            ) {
                ComposerModeBubble(
                    onSize = { bubbleSize = it },
                    onDrag = { drag -> bubbleOffset = clampBubble(bubbleOffset + drag, stageBoxSize, bubbleSize) },
                ) {
                    val activeShape = shapeKind
                    if (activeShape != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolMenuButton(activeShape.label(), activeShape.icon()) { dismiss ->
                                ShapeKind.entries.forEach { kind ->
                                    ToolMenuItem(kind.label(), kind.icon(), dismiss) { shapeKind = kind }
                                }
                            }
                            val isLine = activeShape == ShapeKind.LINE || activeShape == ShapeKind.ARROW
                            // A line has a single colour, its own; the others have a fill and an outline.
                            if (!isLine) ColorDot(shapeFill, stringResource(Res.string.composer_shape_fill)) { choosingShapeFill = true }
                            ColorDot(shapeStroke, stringResource(Res.string.composer_shape_outline)) { choosingShapeFill = false }
                            ToolButton(stringResource(Res.string.composer_outline), Icons.Outlined.Tune, active = shapeOptionsOpen) {
                                shapeOptionsOpen = !shapeOptionsOpen
                            }
                            Spacer(Modifier.width(8.dp))
                            ToolButton(stringResource(Res.string.composer_exit_drawing), Icons.Outlined.Check) { stopDrawing() }
                        }
                        if (shapeOptionsOpen) {
                            if (activeShape == ShapeKind.LINE || activeShape == ShapeKind.ARROW) {
                                BubbleThickness(shapeLineWidth, activeShape.minStroke(), maximum = MAX_SHAPE_STROKE) { shapeLineWidth = it }
                            } else {
                                BubbleThickness(shapeOutlineWidth, activeShape.minStroke(), stringResource(Res.string.composer_outline), maximum = MAX_SHAPE_STROKE) { shapeOutlineWidth = it }
                                TextButton(onClick = { shapeFill = shapeFill.withColorAlpha(0f) }) {
                                    Text(stringResource(Res.string.composer_no_fill))
                                }
                            }
                        }
                        Text(
                            stringResource(Res.string.composer_shape_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = uiColors.secondaryText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToolMenuButton(drawingKind.label(), Icons.Outlined.Brush) { dismiss ->
                                ToolMenuItem(stringResource(Res.string.composer_pen), Icons.Outlined.Brush, dismiss) { drawingKind = StrokeKind.PEN }
                                ToolMenuItem(stringResource(Res.string.composer_marker), Icons.Outlined.Brush, dismiss) { drawingKind = StrokeKind.MARKER }
                                ToolMenuItem(stringResource(Res.string.composer_eraser), Icons.Outlined.Remove, dismiss) { drawingKind = StrokeKind.ERASER }
                            }
                            if (drawingKind != StrokeKind.ERASER) {
                                ColorDot(drawingColor) { choosingDrawingColor = true }
                            }
                            Spacer(Modifier.width(8.dp))
                            ToolButton(stringResource(Res.string.composer_exit_drawing), Icons.Outlined.Check) { stopDrawing() }
                        }
                        if (drawingKind != StrokeKind.ERASER) BubbleThickness(drawingWidth, MIN_LINE_STROKE) { drawingWidth = it }
                    }
                }
            }
            if (eyedropperActive) {
                Surface(
                    color = uiColors.glass,
                    shape = RoundedCornerShape(ImagoRadii.Panel),
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = measuredChrome.bottom + 12.dp, start = 12.dp, end = 12.dp)
                        .border(1.dp, uiColors.subtleBorder, RoundedCornerShape(ImagoRadii.Panel)),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.composer_eyedropper_hint), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { onEyedropperResult(null) }) { Text(stringResource(Res.string.composer_cancel)) }
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
        LocalComposerMediaPicker.current(
            target,
            { mediaPicker = null },
            { chosen ->
                when (target) {
                    is MediaPickerTarget.Element -> actions.addMedia(chosen, target.replaceId)
                    is MediaPickerTarget.NewGrid -> actions.createGridFromMedia(chosen, target.pageIndex)
                    is MediaPickerTarget.Grid -> actions.addMediaToGrid(chosen, target.groupId, target.draft)
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
    // The brand kit covers the stage, so its pickers cannot sample it.
    CompositionLocalProvider(LocalEyedropper provides null) {
        if (brandKitDialog) BrandKitDialog(state, actions, onEditBrand = { brandEditor = true }) { brandKitDialog = false }
    }
    if (brandEditor) BrandKitOverlay(onClose = { brandEditor = false })
    if (templateDialog) TemplateDialog(
        onDismiss = { templateDialog = false },
        onSave = { name, include -> actions.saveAsTemplate(name, include); templateDialog = false },
    )
    if (exportDialog && project != null) ExportDialog(
        project = project,
        currentPage = state.currentPage,
        onDismiss = { exportDialog = false },
        onExport = { format, currentOnly, sendToImmich, quality ->
            exportDialog = false
            actions.export(format, currentOnly, sendToImmich, quality)
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
    ComposerGroupMergeDialog(state, actions)
    if (mediaPicker == null) ComposerGridDialog(state, actions, actions::thumbnailUrl, actions::apiKey,
        onAddMedia = { group -> mediaPicker = MediaPickerTarget.Grid(group, draft = true) })
    val selectedText = project?.elements?.firstOrNull { it.id == state.selectedElementId } as? CompositionElement.Text

}

/** The hairline that separates a column from the stage. */
@Composable
private fun ComposerPaneDivider() {
    val uiColors = ImagoUi.colors
    Box(Modifier.fillMaxHeight().width(1.dp).background(uiColors.subtleBorder))
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
    outlineActive: Boolean,
    onOutline: () -> Unit,
    onRequestGroup: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    val uiColors = ImagoUi.colors
    val selected = state.displayProject()?.elements?.firstOrNull { it.id == state.selectedElementId }
    when {
        state.backgroundSelected -> BackgroundInspector(
            state,
            actions,
            rail = rail,
            onPickImage = onPickImage,
            positioning = positioningBackground,
            onTogglePositioning = onTogglePositioning,
        )

        state.editedLayout() != null && selected == null -> GridInspector(state, actions, rail)

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
            outlineActive = outlineActive,
            onOutline = onOutline,
            onRequestGroup = onRequestGroup,
            onDeleted = onDeleted,
        )

        // With nothing chosen there are no properties to show. In a bottom bar that goes unnoticed —
        // it simply shrinks — but a blank 320dp column in the middle of the screen looks broken, and
        // so here it says what is left to do.
        rail -> Text(
            text = stringResource(Res.string.composer_inspector_hint),
            style = MaterialTheme.typography.bodySmall,
            color = uiColors.tertiaryText,
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
    val uiColors = ImagoUi.colors
    val kit = state.brandKit ?: BrandKit(updatedAt = "")
    val selected = state.displayProject()?.elements?.firstOrNull { it.id == state.selectedElementId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.composer_brand)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (selected == null) stringResource(Res.string.composer_brand_tap_background) else stringResource(Res.string.composer_brand_tap_selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = uiColors.tertiaryText,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val applyBrandColor = stringResource(Res.string.composer_apply_brand_color)
                    kit.paletteArgb.forEach { argb ->
                        Box(
                            Modifier.size(42.dp).clip(CircleShape).background(Color(argb)).border(1.dp, uiColors.border, CircleShape)
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

/**
 * Exporting: which pages, and how each kind comes out.
 *
 * A page with video always comes out as MP4, so for it the choice is the quality, with the size it
 * will have; JPEG and PNG only appear when the export has pages without video, and say that they are
 * for those. Before, the dialog offered only JPEG and PNG whatever was on the pages.
 */
@Composable
internal fun ExportDialog(
    project: CompositionProject,
    currentPage: Int,
    onDismiss: () -> Unit,
    onExport: (StaticExportFormat, Boolean, String?, VideoExportQuality) -> Unit,
) {
    var sendToImmich by remember { mutableStateOf(false) }
    var targetLibraryId by remember { mutableStateOf<String?>(null) }
    var currentOnly by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(VideoExportQuality.Default) }
    var format by remember { mutableStateOf(StaticExportFormat.JPEG) }
    val pages = if (currentOnly) listOf(currentPage) else project.pages.indices.toList()
    val withVideo = pages.count(project::pageHasVideo)
    val withoutVideo = pages.size - withVideo
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.composer_export)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (project.pages.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !currentOnly,
                            onClick = { currentOnly = false },
                            label = { Text(stringResource(Res.string.composer_export_scope_all)) },
                        )
                        FilterChip(
                            selected = currentOnly,
                            onClick = { currentOnly = true },
                            label = { Text(stringResource(Res.string.composer_export_scope_current)) },
                        )
                    }
                }
                if (withVideo > 0) {
                    ExportSectionTitle(stringResource(Res.string.composer_export_video_quality))
                    VideoExportQuality.entries.forEach { option ->
                        val (width, height) = videoExportSize(project.format, option)
                        // On a page no larger than 1080p, the top two have the same size and differ in
                        // how much the encoder compresses — which is what the line then says.
                        val sameSizeAsBelow = option.ordinal > 0 &&
                            videoExportSize(project.format, VideoExportQuality.entries[option.ordinal - 1]) == width to height
                        ExportOption(
                            label = stringResource(option.label()),
                            detail = stringResource(
                                if (sameSizeAsBelow) Res.string.composer_video_quality_detail_finer else Res.string.composer_video_quality_detail,
                                width,
                                height,
                            ),
                            selected = quality == option,
                        ) { quality = option }
                    }
                }
                if (withoutVideo > 0) {
                    if (withVideo > 0) ExportSectionTitle(stringResource(Res.string.composer_export_image_format))
                    ExportOption(
                        label = stringResource(Res.string.composer_image_format_jpeg),
                        detail = stringResource(Res.string.composer_image_format_jpeg_detail),
                        selected = format == StaticExportFormat.JPEG,
                    ) { format = StaticExportFormat.JPEG }
                    ExportOption(
                        label = stringResource(Res.string.composer_image_format_png),
                        detail = stringResource(Res.string.composer_image_format_png_detail),
                        selected = format == StaticExportFormat.PNG,
                    ) { format = StaticExportFormat.PNG }
                }
                TextButton(onClick = { sendToImmich = !sendToImmich }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sendToImmich) stringResource(Res.string.composer_send_to_immich_on) else stringResource(Res.string.composer_send_to_immich))
                }
                if (sendToImmich) eu.studio742.imago.feature.library.ExportLibrarySelector(onSelected = { targetLibraryId = it })
            }
        },
        confirmButton = {
            Button(enabled = !sendToImmich || targetLibraryId != null, onClick = {
                onExport(format, currentOnly, targetLibraryId.takeIf { sendToImmich }, quality)
            }) { Text(stringResource(Res.string.composer_export)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_cancel)) } },
    )
}

@Composable
private fun ExportSectionTitle(text: String) {
    val uiColors = ImagoUi.colors
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = uiColors.secondaryText,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ExportOption(label: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    val uiColors = ImagoUi.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = uiColors.text)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = uiColors.tertiaryText)
        }
    }
}

private fun VideoExportQuality.label() = when (this) {
    VideoExportQuality.STANDARD -> Res.string.composer_video_quality_standard
    VideoExportQuality.HIGH -> Res.string.composer_video_quality_high
    VideoExportQuality.MAXIMUM -> Res.string.composer_video_quality_maximum
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
    val uiColors = ImagoUi.colors
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
            compositionColors = state.project?.usedColors().orEmpty(),
            onDismiss = { pendingColor = null },
            onConfirm = pending.onPick,
        )
    }
    val chrome = inspectorChromeAlpha(rail)
    Column(Modifier.padding(horizontal = ImagoSpacing.Sm), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.alpha(chrome), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Wallpaper, null, Modifier.size(ImagoSizes.IconSmall), tint = uiColors.accent)
            Text(
                "  " + if (currentPageOnly) {
                    stringResource(Res.string.composer_background_page, state.currentPage + 1)
                } else {
                    stringResource(Res.string.composer_background_all)
                },
                style = MaterialTheme.typography.labelMedium,
                color = uiColors.secondaryText,
                modifier = Modifier.padding(end = ImagoSpacing.Sm),
            )
            TextButton(onClick = { currentPageOnly = !currentPageOnly }) {
                Text(if (currentPageOnly) stringResource(Res.string.composer_apply_to_all) else stringResource(Res.string.composer_this_page_only), fontSize = 11.sp)
            }
            IconButton(onClick = actions::clearSelection) { Icon(Icons.Outlined.Check, stringResource(Res.string.composer_close_background)) }
        }
        ComposerToolRow(
            Modifier.alpha(chrome),
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
                color = uiColors.accent,
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
                    color = uiColors.tertiaryText,
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

/** The stroke's thickness while it is being made, before it becomes an element. */
private const val LIVE_STROKE_WIDTH = 5f

/**
 * How far an element moves per arrow press, as a fraction of the page.
 *
 * With Shift it moves ten times further. Fine nudges preserve their exact distance at page edges.
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

@Composable
internal fun ComposerCanvas(
    project: CompositionProject,
    state: ComposerEditorUiState,
    drawing: Boolean,
    drawingKind: StrokeKind,
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
    drawingColor: Long = 0xFFFFFFFF,
    drawingWidth: Float = 6f,
    /** A shape was chosen and waits for the finger: a tap or a drag on the stage places it. */
    shapeKind: ShapeKind? = null,
    shapeFill: Long = 0xFFFFFFFF,
    shapeStroke: Long = 0xFFFFFFFF,
    shapeStrokeWidth: Float = 0f,
    onShape: (ShapeKind, NormalizedRect, Float) -> Unit = { _, _, _ -> },
    /** A colour picker asked for a colour from the page: a touch samples it instead of acting. */
    eyedropper: Boolean = false,
    onSample: (Long?) -> Unit = {},
) {
    val uiColors = ImagoUi.colors
    val gridFeedback = LocalHapticFeedback.current
    var gridSwapDrag by remember(state.editingGroupId) { mutableStateOf<GridSwapDrag?>(null) }
    // Drawing and placing a shape both own the stage's finger: scrolling, selecting and the elements'
    // own gestures stand aside for either.
    val armed = drawing || shapeKind != null || eyedropper
    // While a grid is edited its cells answer to the stage, not to the elements in them. Framing a
    // photo or typing in a cell hands that one element its own gestures back.
    val gridCells = state.editedLayout()?.takeIf { state.textSession == null && !armed && !positioningBackground && !positioningMedia && !multiSelect }
    val gridCellIds = remember(gridCells?.root) { gridCells?.root?.memberIds()?.toSet().orEmpty() }
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
        val layout = target?.groupId?.let { group -> project.layouts.firstOrNull { it.groupId == group } }
            ?.takeIf { state.editingGroupId == null }
        val key = layout?.let { "grid:${it.groupId}" } ?: id
        val now = System.currentTimeMillis()
        when {
            target == null || target.locked || multiSelect || (layout == null && target.placementCrop() == null && target !is CompositionElement.Text) -> lastTapId = null
            key == lastTapId && now - lastTapAt <= DoubleTapWindowMs -> {
                lastTapId = null
                if (layout != null) actions.openGrid(layout.groupId)
                else if (target is CompositionElement.Text) actions.beginTextEditing(id) else onFrameMedia()
            }
            else -> {
                lastTapId = key
                lastTapAt = now
            }
        }
    }
    val viewport = remember(project.id) { ComposerViewport() }
    var textViewport by remember(project.id) { mutableStateOf<ComposerViewport.Snapshot?>(null) }
    LaunchedEffect(state.textSession?.draft?.id) {
        if (state.textSession != null && textViewport == null) textViewport = viewport.snapshot()
        else if (state.textSession == null) { textViewport?.let(viewport::restore); textViewport = null }
    }
    val selectedElementId = state.selectedElementId
    var contextMenuFor by remember(project.id) { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.background(uiColors.background).clipToBounds().testTag("composer_stage")) {
        val density = LocalDensity.current
        val viewportSize = with(density) { androidx.compose.ui.geometry.Size(maxWidth.toPx(), maxHeight.toPx()) }
        val topPx = with(density) { stageInsets.top.toPx() }
        val bottomPx = with(density) { stageInsets.bottom.toPx() }
        val marginPx = with(density) { 18.dp.toPx() }
        val modelSize = androidx.compose.ui.geometry.Size(project.format.pixelWidth.toFloat(), project.format.pixelHeight.toFloat())
        val fit = minOf((viewportSize.width - marginPx * 2).coerceAtLeast(1f) / modelSize.width,
            (viewportSize.height - topPx - bottomPx - marginPx * 2).coerceAtLeast(1f) / modelSize.height)
        LaunchedEffect(viewportSize, project.format) {
            viewport.resize(viewportSize, fit, state.currentPage, modelSize, topPx, bottomPx)
        }
        val scale = viewport.scale.takeIf { it > 0f } ?: fit
        val pageWidthPx = modelSize.width * scale
        val pageHeightPx = modelSize.height * scale
        val pageWidth = with(density) { pageWidthPx.toDp() }
        val pageHeight = with(density) { pageHeightPx.toDp() }
        val stageOrigin = if (viewport.scale > 0f) viewport.origin else Offset(
            (viewportSize.width - pageWidthPx) / 2f - state.currentPage * pageWidthPx,
            topPx + (viewportSize.height - topPx - bottomPx - pageHeightPx) / 2f,
        )
        val liveProject by rememberUpdatedState(project)
        val liveStageOrigin by rememberUpdatedState(stageOrigin)
        val livePageSize by rememberUpdatedState(Offset(pageWidthPx, pageHeightPx))
        val liveMultiSelect by rememberUpdatedState(multiSelect)
        val liveMediaTap by rememberUpdatedState(onMediaTap)
        val tapGridCell: (String) -> Unit = { id ->
            val repeated = id == lastTapId && System.currentTimeMillis() - lastTapAt <= DoubleTapWindowMs
            when {
                project.elements.firstOrNull { it.id == id } is CompositionElement.MediaPlaceholder -> onPickMediaFor(id)
                state.selectedElementId == id && !repeated -> actions.clearGridMemberSelection()
                else -> actions.focusGroupMember(id)
            }
            onMediaTap(id)
        }
        val liveGridCells by rememberUpdatedState(gridCells)
        val liveGridTap by rememberUpdatedState(tapGridCell)
        val liveFit by rememberUpdatedState(fit)
        val touchSlopPx = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
        val strokes = remember(drawing, project.id) { mutableStateListOf<StrokePoint>() }
        // An immutable copy per composition. Reading the live list inside the `Canvas` was a race: the
        // `isNotEmpty()` guard ran in the composition and `first()` in the draw phase, already after
        // `onDragEnd` had cleared the list — hence the `NoSuchElementException`.
        val liveStroke = strokes.toList()
        val currentOnDrawing by rememberUpdatedState(onDrawing)
        val currentOnShape by rememberUpdatedState(onShape)
        val currentOnSample by rememberUpdatedState(onSample)
        // What the stage shows, kept as a picture only while the eyedropper is on — recording every
        // frame for nothing would cost a layer's worth of memory.
        val captureLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
        val sampleScope = rememberCoroutineScope()
        var eyedropperAt by remember { mutableStateOf<Offset?>(null) }
        // The stage as a picture, taken when the finger lands: the page stands still while a colour is
        // being chosen, and the loupe and the final sample both read from it.
        var eyedropperPicture by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
        // The finger's first and current position while a shape is being dragged out, in stage pixels.
        var shapeDrag by remember(project.id) { mutableStateOf<Pair<Offset, Offset>?>(null) }
        val positionedBackground by rememberUpdatedState(
            (project.pages.getOrNull(state.currentPage)?.backgroundOverride ?: project.background)
                as? CompositionBackground.Photo,
        )
        var viewportPage by remember(project.id) { mutableIntStateOf(state.currentPage) }
        LaunchedEffect(project.id, viewportSize, project.format, project.pages.size) {
            snapshotFlow { ((viewportSize.width / 2f - viewport.origin.x) / (modelSize.width * viewport.scale)).toInt()
                .coerceIn(project.pages.indices) }
                .distinctUntilChanged().collect { page ->
                    viewportPage = page
                    actions.selectPage(page)
                }
        }
        LaunchedEffect(state.currentPage) {
            if (state.currentPage != viewportPage && !viewport.navigating && !state.stageGesture && viewport.scale > 0f) {
                viewport.reveal(NormalizedRect(state.currentPage.toFloat(), 0f, 1f, 1f), modelSize,
                    viewportSize, topPx, bottomPx, marginPx)
            }
        }
        LaunchedEffect(state.revealSelectionRequest) {
            if (state.revealSelectionRequest == 0) return@LaunchedEffect
            val selected = project.elements.firstOrNull { it.id == selectedElementId }
            if (selected != null && !state.stageGesture && viewport.scale > 0f) {
                val members = state.selectionElements()
                viewport.reveal(members.map { it.visualBounds(aspect = modelSize.width / modelSize.height) }.boundingBox()!!,
                    modelSize, viewportSize, topPx, bottomPx, marginPx)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(project.id, positioningMedia) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (positioningMedia) return@awaitEachGesture
                        var previousDistance = 0f
                        var previousCentre = Offset.Zero
                        var claimed = false
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    viewport.navigating = true
                                    claimed = true
                                    val centre = (pressed[0].position + pressed[1].position) / 2f
                                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                                    if (previousDistance > 0f) viewport.transform(distance / previousDistance,
                                        previousCentre, centre - previousCentre, liveFit)
                                    previousCentre = centre
                                    previousDistance = distance
                                }
                                if (claimed) event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        } finally { viewport.navigating = false }
                    }
                }
                .pointerInput(project.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll && event.changes.none { it.isConsumed }) {
                                val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
                                viewport.pan(delta * -48.dp.toPx())
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .then(
                    if (eyedropper) {
                        Modifier.drawWithContent {
                            captureLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(captureLayer)
                            // After the layer, so the ring is never part of what is sampled.
                            val at = eyedropperAt
                            val picture = eyedropperPicture
                            if (at != null && picture != null) {
                                // A loupe above the finger (below it near the top): the pixels under the
                                // finger, magnified, in a ring that is the colour that would be picked.
                                val diameter = 120.dp.toPx()
                                val radius = diameter / 2f
                                val grid = 11
                                val sx = at.x.roundToInt().coerceIn(0, picture.width - 1)
                                val sy = at.y.roundToInt().coerceIn(0, picture.height - 1)
                                val gap = 56.dp.toPx()
                                val above = at.y - radius - gap >= 0f
                                val centre = Offset(
                                    at.x.coerceIn(radius, (size.width - radius).coerceAtLeast(radius)),
                                    if (above) at.y - radius - gap else at.y + radius + gap,
                                )
                                val picked = picture.toPixelMap(sx, sy, 1, 1)[0, 0]
                                val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(centre, radius)) }
                                clipPath(disc) {
                                    drawRect(uiColors.workspace, centre - Offset(radius, radius), androidx.compose.ui.geometry.Size(diameter, diameter))
                                    drawImage(
                                        picture,
                                        srcOffset = androidx.compose.ui.unit.IntOffset(
                                            (sx - grid / 2).coerceIn(0, (picture.width - grid).coerceAtLeast(0)),
                                            (sy - grid / 2).coerceIn(0, (picture.height - grid).coerceAtLeast(0)),
                                        ),
                                        srcSize = androidx.compose.ui.unit.IntSize(grid.coerceAtMost(picture.width), grid.coerceAtMost(picture.height)),
                                        dstOffset = androidx.compose.ui.unit.IntOffset((centre.x - radius).roundToInt(), (centre.y - radius).roundToInt()),
                                        dstSize = androidx.compose.ui.unit.IntSize(diameter.roundToInt(), diameter.roundToInt()),
                                        filterQuality = FilterQuality.None,
                                    )
                                }
                                // The pixel under the finger, outlined at the loupe's centre.
                                val cell = diameter / grid
                                drawRect(uiColors.workspace, centre - Offset(cell / 2f, cell / 2f), androidx.compose.ui.geometry.Size(cell, cell), style = Stroke(3f))
                                drawRect(uiColors.text, centre - Offset(cell / 2f, cell / 2f), androidx.compose.ui.geometry.Size(cell, cell), style = Stroke(1.5f))
                                drawCircle(Color(picked.red, picked.green, picked.blue), radius, centre, style = Stroke(10.dp.toPx()))
                                drawCircle(uiColors.text, radius + 5.dp.toPx(), centre, style = Stroke(1.5f))
                                // And the touch point itself, small, where the finger is.
                                drawCircle(uiColors.workspace, 12f, at, style = Stroke(4f))
                                drawCircle(uiColors.text, 12f, at, style = Stroke(2f))
                            } else if (at != null) {
                                drawCircle(uiColors.workspace, 22f, at, style = Stroke(5f))
                                drawCircle(uiColors.text, 22f, at, style = Stroke(2.5f))
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .pointerInput(eyedropper) {
                    if (!eyedropper) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        var last = down.position
                        var lifted = false
                        eyedropperAt = last
                        val capture = sampleScope.async { captureLayer.toImageBitmap() }
                        sampleScope.launch { eyedropperPicture = runCatching { capture.await() }.getOrNull() }
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.count { it.pressed } > 1) break
                                event.changes.forEach { it.consume() }
                                last = change.position
                                eyedropperAt = last
                                lifted = !change.pressed
                            } while (!lifted)
                            if (lifted) {
                                val at = last
                                sampleScope.launch {
                                    val picture = capture.await()
                                    val x = at.x.roundToInt().coerceIn(0, picture.width - 1)
                                    val y = at.y.roundToInt().coerceIn(0, picture.height - 1)
                                    val pixel = picture.toPixelMap(x, y, 1, 1)[0, 0]
                                    if (pixel.alpha > 0f) {
                                        currentOnSample(0xFF000000L or (pixel.toArgb().toLong() and 0xFFFFFFL))
                                    }
                                }
                            }
                        } finally {
                            eyedropperAt = null
                            eyedropperPicture = null
                        }
                    }
                }
                .pointerInput(drawing, eyedropper, project.pages.size, pageWidthPx, pageHeightPx) {
                    if (drawing && !eyedropper) awaitEachGesture {
                        // Claim the stage before the photo or selection gestures see the same down.
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        actions.beginDrawingStroke()
                        actions.beginGesture()
                        strokes.clear()
                        fun sample(change: androidx.compose.ui.input.pointer.PointerInputChange) = StrokePoint(
                            (change.position.x - viewport.origin.x) / pageWidthPx,
                            (change.position.y - viewport.origin.y) / pageHeightPx,
                            if (change.type == PointerType.Stylus) change.pressure else 1f,
                        )
                        strokes += sample(down)
                        var completed = false
                        var cancelled = false
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // A second finger is a pinch, not part of the stroke.
                                if (event.changes.count { it.pressed } > 1) { cancelled = true; break }
                                event.changes.forEach { it.consume() }
                                if (change.pressed) strokes += sample(change)
                            } while (change.pressed)
                            if (!cancelled) {
                                if (strokes.size == 1) strokes += strokes.first()
                                currentOnDrawing(strokes.toList())
                                completed = true
                            }
                        } finally {
                            if (!completed) actions.finishDrawingSession()
                            strokes.clear()
                            actions.endGesture()
                        }
                    }
                }
                .pointerInput(shapeKind, eyedropper, project.pages.size, pageWidthPx, pageHeightPx) {
                    // Outside `awaitEachGesture`, for the reason given on the background tap below.
                    val kind = shapeKind ?: return@pointerInput
                    if (eyedropper) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        val first = down.position - viewport.origin
                        var last = first
                        var lifted = false
                        shapeDrag = first to last
                        try {
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (event.changes.count { it.pressed } > 1) break
                                event.changes.forEach { it.consume() }
                                last = change.position - viewport.origin
                                shapeDrag = first to last
                                lifted = !change.pressed
                            } while (!lifted)
                            if (lifted) {
                                val placement = shapePlacement(kind, first, last, pageWidthPx, pageHeightPx, project.pages.size, viewConfiguration.touchSlop)
                                currentOnShape(kind, placement.bounds, placement.rotationDegrees)
                            }
                        } finally {
                            shapeDrag = null
                        }
                    }
                }
                // A second `pointerInput`, and not an `if` inside the one above: the two keys
                // (`drawing`, `positioningBackground`) can no longer both be true at the same time —
                // whoever turns one on turns the other off — but keeping them separate avoids turning
                // one on restarting the other's gesture halfway.
                .composerHandleGestures(state, pageWidthPx, pageHeightPx, state.textSession == null && gridCells == null && !armed && !positioningBackground && !positioningMedia, actions, stageOrigin)
                .composerGridDividerGestures(gridCells ?: state.manipulatedLayout()?.takeIf { state.textSession == null && !armed && !positioningBackground && !positioningMedia && !state.multiSelect },
                    project.format.aspectRatio, stageOrigin, pageWidthPx, pageHeightPx,
                    { actions.beginGesture() }, { actions.endGesture() },
                    { split, index, delta ->
                        (gridCells ?: state.manipulatedLayout())?.let { actions.adjustGridDivider(it.groupId, split, index, delta) }
                    })
                .then(if (gridCells == null) Modifier else Modifier.composerGridSwapGestures(project, gridCells,
                    stageOrigin, pageWidthPx, pageHeightPx, { gridSwapDrag = it }, actions::swapGridMembers,
                    { gridFeedback.performHapticFeedback(HapticFeedbackType.LongPress) }, actions::removeGridMember, stageInsets.bottom))
                .pointerInput(positioningBackground, eyedropper, project.pages.size) {
                    if (positioningBackground && !eyedropper) detectDragGestures(
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
                .pointerInput(armed, positioningBackground) {
                    // The mode check sits outside `awaitEachGesture`. Inside, the block returned without
                    // suspending, and once the finger was up `awaitEachGesture` looped straight back
                    // into it with nothing to wait for: a busy loop on the main thread, and the app
                    // froze the moment the pen or the background framing touched the stage.
                    if (armed || positioningBackground) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        var moved = false
                        var travelled = Offset.Zero
                        try {
                            do {
                                val event = awaitPointerEvent()
                                // This node hears the finger before the cell gesture does, and must not pan under it.
                                if (event.changes.any { it.isConsumed } || gridSwapDrag != null) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                travelled += change.positionChange()
                                if (!moved && travelled.getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                    viewport.navigating = true
                                    viewport.pan(travelled)
                                } else if (moved) viewport.pan(change.positionChange())
                                if (moved) event.changes.forEach { it.consume() }
                                if (!change.pressed) {
                                    if (!moved) {
                                        val aspect = liveProject.format.aspectRatio
                                        fun CompositionLayout.at(): String? = cellAt(change.position, aspect, liveStageOrigin, livePageSize.x, livePageSize.y)
                                        val layout = liveProject.layouts.lastOrNull { it.copy(margin = 0f, gap = 0f).at() != null }
                                        val grid = liveGridCells
                                        if (grid != null && grid.groupId == layout?.groupId) {
                                            // A tap on a gutter lets go of the photo without leaving the grid.
                                            grid.at()?.let { liveGridTap(it) } ?: actions.clearGridMemberSelection()
                                        } else if (layout != null && !liveMultiSelect) {
                                            actions.selectGroup(layout.groupId)
                                            liveMediaTap(layout.root.memberIds().first())
                                        } else actions.clearSelection()
                                    }
                                    break
                                }
                            } while (true)
                        } finally { viewport.navigating = false }
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
                Box(Modifier.offset { IntOffset((stageOrigin.x + pageWidthPx * page.index).roundToInt(), stageOrigin.y.roundToInt()) }.wrapContentSize(Alignment.TopStart, unbounded = true).size(pageWidth, pageHeight).testTag("composer_page_${page.index}")) {
                    Background(page.backgroundOverride ?: project.background, previewUrl, thumbnailUrl, apiKey, Modifier.fillMaxSize())
                }
                Box(
                    Modifier.offset { IntOffset((stageOrigin.x + pageWidthPx * page.index).roundToInt(), stageOrigin.y.roundToInt()) }.wrapContentSize(Alignment.TopStart, unbounded = true).size(pageWidth, pageHeight)
                        .border(
                            1.dp,
                            if (state.backgroundSelected && page.index == state.currentPage) {
                                uiColors.accent
                            } else {
                                uiColors.text.copy(alpha = .32f)
                            },
                        ),
                ) {
                    if (project.safeZonesVisible) Box(Modifier.fillMaxSize().padding(pageWidth * .06f).border(1.dp, uiColors.text.copy(alpha = .2f)))
                    Text("${page.index + 1}", color = uiColors.text.copy(alpha = .5f), fontSize = 10.sp, modifier = Modifier.padding(4.dp))
                }
            }
            project.renderElements().filter(CompositionElement::visible).sortedBy(CompositionElement::zIndex).forEach { element ->
                val pageTargets = element.repeatOnPages.takeIf { it.isNotEmpty() } ?: setOf(-1)
                pageTargets.forEach { page ->
                    val placement = if (page < 0) element.transform.bounds else element.boundsOnPage(page)
                    val coverage = element.paintedBounds(placement, modelSize.width / modelSize.height)
                    if (stageOrigin.x + coverage.right * pageWidthPx < -marginPx ||
                        stageOrigin.x + coverage.x * pageWidthPx > viewportSize.width + marginPx ||
                        stageOrigin.y + coverage.bottom * pageHeightPx < -marginPx ||
                        stageOrigin.y + coverage.y * pageHeightPx > viewportSize.height + marginPx) return@forEach
                    val translatedX = placement.x
                    val exportPage = if (element is CompositionElement.Video) element.pageIndex else page.takeIf { it >= 0 }
                    val elementOrigin = Offset((stageOrigin.x + translatedX * pageWidthPx).roundToInt().toFloat(),
                        (stageOrigin.y + placement.y * pageHeightPx).roundToInt().toFloat())
                    val firstPage = exportPage ?: 0
                    val lastPage = exportPage ?: project.pages.lastIndex
                    val pageTop = stageOrigin.y.roundToInt() - elementOrigin.y
                    val pageClip = Rect(
                        (stageOrigin.x + firstPage * pageWidthPx).roundToInt() - elementOrigin.x, pageTop,
                        (stageOrigin.x + lastPage * pageWidthPx).roundToInt() + pageWidthPx.roundToInt() - elementOrigin.x,
                        pageTop + pageHeightPx.roundToInt(),
                    )
                    val viewportClip = Rect(-elementOrigin.x, -elementOrigin.y,
                        viewportSize.width - elementOrigin.x, viewportSize.height - elementOrigin.y)
                    CompositionElementView(
                        element, translatedX, pageWidth, pageHeight, pageWidthPx, pageHeightPx, stageOrigin,
                        pageClip = pageClip, viewportClip = viewportClip,
                        selected = element.id in state.selectedElementIds,
                        multiSelect = multiSelect,
                        // Framing is always of a single element: the chosen one. An element repeated on
                        // several pages shows several times, and only the copy in view receives the
                        // finger — the others follow it anyway, because it is the same `crop` that changes.
                        positioning = positioningMedia && element.id == state.selectedElementId,
                        onLongPress = { actions.beginMultiSelection(element.id) },
                        // A media slot with a "+" drawn on it promised that tapping it was good for
                        // something, and it was not: it selected it and everything stayed the same.
                        // Whoever taps the "+" wants the library, and that is what opens — except in
                        // multiple selection, where the tap is for adding to the set, and in the drawing
                        // and background modes, where the stage belongs to another gesture.
                        onPickMedia = if (
                            element is CompositionElement.MediaPlaceholder &&
                            (element.groupId == null || project.layouts.none { it.groupId == element.groupId } || state.editingGroupId == element.groupId) &&
                            !element.locked &&
                            !multiSelect &&
                            !armed &&
                            !positioningBackground
                        ) {
                            { onPickMediaFor(element.id) }
                        } else {
                            null
                        },
                        onTap = { onMediaTap(element.id) },
                        previewUrl = previewUrl, thumbnailUrl = thumbnailUrl, videoPlaybackUrl = videoPlaybackUrl, apiKey = apiKey,
                        actions = actions,
                        interactive = element.id !in gridCellIds && state.textSession?.draft?.id != element.id,
                    )
                }
            }
            state.textSession?.takeIf { it.contentEditing }?.let { session ->
                ComposerInlineTextEditor(session, pageWidthPx, pageHeightPx, stageOrigin, actions) { caret ->
                    viewport.reveal(caret, modelSize, viewportSize, topPx, bottomPx, marginPx)
                }
            }
            ComposerMovementGuides(state.movementGuides, stageOrigin, pageWidthPx, pageHeightPx)
            gridCells?.let { layout ->
                ComposerGridSwapOverlay(project, layout, stageOrigin, pageWidthPx, pageHeightPx, gridSwapDrag,
                    thumbnailUrl, apiKey, actions::swapGridMembers, actions::removeGridMember, stageInsets.bottom,
                    state.selectedElementId, actions::focusGroupMember)
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
                                .offset { IntOffset((stageOrigin.x + pageWidthPx * cb.x).roundToInt(), (stageOrigin.y + pageHeightPx * cb.y).roundToInt()) }
                                .size(pageWidth * cb.width, pageHeight * cb.height)
                                .border(1.dp, uiColors.accent.copy(alpha = .8f)),
                        )
                    }
            }
            // The frame comes after all the elements so the handles are always on top.
            val selected = project.elements.firstOrNull { it.id == selectedElementId && it.visible }
                ?: state.selectionElements().firstOrNull { it.visible }
            val block = state.selectionElements()
                .takeIf { it.size > 1 }
            if (selected != null && selected.visible && !armed && state.textSession?.contentEditing != true && gridCells == null) {
                // With several chosen the frame wraps the set, and not the last one tapped: it is the
                // set the handles resize, and a frame around a single one promised something else.
                // Without rotation, because the block has no angle of its own.
                val layoutFrame = state.manipulatedLayout()?.frame
                val frame = layoutFrame?.bounds ?: block?.let { members ->
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
                    origin = stageOrigin,
                    rotationDegrees = layoutFrame?.rotationDegrees ?: if (frame != null) 0f else selected.transform.rotationDegrees,
                    locked = !state.canEditElement(selected.id),
                    positioning = positioningMedia || state.textSession != null,
                    textWidthHandles = selected is CompositionElement.Text && block == null && layoutFrame == null,
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
            if (state.textSession == null && !armed && !positioningMedia && !state.multiSelect) {
                (gridCells ?: state.selectedLayout()?.takeIf { state.editingGroupId == null })
                    ?.takeIf { layout -> project.elements.none { it.groupId == layout.groupId && it.locked } }?.let { layout ->
                    ComposerGridDividers(layout, project.format.aspectRatio, stageOrigin, pageWidthPx, pageHeightPx,
                        onBegin = { actions.beginGesture() }, onEnd = { actions.endGesture() },
                        onAdjust = { split, index, delta -> actions.adjustGridDivider(layout.groupId, split, index, delta) })
                }
            }
            contextMenuFor?.let { id ->
                val target = project.elements.firstOrNull { it.id == id }
                if (target == null) {
                    contextMenuFor = null
                } else {
                    val tb = target.transform.bounds
                    Box(Modifier.offset { IntOffset((stageOrigin.x + pageWidthPx * tb.x).roundToInt(), (stageOrigin.y + pageHeightPx * tb.bottom).roundToInt()) }) {
                        ElementContextMenu(
                            element = target,
                            actions = actions,
                            onDismiss = { contextMenuFor = null },
                        )
                    }
                }
            }
            if (liveStroke.size > 1 && drawingKind == StrokeKind.ERASER) {
                // The eraser leaves no ink: a pale band the width of what it reaches shows where it
                // has been, and the strokes it touched go when the finger lifts.
                Canvas(Modifier.fillMaxSize().drawWithContent {
                    translate(stageOrigin.x, stageOrigin.y) { this@drawWithContent.drawContent() }
                }) {
                    val path = Path().apply {
                        moveTo(liveStroke.first().x * pageWidthPx, liveStroke.first().y * pageHeightPx)
                        liveStroke.drop(1).forEach { lineTo(it.x * pageWidthPx, it.y * pageHeightPx) }
                    }
                    drawPath(path, uiColors.text, alpha = .28f, style = Stroke(ERASER_REACH * 2f * pageWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            } else if (liveStroke.size > 1) {
                Canvas(Modifier.fillMaxSize().drawWithContent {
                    translate(stageOrigin.x, stageOrigin.y) { this@drawWithContent.drawContent() }
                }) {
                    // The stroke being born already shows the thickness it will keep: seeing the
                    // pressure only after letting go of the stylus took away half its usefulness.
                    if (liveStroke.hasVariablePressure()) {
                        liveStroke.zipWithNext { from, to ->
                            drawLine(
                                color = Color(drawingColor),
                                alpha = if (drawingKind == StrokeKind.MARKER) .45f else 1f,
                                start = Offset(from.x * pageWidthPx, from.y * pageHeightPx),
                                end = Offset(to.x * pageWidthPx, to.y * pageHeightPx),
                                strokeWidth = strokeWidthAt(drawingWidth * pageWidthPx / REFERENCE_PAGE_WIDTH, (from.pressure + to.pressure) / 2f),
                                cap = StrokeCap.Round,
                            )
                        }
                    } else {
                        val path = Path().apply {
                            moveTo(liveStroke.first().x * pageWidthPx, liveStroke.first().y * pageHeightPx)
                            liveStroke.drop(1).forEach { lineTo(it.x * pageWidthPx, it.y * pageHeightPx) }
                        }
                        drawPath(path, Color(drawingColor), alpha = if (drawingKind == StrokeKind.MARKER) .45f else 1f, style = Stroke(drawingWidth * pageWidthPx / REFERENCE_PAGE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
            val drag = shapeDrag
            val previewKind = shapeKind
            if (drag != null && previewKind != null && (drag.second - drag.first).getDistance() > touchSlopPx) {
                Canvas(Modifier.fillMaxSize().drawWithContent {
                    translate(stageOrigin.x, stageOrigin.y) { this@drawWithContent.drawContent() }
                }) {
                    val colour = Color(shapeFill)
                    val lineColour = Color(shapeStroke)
                    val widthPx = shapeStrokeWidth * pageWidthPx / REFERENCE_PAGE_WIDTH
                    val placed = shapePlacement(previewKind, drag.first, drag.second, pageWidthPx, pageHeightPx, project.pages.size, 0f)
                    val box = placed.bounds
                    val topLeft = Offset(box.x * pageWidthPx, box.y * pageHeightPx)
                    val boxSize = androidx.compose.ui.geometry.Size(box.width * pageWidthPx, box.height * pageHeightPx)
                    when (previewKind) {
                        ShapeKind.RECTANGLE -> {
                            drawRect(colour, topLeft, boxSize, alpha = .6f)
                            if (widthPx > 0f) drawRect(lineColour, topLeft - Offset(widthPx / 2f, widthPx / 2f), androidx.compose.ui.geometry.Size(boxSize.width + widthPx, boxSize.height + widthPx), alpha = .6f, style = Stroke(widthPx))
                        }
                        ShapeKind.ELLIPSE -> {
                            drawOval(colour, topLeft, boxSize, alpha = .6f)
                            if (widthPx > 0f) drawOval(lineColour, topLeft - Offset(widthPx / 2f, widthPx / 2f), androidx.compose.ui.geometry.Size(boxSize.width + widthPx, boxSize.height + widthPx), alpha = .6f, style = Stroke(widthPx))
                        }
                        ShapeKind.LINE, ShapeKind.ARROW -> {
                            drawLine(lineColour, drag.first, drag.second, widthPx, StrokeCap.Round)
                            if (previewKind == ShapeKind.ARROW) {
                                // The head is a fraction of the box's shorter side, like the placed arrow.
                                val head = kotlin.math.min(boxSize.width, boxSize.height) * .22f
                                val angle = Math.toRadians(placed.rotationDegrees.toDouble())
                                for (side in listOf(-1f, 1f)) {
                                    val back = Offset(
                                        drag.second.x - head * kotlin.math.cos(angle).toFloat() - side * head * kotlin.math.sin(angle).toFloat(),
                                        drag.second.y - head * kotlin.math.sin(angle).toFloat() + side * head * kotlin.math.cos(angle).toFloat(),
                                    )
                                    drawLine(lineColour, drag.second, back, widthPx, StrokeCap.Round)
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(
            color = uiColors.workspace.copy(alpha = .72f),
            shape = RoundedCornerShape(18.dp),
            // Keep the zoom controls clear of the mode bubble and the multiple-selection instruction.
            modifier = (if (armed || multiSelect || state.editingGroupId != null) Modifier.align(Alignment.BottomEnd) else Modifier.align(Alignment.TopEnd))
                .padding(
                    top = if (armed) 8.dp else stageInsets.top + 8.dp,
                    start = 8.dp,
                    end = 8.dp,
                    bottom = if (armed || multiSelect || state.editingGroupId != null) stageInsets.bottom + 8.dp else 8.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewport.transform(1f / 1.25f, Offset(viewportSize.width / 2, viewportSize.height / 2), Offset.Zero, fit) }) { Text("−") }
                // 100% is the whole page; tapping the number comes back to it.
                Text(
                    "${(scale / fit * 100).roundToInt()}%",
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { viewport.fit(viewportSize, fit, state.currentPage, modelSize, topPx, bottomPx) }.testTag("composer_fit_page"),
                )
                TextButton(onClick = { viewport.transform(1.25f, Offset(viewportSize.width / 2, viewportSize.height / 2), Offset.Zero, fit) }) { Text("+") }
            }
        }
    }
}

@Composable
internal fun Background(
    background: CompositionBackground,
    previewUrl: (String) -> String,
    thumbnailUrl: (String) -> String,
    apiKey: (String) -> String,
    modifier: Modifier,
) {
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
        // It goes on the box, so the draft and the large version underneath blur as one image.
        is CompositionBackground.Photo -> Box(
            if (background.blurRadius > 0f) modifier.blur(background.blurRadius.dp) else modifier,
        ) {
            // The recipe the photo had when it became the background, with the same requests as a
            // photo element: the background has to show the look the file will have, too.
            val requests = rememberComposerPhotoRequests(
                background.media.assetId, background.recipe?.recipe, previewUrl, thumbnailUrl, apiKey,
            )
            // The same framing pair as the photo/video elements — without it, the "Scale" slider
            // saved the right value (and the export respected it), but the stage always drew the image
            // at 1:1 and the slider seemed to do nothing.
            requests.draft?.let { draft ->
                AsyncImage(
                    model = draft,
                    contentDescription = null,
                    alignment = placementAlignment(background.crop),
                    modifier = Modifier.fillMaxSize().placementZoom(background.crop),
                    contentScale = ContentScale.Crop,
                )
            }
            AsyncImage(
                model = requests.full,
                contentDescription = null,
                alignment = placementAlignment(background.crop),
                modifier = Modifier.fillMaxSize().placementZoom(background.crop),
                contentScale = ContentScale.Crop,
            )
        }
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
    stageOrigin: Offset,
    pageClip: Rect,
    viewportClip: Rect,
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
    interactive: Boolean = true,
) {
    val uiColors = ImagoUi.colors
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
    val haptic = LocalHapticFeedback.current
    val onBeginMultiSelection = {
        if (actions.canEdit(element.id)) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongPress()
        }
    }
    val liveAspect by rememberUpdatedState(measuredAspect ?: element.contentAspect())
    val liveBoxWidth by rememberUpdatedState(pageWidthPx * b.width)
    val liveBoxHeight by rememberUpdatedState(pageHeightPx * b.height)
    val snapDistancePx = with(LocalDensity.current) { ComposerSnapDistance.toPx() }
    // In multiple selection mode a tap adds and removes instead of replacing: that is what allows
    // picking up three things to align them without having to group them first.
    val onSelect = {
        if (!selected) {
            if (multiSelect) actions.toggleElementInSelection(element.id) else actions.selectElement(element.id)
        }
    }
    val onSelectTap = {
        if (multiSelect) actions.toggleElementInSelection(element.id) else actions.selectElement(element.id)
    }
    // The semantics block is not a composition: the texts are read here, before it.
    val elementLabel = element.accessibilityLabel()
    val pickMediaLabel = stringResource(Res.string.composer_choose_media)
    val selectLabel = stringResource(Res.string.composer_select)
    val common = Modifier
        .offset { IntOffset((stageOrigin.x + translatedX * pageWidthPx).roundToInt(), (stageOrigin.y + b.y * pageHeightPx).roundToInt()) }
        .testTag("composer_element_${element.id}")
        .wrapContentSize(Alignment.TopStart, unbounded = true).size(pageWidth * b.width, pageHeight * b.height)
        .composerPageAppearance(pageClip, viewportClip, uiColors.outsidePageContentAlpha)
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
            alpha = if (element.mediaFrame() == null) element.transform.opacity else 1f
        }
        .then(
            if (!interactive || !actions.canEdit(element.id)) Modifier else if (positioning) {
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
                    onLongPress = onBeginMultiSelection,
                    onTap = { onSelectTap(); if (!multiSelect) { onPickMedia?.invoke() ?: onTap() } },
                    onBeginGesture = { actions.beginMoveGesture(translatedX - b.x) },
                    onEndGesture = actions::endGesture,
                    onMove = { dx, dy -> actions.moveSelectedWithGuides(dx, dy,
                        snapDistancePx / pageWidthPx, snapDistancePx / pageHeightPx) },
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
        // Semantic actions follow the same selection rules as touch and keyboard input.
        .semantics {
            if (element.id.startsWith("layout-decoration:")) return@semantics
            contentDescription = elementLabel
            role = Role.Button
            // In a media slot TalkBack's double tap is worth what the tap on the stage is worth:
            // opening the library. Choosing an empty slot leads nowhere.
            if (interactive && actions.canEdit(element.id)) {
                onClick(label = if (onPickMedia != null) pickMediaLabel else selectLabel) {
                    onSelectTap()
                    if (!multiSelect) onPickMedia?.invoke()
                    true
                }
                onLongClick(label = selectLabel) { onBeginMultiSelection(); true }
            }
        }
    when (element) {
        is CompositionElement.MediaPlaceholder -> Box(
            common.frameDecoration(element.frame, pageWidthPx / REFERENCE_PAGE_WIDTH, element.transform.opacity).background(uiColors.text.copy(alpha = .08f))
                .border(1.dp, uiColors.text.copy(alpha = .35f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, stringResource(Res.string.composer_add_media), tint = uiColors.text.copy(alpha = .7f)) }
        // The element's recipe comes in here for the same reason it comes in on export: the stage has
        // to show the photo with the look the final file will have.
        is CompositionElement.Photo -> Box(common.frameDecoration(element.frame, pageWidthPx / REFERENCE_PAGE_WIDTH, element.transform.opacity)) {
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
                    color = uiColors.text,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
        }
        is CompositionElement.Video -> Box(common.frameDecoration(element.frame, pageWidthPx / REFERENCE_PAGE_WIDTH, element.transform.opacity)) {
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
                Surface(color = uiColors.workspace.copy(alpha = .55f), shape = CircleShape, modifier = Modifier.align(Alignment.Center)) {
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
            LaunchedEffect(fontBytes, element.id) { if (fontBytes != null) actions.refreshTextMetrics(element.id) }
            Canvas(common) { drawCompositionText(element, pageWidthPx / REFERENCE_PAGE_WIDTH, fontBytes) }
        }
        // Fill **and** outline, like the exporter. The stage painted lines and arrows with `fillArgb`
        // and ignored `strokeArgb`, so the shape changed colour on export.
        is CompositionElement.Shape -> Canvas(common) { drawCompositionShape(element, pageWidthPx / REFERENCE_PAGE_WIDTH) }
        is CompositionElement.Drawing -> Canvas(common) { drawCompositionDrawing(element, pageWidthPx / REFERENCE_PAGE_WIDTH) }
    }
}

/**
 * Corners, shadow and outline, as the exporter draws them.
 *
 * The frame was only applied on export — on the stage the outline and shadow buttons did nothing
 * visible, and the final file came out with a decoration nobody had seen.
 */
@Composable
private fun Modifier.frameDecoration(frame: FrameStyle, unit: Float, opacity: Float): Modifier {
    return compositionFrameDecoration(frame, unit, opacity)
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
                if (event.changes.any { it.isConsumed }) break
                val zoom = event.calculateZoom()
                if (zoom.isFinite() && zoom != 1f) onZoomBy(zoom)
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
internal fun placementAlignment(crop: PlacementCrop) =
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
internal fun Modifier.placementZoom(crop: PlacementCrop): Modifier {
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

/** The same for the background, with the recipe it kept — a crop or a quarter turn changes its slack too. */
private fun CompositionBackground.Photo.contentAspect(): Float? {
    val width = media.width?.takeIf { it > 0L } ?: return null
    val height = media.height?.takeIf { it > 0L } ?: return null
    return recipe?.recipe?.editedAspectRatio(width, height)
        ?: (width.toFloat() / height).takeIf { it.isFinite() && it > 0f }
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
    outlineActive: Boolean,
    onOutline: () -> Unit,
    onRequestGroup: () -> Unit,
    onDeleted: (String) -> Unit,
) {
    val uiColors = ImagoUi.colors
    val selected = state.displayProject()?.elements?.firstOrNull { it.id == state.selectedElementId } ?: return
    val chosen = state.selectedElementIds.size
    if (selected is CompositionElement.Text && !state.hasSelectionTools()) {
        ComposerTextTools(selected, state, actions, rail)
        return
    }
    val outlineElements = state.outlineSelection()
    val isMedia = selected is CompositionElement.Photo ||
        selected is CompositionElement.Video ||
        selected is CompositionElement.MediaPlaceholder
    // Collapsed by default: the composition is what is being edited, and a fine adjustment is asked for
    // less often than the result is looked at. It reopens per element — switching selection should not
    // inherit the previous one's open drawer.
    var expanded by remember(selected.id, rail) { mutableStateOf(rail) }
    var pendingColor by remember(selected.id) { mutableStateOf<PendingColor?>(null) }
    var choosingFont by remember(selected.id) { mutableStateOf(false) }
    var choosingFrames by remember(selected.id) { mutableStateOf(false) }
    if (choosingFrames) ComposerFramesDialog(state, actions, onDismiss = { choosingFrames = false })
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
            compositionColors = state.project?.usedColors().orEmpty(),
            onDismiss = { pendingColor = null },
            onConfirm = pending.onPick,
        )
    }
    val chrome = inspectorChromeAlpha(rail)
    Column(Modifier.padding(horizontal = ImagoSpacing.Sm), horizontalAlignment = Alignment.CenterHorizontally) {
        // Six buttons, not twenty-five. What is continuous is a slider in the drawer; what belongs to
        // the same subject — align, arrange — sits behind a menu. What is left in the row is what is
        // used on every element one taps.
        ComposerToolRow(
            Modifier.alpha(chrome),
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
                    if (outlineElements.isNotEmpty() && outlineElements.all { it is CompositionElement.Shape && it.isClosedShape() }) {
                        ToolButton(stringResource(Res.string.composer_fill), Icons.Outlined.FormatColorFill, onClick = onOutline)
                    }
                }
                is CompositionElement.Text -> ToolButton(stringResource(Res.string.composer_color), Icons.Outlined.Colorize) {
                    pendingColor = PendingColor(uiText(Res.string.composer_text_color), selected.colorArgb, actions::setElementColor)
                }
                is CompositionElement.Drawing -> ToolButton(stringResource(Res.string.composer_color), Icons.Outlined.Colorize) {
                    pendingColor = PendingColor(uiText(Res.string.composer_stroke_color), selected.colorArgb, actions::setElementColor)
                }
                else -> Unit
            }
            if (outlineElements.isNotEmpty()) ToolButton(stringResource(Res.string.composer_outline), Icons.Outlined.BorderColor,
                active = outlineActive, onClick = onOutline)
            if (state.frameDesignSelection().isNotEmpty()) ToolButton(stringResource(Res.string.composer_frame_library), Icons.Outlined.CropPortrait) { choosingFrames = true }
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
            ToolButton(stringResource(Res.string.composer_lock), Icons.Outlined.Lock, onClick = actions::toggleLocked)
            ToolButton(stringResource(Res.string.composer_duplicate), Icons.Outlined.ContentCopy, onClick = actions::duplicateSelected)
            // The caption is read before deleting: afterwards there is no element to take it from.
            val deletedLabel = if (chosen > 1) {
                stringResource(Res.string.composer_elements_deleted, chosen)
            } else {
                stringResource(Res.string.composer_element_deleted, selected.accessibilityLabel())
            }
            ToolButton(stringResource(Res.string.composer_delete), Icons.Outlined.Delete, tint = uiColors.danger) {
                val label = deletedLabel
                actions.deleteSelected()
                onDeleted(label)
            }
        }
        // Right under the tools and outside the drawer: with a video in hand, when it plays is what one
        // came for. Every video on the page is there, so the selected one is lined up against the rest.
        if (chosen <= 1 && selected is CompositionElement.Video) {
            PageVideoTimeline(checkNotNull(state.project), selected.pageIndex, selected.id, actions, Modifier.alpha(chrome))
        }
        if (positioning) {
            Text(
                stringResource(Res.string.composer_mask_hint),
                style = MaterialTheme.typography.bodySmall,
                color = uiColors.accent,
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
                ComposerToolRow(
                    Modifier.fillMaxWidth().alpha(chrome),
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
                if (isMedia && (selected !is CompositionElement.Video || supportsVideoFrameOutlines)) {
                    val frame = selected.frameStyle() ?: FrameStyle()
                    InspectorSlider(stringResource(Res.string.composer_shadow), frame.shadowRadius, 0f..MAX_FRAME_SHADOW, 0f, actions, actions::setFrameShadow)
                }
                if (selected is CompositionElement.Text) {
                    TextStyleControls(selected, actions, rail, onPickColor = { pendingColor = it })
                }
                if (selected is CompositionElement.Video) {
                    VideoSoundControls(selected, actions)
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

/** A clip's sound, in the drawer: the timeline already has where it plays and whether it is heard. */
@Composable
private fun VideoSoundControls(video: CompositionElement.Video, actions: ComposerEditorViewModel) {
    val timing = video.timing
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        InspectorSlider(stringResource(Res.string.composer_volume), timing.volume, 0f..1f, 1f, actions) { actions.adjustVideoVolume(it - timing.volume) }
        ComposerToolRow(
            Modifier.fillMaxWidth(),
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

internal fun Float.formatOne(): String = String.format(java.util.Locale.getDefault(), "%.1f", this)

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
    val uiColors = ImagoUi.colors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = uiColors.surface, dragHandle = { SheetHandle() }) {
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
    val uiColors = ImagoUi.colors
    Column(modifier.padding(horizontal = ImagoSpacing.Md)) {
        Text(stringResource(Res.string.composer_layers), style = MaterialTheme.typography.titleLarge, color = uiColors.text)
        Text(stringResource(Res.string.composer_layers_hint), style = MaterialTheme.typography.bodySmall,
            color = uiColors.tertiaryText, modifier = Modifier.padding(bottom = ImagoSpacing.Sm))
        ComposerLayersList(state, actions, Modifier.weight(1f))
    }
}
@Composable
internal fun LayerRow(
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
    memberOfGroup: Boolean = false,
) {
    val uiColors = ImagoUi.colors
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember(element.id) { mutableStateOf(false) }
    val editable = if (memberOfGroup) !element.locked else actions.canEdit(element.id)
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
            .padding(start = if (memberOfGroup) 16.dp else 0.dp)
            .testTag("composer-layer-${element.id}")
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(
                when {
                    dragging -> uiColors.selectedSurface
                    selected -> uiColors.accent.copy(alpha = .14f)
                    else -> uiColors.elevated
                },
            )
            .combinedClickable(
                enabled = editable,
                onClick = {
                    if (memberOfGroup && (actions.state.value.editingGroupId != element.groupId || !actions.state.value.multiSelect)) actions.focusGroupMember(element.id)
                    else if (actions.state.value.multiSelect) actions.toggleElementInSelection(element.id) else actions.focusElement(element.id)
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (memberOfGroup) actions.beginGroupMemberMultiSelection(element.id) else actions.beginMultiSelection(element.id)
                },
            )
            .padding(ImagoSpacing.Sm)
            // The drag handle does not exist for TalkBack, and the "Back"/"Forward" buttons that played
            // that role left the row. The custom actions keep the promise: the screen reader announces
            // them without them taking up space.
            .semantics {
                contentDescription = label
                customActions = if (!editable) emptyList() else listOf(
                    CustomAccessibilityAction(layerForward) {
                        val possible = index > 0
                        if (possible) { if (memberOfGroup) actions.reorderGroupMember(element.groupId!!, index, index - 1) else actions.reorderLayer(index, index - 1) }
                        possible
                    },
                    CustomAccessibilityAction(layerBackward) {
                        val possible = index < lastIndex
                        if (possible) { if (memberOfGroup) actions.reorderGroupMember(element.groupId!!, index, index + 1) else actions.reorderLayer(index, index + 1) }
                        possible
                    },
                    CustomAccessibilityAction(moveLeft) { if (memberOfGroup) actions.focusGroupMember(element.id); actions.moveElement(element.id, -.02f, 0f); true },
                    CustomAccessibilityAction(moveRight) { if (memberOfGroup) actions.focusGroupMember(element.id); actions.moveElement(element.id, .02f, 0f); true },
                    CustomAccessibilityAction(moveUp) { if (memberOfGroup) actions.focusGroupMember(element.id); actions.moveElement(element.id, 0f, -.02f); true },
                    CustomAccessibilityAction(moveDown) { if (memberOfGroup) actions.focusGroupMember(element.id); actions.moveElement(element.id, 0f, .02f); true },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayerThumbnail(element, thumbnailUrl, apiKey)
        Column(Modifier.weight(1f).padding(horizontal = ImagoSpacing.Sm)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (element.visible) uiColors.text else uiColors.disabledText,
                maxLines = 1,
            )
            Text(
                element.layerSubtitle(actions.state.value.project?.pages?.size ?: 1),
                style = MaterialTheme.typography.labelSmall,
                color = uiColors.tertiaryText,
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(ImagoSizes.TouchTarget).testTag("composer-layer-options-${element.id}")) {
                Icon(Icons.Outlined.MoreVert, stringResource(Res.string.composer_more), tint = uiColors.secondaryText)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ToolMenuItem(stringResource(if (element.locked) Res.string.composer_unlock else Res.string.composer_lock),
                    if (element.locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, { menuOpen = false }) {
                    actions.toggleElementLocked(element.id, wholeGroup = !memberOfGroup)
                }
                ToolMenuItem(stringResource(if (element.visible) Res.string.composer_hide else Res.string.composer_show),
                    if (element.visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, { menuOpen = false }) { actions.toggleElementVisible(element.id) }
                DropdownMenuItem(text = { Text(stringResource(Res.string.composer_delete_element)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) }, enabled = editable, onClick = {
                        menuOpen = false
                        if (memberOfGroup) actions.deleteGroupMember(element.id) else actions.deleteElement(element.id)
                    })
            }
        }
        Box(
            Modifier
                .size(ImagoSizes.TouchTarget)
                .pointerInput(element.id, editable) {
                    if (!editable) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startDrag() },
                        onDragEnd = { endDrag() },
                        onDragCancel = { endDrag() },
                    ) { change, delta -> change.consume(); drag(delta.y) }
                }
                .semantics { contentDescription = reorderLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.DragHandle, null, Modifier.size(ImagoSizes.IconSmall), tint = uiColors.tertiaryText)
        }
    }
}

/** The layer's thumbnail: the real image when there is one, otherwise a drawn summary of the element. */
@Composable
private fun LayerThumbnail(element: CompositionElement, thumbnailUrl: (String) -> String, apiKey: (String) -> String) {
    val uiColors = ImagoUi.colors
    val shape = RoundedCornerShape(ImagoRadii.Small)
    Box(
        Modifier.size(LayerThumbnailSize).clip(shape).background(uiColors.elevated)
            .border(1.dp, uiColors.subtleBorder, shape),
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
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(ImagoSizes.IconSmall), tint = uiColors.text)
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
                        element.points.drop(1).forEach { if (it.startsStroke) moveTo(it.x * size.width, it.y * size.height) else lineTo(it.x * size.width, it.y * size.height) }
                    }
                    drawPath(path, Color(element.colorArgb), style = Stroke(2.dp.toPx()))
                }
            }
            is CompositionElement.MediaPlaceholder -> Canvas(Modifier.fillMaxSize().padding(ImagoSpacing.Xs)) {
                drawRect(
                    uiColors.tertiaryText,
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
    val uiColors = ImagoUi.colors
    val background = project.pages.getOrNull(state.currentPage)?.backgroundOverride ?: project.background
    val shape = RoundedCornerShape(ImagoRadii.Medium)
    Row(
        Modifier.fillMaxWidth().padding(vertical = ImagoSpacing.Sm).clip(shape)
            .background(if (state.backgroundSelected) uiColors.accent.copy(alpha = .14f) else uiColors.elevated).padding(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LayerThumbnailSize).clip(RoundedCornerShape(ImagoRadii.Small))
                .background(
                    when (background) {
                        is CompositionBackground.Solid -> Color(background.argb)
                        is CompositionBackground.Gradient -> Color(background.startArgb)
                        is CompositionBackground.Photo -> uiColors.elevated
                    },
                )
                .border(1.dp, uiColors.subtleBorder, RoundedCornerShape(ImagoRadii.Small)),
            contentAlignment = Alignment.Center,
        ) {
            if (background is CompositionBackground.Photo) {
                Icon(Icons.Outlined.Image, null, Modifier.size(ImagoSizes.IconSmall), tint = uiColors.secondaryText)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = ImagoSpacing.Sm)) {
            Text(stringResource(Res.string.composer_background), style = MaterialTheme.typography.bodyMedium, color = uiColors.text)
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
                color = uiColors.tertiaryText,
            )
        }
        Icon(Icons.Outlined.Wallpaper, null, Modifier.size(ImagoSizes.IconSmall), tint = uiColors.tertiaryText)
    }
}

@Composable
private fun CompositionElement.layerSubtitle(pageCount: Int): String {
    val pageIndex = when {
        this is CompositionElement.Video -> this.pageIndex
        repeatOnPages.isNotEmpty() -> repeatOriginPage()
        else -> kotlin.math.floor(transform.bounds.x).toInt().coerceIn(0, pageCount - 1)
    }
    val b = transform.bounds
    val outside = b.right <= 0f || b.x >= pageCount || b.bottom <= 0f || b.y >= 1f
    val page = stringResource(Res.string.composer_page_n, pageIndex + 1)
    val flags = listOfNotNull(
        Res.string.composer_flag_hidden.takeIf { !visible },
        Res.string.composer_flag_locked.takeIf { locked },
        Res.string.composer_flag_grouped.takeIf { groupId != null },
        Res.string.composer_flag_repeated.takeIf { repeatOnPages.isNotEmpty() },
        Res.string.composer_flag_outside.takeIf { outside },
    ).map { stringResource(it) }
    return (listOf(page) + flags).joinToString(" · ")
}

@Composable
private fun PageControls(
    state: ComposerEditorUiState,
    actions: ComposerEditorViewModel,
    onPageDeleted: (String) -> Unit,
) {
    val uiColors = ImagoUi.colors
    val project = state.project ?: return
    Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
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
        ComposerToolRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
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
            ToolButton(stringResource(Res.string.composer_delete), Icons.Outlined.Delete, tint = uiColors.danger) {
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
    val uiColors = ImagoUi.colors
    val width = PageStripHeight * project.format.pixelWidth / project.format.pixelHeight
    val pageLabel = stringResource(Res.string.composer_page_n, page.index + 1)
    Box(
        Modifier
            .size(width, PageStripHeight)
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .border(
                width = if (current) 2.dp else 1.dp,
                color = if (current) uiColors.accent else uiColors.border,
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
            color = uiColors.workspace.copy(alpha = .6f),
            shape = RoundedCornerShape(topStart = ImagoRadii.Small),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Text(
                "${page.index + 1}",
                fontSize = 10.sp,
                color = uiColors.text,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Where the chosen media goes. The background stopped requiring a pass through an element. */
internal sealed interface MediaPickerTarget {
    data class Element(val replaceId: String?) : MediaPickerTarget
    data class Background(val currentPageOnly: Boolean) : MediaPickerTarget
    data class NewGrid(val pageIndex: Int) : MediaPickerTarget
    data class Grid(val groupId: String, val draft: Boolean = false) : MediaPickerTarget
}

internal typealias ComposerMediaPickerContent = @Composable (target: MediaPickerTarget, onDismiss: () -> Unit, onPicked: (List<ComposerMedia>) -> Unit) -> Unit
internal val LocalComposerMediaPicker = androidx.compose.runtime.staticCompositionLocalOf<ComposerMediaPickerContent> {
    { target, dismiss, picked -> ComposerMediaPicker(target, dismiss, picked) }
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
    val uiColors = ImagoUi.colors
    val forBackground = target is MediaPickerTarget.Background
    Surface(
        color = uiColors.background,
        modifier = Modifier
            .fillMaxSize()
            // Opaque is not enough: without something consuming the touches, those falling outside the
            // grid hit the composer buttons left underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        LibraryPickerRoute(
            title = if (forBackground) stringResource(Res.string.composer_choose_background) else stringResource(Res.string.composer_add_media),
            subtitle = when (target) {
                is MediaPickerTarget.Background -> stringResource(Res.string.composer_background_photos_only)
                is MediaPickerTarget.NewGrid -> stringResource(Res.string.composer_grid_choose_multiple)
                else -> null
            },
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
    tint: Color = ImagoUi.colors.secondaryText,
    onClick: () -> Unit,
) {
    val uiColors = ImagoUi.colors
    // The filled capsule marks what is on — drawing mode, the adjustments drawer. Over a stage of
    // arbitrary colour, a difference in opacity alone does not read.
    val content = if (active) uiColors.text else tint
    val toolWidth = LocalComposerContextToolWidth.current
    Column(
        Modifier.then(if (toolWidth.isSpecified) Modifier.width(toolWidth) else Modifier).clip(RoundedCornerShape(ImagoRadii.Small))
            .background(if (active) uiColors.selectedSurface else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = content)
        Text(label, fontSize = 10.sp, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
internal fun ToolMenuButton(
    label: String,
    icon: ImageVector,
    onOpen: () -> Unit = {},
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(label, icon, active = open) { onOpen(); open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            content { open = false }
        }
    }
}

/** An entry of a [ToolMenuButton]. It closes the menu on its own: none of these actions is repeatable. */
@Composable
internal fun ToolMenuItem(
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
    val uiColors = ImagoUi.colors
    Box(
        Modifier
            .padding(horizontal = ImagoSpacing.Sm, vertical = ImagoSpacing.Sm)
            .width(1.dp)
            .height(28.dp)
            .background(uiColors.border),
    )
}

@Composable
internal fun CompositionElement.accessibilityLabel(): String = when (this) {
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

@Composable
private fun ShapeKind.label(): String = stringResource(
    when (this) {
        ShapeKind.RECTANGLE -> Res.string.composer_rectangle
        ShapeKind.ELLIPSE -> Res.string.composer_circle
        ShapeKind.LINE -> Res.string.composer_line
        ShapeKind.ARROW -> Res.string.composer_arrow
    },
)

private fun ShapeKind.icon(): ImageVector = when (this) {
    ShapeKind.RECTANGLE -> Icons.Outlined.Rectangle
    ShapeKind.ELLIPSE -> Icons.Outlined.Circle
    ShapeKind.LINE -> Icons.Outlined.Remove
    ShapeKind.ARROW -> Icons.AutoMirrored.Outlined.ArrowForward
}

/**
 * The floating pill of a stage mode (drawing, shapes): the editor's glass, over the page, with the
 * mode's settings. It floats instead of being a bar of the top app bar so it takes no height from the
 * stage — the page stays where it is when the mode starts.
 */
@Composable
internal fun ComposerModeBubble(
    modifier: Modifier = Modifier,
    onSize: (androidx.compose.ui.unit.IntSize) -> Unit,
    onDrag: (Offset) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val uiColors = ImagoUi.colors
    val shape = RoundedCornerShape(ImagoRadii.Panel)
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(modifier.onSizeChanged(onSize)) {
        Box(Modifier.matchParentSize().clip(shape).imagoGlassBlur().background(Color.Transparent))
        Surface(
            color = uiColors.glass,
            shape = shape,
            modifier = Modifier.border(1.dp, uiColors.subtleBorder, shape),
        ) {
            Column(Modifier.padding(horizontal = ImagoSpacing.Sm, vertical = 4.dp)) {
                // The grip: the only part of the bubble that moves it, so sliders and buttons keep their own drags.
                Box(
                    Modifier.fillMaxWidth().height(20.dp).testTag("composer-bubble-grip").pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            currentOnDrag(drag)
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(uiColors.strongBorder))
                }
                content()
            }
        }
    }
}

/** Keeps the bubble on the stage, wherever it was dragged to: its top-centre rest position is the origin. */
private fun clampBubble(
    offset: Offset,
    stage: androidx.compose.ui.unit.IntSize,
    bubble: androidx.compose.ui.unit.IntSize,
): Offset {
    if (stage == androidx.compose.ui.unit.IntSize.Zero) return offset
    val halfRoom = ((stage.width - bubble.width) / 2f).coerceAtLeast(0f)
    return Offset(
        offset.x.coerceIn(-halfRoom, halfRoom),
        offset.y.coerceIn(-(stage.height * .1f), (stage.height - bubble.height).toFloat().coerceAtLeast(0f)),
    )
}

/** The colour in use, as a circle; tapping it opens the picker. */
@Composable
internal fun ColorDot(argb: Long, label: String = stringResource(Res.string.composer_color), onClick: () -> Unit) {
    val uiColors = ImagoUi.colors
    Box(
        Modifier.padding(horizontal = 6.dp).size(28.dp).clip(CircleShape)
            .background(Color(argb)).border(1.5.dp, uiColors.strongBorder, CircleShape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/** The thickness row shared by both bubbles. */
@Composable
private fun BubbleThickness(
    value: Float,
    min: Float,
    label: String = stringResource(Res.string.composer_thickness),
    maximum: Float = MAX_DRAWING_STROKE,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.width(300.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        ImagoSlider(
            value = value,
            onValueChange = onValueChange,
            range = min..maximum,
            neutral = min,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = label },
        )
        Text(value.formatOne(), style = MaterialTheme.typography.labelMedium)
    }
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
