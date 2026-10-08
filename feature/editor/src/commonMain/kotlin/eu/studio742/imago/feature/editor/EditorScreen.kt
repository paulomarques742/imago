@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.editor.resources.*
import kotlin.math.exp
import androidx.compose.ui.input.pointer.PointerEventType
import eu.studio742.imago.core.render.libraryAuth
import coil3.request.crossfade
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Deblur
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import eu.studio742.imago.core.render.DOUBLE_TAP_PHOTO_ZOOM
import eu.studio742.imago.core.render.PhotoCanvas
import eu.studio742.imago.core.render.pixelHeight
import eu.studio742.imago.core.render.pixelWidth
import eu.studio742.imago.core.render.MIN_CROP_ZOOM
import eu.studio742.imago.core.render.MIN_PHOTO_ZOOM
import eu.studio742.imago.core.render.PhotoTransform
import eu.studio742.imago.core.render.frameGeometry
import eu.studio742.imago.core.render.photoBounds
import eu.studio742.imago.core.render.PhotoBounds
import eu.studio742.imago.core.render.drag
import eu.studio742.imago.core.render.pinch
import eu.studio742.imago.core.render.settle
import eu.studio742.imago.core.render.toggleDoubleTap
import eu.studio742.imago.core.designsystem.AutoSaveStatus
import eu.studio742.imago.core.designsystem.FocusedAdjustmentScale
import eu.studio742.imago.core.designsystem.ImagoScaleTint
import eu.studio742.imago.core.designsystem.ImagoSlider
import eu.studio742.imago.core.designsystem.SheetHandle
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoParameterSlider
import eu.studio742.imago.core.designsystem.ImagoMotion
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.LocalImagoWindow
import eu.studio742.imago.core.designsystem.LocalReducedMotion
import eu.studio742.imago.core.designsystem.imagoGlassBlur
import eu.studio742.imago.core.designsystem.imagoTween
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Perspective
import eu.studio742.imago.core.model.MAX_UPRIGHT_GUIDES
import eu.studio742.imago.core.model.MIN_UPRIGHT_GUIDES
import eu.studio742.imago.core.model.UPRIGHT_GUIDED
import eu.studio742.imago.core.model.UPRIGHT_AUTO
import eu.studio742.imago.core.model.UPRIGHT_FULL
import eu.studio742.imago.core.model.UPRIGHT_LEVEL
import eu.studio742.imago.core.model.UPRIGHT_VERTICAL
import eu.studio742.imago.core.model.UPRIGHT_OFF
import eu.studio742.imago.core.model.UprightGuide
import eu.studio742.imago.core.render.asComposeImage
import kotlin.math.abs
import kotlin.math.roundToInt

/** How long without touches until the interface steps back and leaves only the photo. */
private const val CHROME_IDLE_TIMEOUT_MS = 3_000L

/** How long the mask's red stays lit after the finger lets go. */
private const val MASK_FLASH_MILLIS = 900L

/** How much each accessibility action moves the crop. A coarse step, which is what the finger does not give. */
private const val CROP_NUDGE = 0.03f

/** The radius within which a touch counts as having grabbed a frame handle. */
private val CROP_HANDLE_HIT_RADIUS = 28.dp

/**
 * What the chrome takes up at the top and bottom of the editor screen.
 *
 * Measured and not estimated. There used to be two hand-written numbers — a `56.dp` for a bar that
 * measures 64, and `panelHeight` alone for a column that also has the toolbar below — and the
 * difference was photo covered up. As long as the height comes from whoever takes it, it cannot
 * diverge.
 */
/**
 * How much the chrome takes from the stage, and on which side.
 *
 * On a narrow screen the panel sits at the bottom and eats height; in a rail it sits on the right and
 * eats width. The stage does not need to know which of the two is mounted — only to step back by
 * whatever is here.
 */
private data class EditorChromeInsets(
    val top: Dp = 64.dp,
    val bottom: Dp = 0.dp,
    val end: Dp = 0.dp,
)

/** The rail's width: a slice of the window, but never so narrow that the sliders stop being usable. */
private val RAIL_WIDTH_MIN = 320.dp
private val RAIL_WIDTH_MAX = 400.dp
private const val RAIL_WIDTH_FRACTION = 0.3f

/** What the recipe editor adds to the editor's bar and menu; null while editing a photo. */
internal class RecipeEditActions(
    val onSave: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
fun EditorRoute(
    asset: EditorAsset,
    onBack: () -> Unit,
    assets: List<EditorAsset> = listOf(asset),
    selectedIndex: Int = 0,
    onSelectIndex: (Int) -> Unit = {},
    target: PhotoEditTarget = PhotoEditTarget.Asset(asset),
    onCompositionCommit: (EditRecipe) -> Unit = {},
    viewModel: EditorViewModel = editorViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val compositionMode = target is PhotoEditTarget.Composition
    val recipeMode = target is PhotoEditTarget.Recipe
    DisposableEffect(asset.id, target) {
        viewModel.open(target)
        onDispose { viewModel.saveNow() }
    }
    // A recipe is saved by hand, unlike a photo: leaving with changes asks first, and one that is not
    // the user's yet — a filter, a new recipe — asks for a name the first time it is saved.
    var askingToLeave by rememberSaveable { mutableStateOf(false) }
    var namingRecipe by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var leaveOnceSaved by rememberSaveable { mutableStateOf(false) }
    val leave = {
        viewModel.saveNow()
        onBack()
    }
    val requestLeave = {
        if (recipeMode && state.hasUnsavedRecipeChanges) askingToLeave = true else leave()
    }
    val saveRecipe = { thenLeave: Boolean ->
        if (state.recipeEdit?.saved != null) viewModel.saveRecipeEdits() else namingRecipe = true
        leaveOnceSaved = thenLeave
    }
    // The save is asynchronous: leaving waits for it to land, and does not happen if it failed.
    LaunchedEffect(leaveOnceSaved, namingRecipe, state.hasUnsavedRecipeChanges, state.recipeEdit?.saved) {
        if (leaveOnceSaved && !namingRecipe && !state.hasUnsavedRecipeChanges && state.recipeEdit?.saved != null) {
            leaveOnceSaved = false
            leave()
        }
    }
    BackHandler { requestLeave() }
    EditorScreen(
        state = state,
        compositionMode = compositionMode,
        onBack = requestLeave,
        onConfirm = {
            viewModel.currentRecipe()?.let(onCompositionCommit)
            onBack()
        },
        assets = assets,
        selectedIndex = selectedIndex,
        onSelectIndex = onSelectIndex,
        onRetry = viewModel::retry,
        onSelectPanel = viewModel::selectPanel,
        onSelectSheet = viewModel::selectSheet,
        onRestoreHistory = viewModel::restoreHistory,
        onClearHistory = viewModel::clearHistory,
        onApplyConflict = viewModel::applyConflictVersion,
        onAdjustment = viewModel::updateAdjustment,
        onAdjustmentFinished = viewModel::finishAdjustment,
        onResetAdjustment = viewModel::resetAdjustment,
        onShowOriginal = viewModel::showOriginal,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onSelectHslBand = viewModel::selectHslBand,
        onAddCurvePoint = viewModel::addCurvePoint,
        onMoveCurvePoint = viewModel::moveCurvePoint,
        onRemoveCurvePoint = viewModel::removeCurvePoint,
        onResetCurve = viewModel::resetCurve,
        onHsl = viewModel::updateHsl,
        onResetHsl = viewModel::resetHsl,
        onSelectCropAspect = viewModel::selectCropAspect,
        onResetCrop = viewModel::resetCrop,
        onCropGestureStart = viewModel::beginCropGesture,
        onCropRectChange = viewModel::updateCropRect,
        onCropGestureFinished = viewModel::finishCropGesture,
        onStraighten = viewModel::updateStraighten,
        onResetStraighten = viewModel::resetStraighten,
        onRotateClockwise = viewModel::rotateClockwise,
        onToggleMirrorHorizontal = viewModel::toggleMirrorHorizontal,
        onToggleMirrorVertical = viewModel::toggleMirrorVertical,
        onResetGeometry = viewModel::resetGeometry,
        onCopyRecipe = viewModel::copyCurrentRecipe,
        onPasteRecipe = viewModel::pasteCopiedRecipe,
        onSaveRecipe = viewModel::saveCurrentRecipe,
        onApplySavedRecipe = viewModel::applySavedRecipe,
        onApplyBuiltInRecipe = viewModel::applyBuiltInRecipe,
        onToggleRecipeFavorite = viewModel::toggleSavedRecipeFavorite,
        onUpdateSavedRecipe = viewModel::updateSavedRecipe,
        onDeleteSavedRecipe = viewModel::deleteSavedRecipe,
        onConsumeRecipeNotice = viewModel::consumeRecipeNotice,
        onExportGallery = viewModel::exportToGallery,
        onExportImmich = viewModel::exportToImmich,
        onConsumeExportResult = viewModel::consumeExportResult,
        onExportPermissionDenied = {
            viewModel.reportExportError(uiText(Res.string.editor_gallery_permission_needed))
        },
        maskActions = remember(viewModel) {
            MaskActions(
                onAdd = viewModel::addMask,
                onSelect = viewModel::selectMask,
                onShowList = viewModel::showMaskList,
                onDelete = viewModel::deleteMask,
                onSetEnabled = viewModel::setMaskEnabled,
                onSetInverted = viewModel::setMaskInverted,
                onSetOverlayPinned = viewModel::setMaskOverlayPinned,
                onDismissNotice = viewModel::dismissMaskNotice,
                onBeginGesture = viewModel::beginMaskGesture,
                onMove = viewModel::moveMask,
                onSetRadius = viewModel::setMaskRadius,
                onSetWidth = viewModel::setMaskWidth,
                onRotate = viewModel::rotateMask,
            )
        },
        perspectiveActions = remember(viewModel) {
            PerspectiveActions(
                onConstrainCrop = viewModel::setConstrainCrop,
                onReset = viewModel::resetPerspective,
                onUprightMode = viewModel::setUprightMode,
                onGuideGestureStart = viewModel::beginGuideGesture,
                onGuidesChange = viewModel::updateGuides,
                onGuideGestureEnd = viewModel::finishGuideGesture,
                onRemoveLastGuide = viewModel::removeLastGuide,
                onClearGuides = viewModel::clearGuides,
            )
        },
        colorGradeActions = remember(viewModel) {
            ColorGradeActions(
                onSelectWheel = viewModel::selectColorGradeWheel,
                onHandle = viewModel::updateColorGradeHandle,
                onComponent = viewModel::updateColorGrade,
                onResetWheel = viewModel::resetColorGradeWheel,
                onResetAll = viewModel::resetColorGrading,
                onGestureFinished = viewModel::finishAdjustment,
            )
        },
        recipeActions = if (recipeMode) {
            RecipeEditActions(
                onSave = { saveRecipe(false) },
                onDuplicate = viewModel::duplicateRecipeEdit,
                onDelete = { confirmingDelete = true },
            )
        } else {
            null
        },
    )

    if (askingToLeave) {
        AlertDialog(
            onDismissRequest = { askingToLeave = false },
            title = { Text(stringResource(Res.string.editor_unsaved_recipe_title)) },
            text = { Text(stringResource(Res.string.editor_unsaved_recipe_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToLeave = false
                        saveRecipe(true)
                    },
                ) { Text(stringResource(Res.string.editor_tool_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            askingToLeave = false
                            leave()
                        },
                    ) { Text(stringResource(Res.string.editor_discard), color = ImagoColors.Danger) }
                    TextButton(onClick = { askingToLeave = false }) { Text(stringResource(Res.string.editor_cancel)) }
                }
            },
        )
    }
    if (namingRecipe) {
        val edit = state.recipeEdit
        RecipeDetailsDialog(
            title = stringResource(Res.string.editor_save_recipe),
            initialName = edit?.suggestedName?.resolve().orEmpty(),
            initialCollection = edit?.suggestedCollection?.resolve() ?: stringResource(Res.string.editor_default_collection),
            confirmLabel = stringResource(Res.string.editor_tool_save),
            onDismiss = {
                namingRecipe = false
                leaveOnceSaved = false
            },
            onConfirm = { name, collection ->
                viewModel.saveRecipeEdits(name, collection)
                namingRecipe = false
            },
        )
    }
    if (confirmingDelete) {
        val name = state.recipeEdit?.saved?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(Res.string.editor_delete_recipe_question)) },
            text = { Text(stringResource(Res.string.editor_recipe_delete_body, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        // Gone, so there is nothing left to ask about saving: straight out.
                        viewModel.deleteRecipeEdit(onDeleted = onBack)
                    },
                ) { Text(stringResource(Res.string.editor_delete), color = ImagoColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Res.string.editor_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    state: EditorUiState,
    compositionMode: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    assets: List<EditorAsset>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onRetry: () -> Unit,
    onSelectPanel: (EditorPanel) -> Unit,
    onSelectSheet: (EditorSheet) -> Unit,
    onRestoreHistory: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onApplyConflict: (Long) -> Unit,
    onAdjustment: (Adjustment, Float) -> Unit,
    onAdjustmentFinished: () -> Unit,
    onResetAdjustment: (Adjustment) -> Unit,
    onShowOriginal: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectHslBand: (HslColorBand) -> Unit,
    onAddCurvePoint: (Int, Int) -> Unit,
    onMoveCurvePoint: (Int, Int, Int) -> Unit,
    onRemoveCurvePoint: (Int) -> Unit,
    onResetCurve: () -> Unit,
    onHsl: (HslColorBand, HslComponent, Float) -> Unit,
    onResetHsl: (HslColorBand, HslComponent) -> Unit,
    onSelectCropAspect: (String?) -> Unit,
    onResetCrop: () -> Unit,
    onCropGestureStart: () -> Unit,
    onCropRectChange: (CropRect) -> Unit,
    onCropGestureFinished: () -> Unit,
    onStraighten: (Float) -> Unit,
    onResetStraighten: () -> Unit,
    onRotateClockwise: () -> Unit,
    onToggleMirrorHorizontal: () -> Unit,
    onToggleMirrorVertical: () -> Unit,
    onResetGeometry: () -> Unit,
    onCopyRecipe: () -> Unit,
    onPasteRecipe: () -> Unit,
    onSaveRecipe: (String, String) -> Unit,
    onApplySavedRecipe: (String) -> Unit,
    onApplyBuiltInRecipe: (String) -> Unit,
    onToggleRecipeFavorite: (String) -> Unit,
    onUpdateSavedRecipe: (String, String, String) -> Unit,
    onDeleteSavedRecipe: (String) -> Unit,
    onConsumeRecipeNotice: () -> Unit,
    onExportGallery: () -> Unit,
    onExportImmich: (String) -> Unit,
    onConsumeExportResult: () -> Unit,
    onExportPermissionDenied: () -> Unit,
    maskActions: MaskActions,
    colorGradeActions: ColorGradeActions,
    perspectiveActions: PerspectiveActions,
    recipeActions: RecipeEditActions?,
) {
    val recipeMode = recipeActions != null
    // `rememberSaveable` for the ones that open over the photo: a dialog that disappears halfway
    // because the screen rotated looks like an app failure, and on a tablet it rotates all the time.
    // `editingSavedRecipe` and `deletingSavedRecipe` stay out: they keep a whole `SavedRecipe` and not
    // a boolean — persisting them would require a `Saver` this step does not need to have.
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showSaveRecipeDialog by rememberSaveable { mutableStateOf(false) }
    var showRecipeLibrary by rememberSaveable { mutableStateOf(false) }
    var editingSavedRecipe by remember { mutableStateOf<SavedRecipe?>(null) }
    var deletingSavedRecipe by remember { mutableStateOf<SavedRecipe?>(null) }
    val assetId = state.asset?.id
    // With width to spare, the tools leave the bottom of the photo and move to a column beside it.
    // There is no longer a drawer opening and closing: the photo and the controls are both in view at
    // the same time, which is the only thing a large screen has to offer here.
    val editorWindow = LocalImagoWindow.current
    val rail = editorWindow.prefersSidePanel
    val railWidth = (editorWindow.width * RAIL_WIDTH_FRACTION).coerceIn(RAIL_WIDTH_MIN, RAIL_WIDTH_MAX)
    var panelVisible by rememberSaveable(assetId) { mutableStateOf(false) }
    /**
     * The tools are in view — either because the drawer is open, or because there is no drawer at
     * all and they live in the rail.
     *
     * Whoever wants to know that asks here and never [panelVisible], which in a rail stays false
     * forever: no tap turns it on because there is nothing to open. That is how crop stopped arming
     * on a tablet — the button was in view in the column, but the condition that turns it on was
     * still looking at a drawer that does not exist there.
     */
    val toolsVisible = panelVisible || rail
    // Declared here, and not next to the layout, because the framing effects observe it.
    val cropMode = toolsVisible && state.isCropping
    /** The tools drawer: closed it shows the five main ones, open it shows them all. */
    // While a value is being dragged, the panel fades and lets the whole photo show: only the
    // focused mode's ruler survives. It lives here, and not inside the panel, because that ruler —
    // drawn outside it — is what needs to know what is being moved.
    var editingKey by remember(assetId, state.panel, state.sheet) { mutableStateOf<Any?>(null) }
    var chromeVisible by rememberSaveable(assetId) { mutableStateOf(true) }
    // Incremented on every touch; it is what restarts the auto-hide timer.
    var chromeInteraction by remember(assetId) { mutableIntStateOf(0) }
    var transform by remember(assetId) { mutableStateOf(PhotoTransform()) }
    // The last adjustment touched in the panel. With mouse and keyboard this is the one the arrows
    // fine-tune — on the phone it serves no purpose, and does not get in the way.
    var lastAdjustment by remember(assetId) { mutableStateOf<Adjustment?>(null) }
    val panelHeight = when (state.sheet) {
        EditorSheet.CROP -> ImagoSizes.CropPanelHeight
        // Colour grading is the only tool whose control is a circle, and a circle cannot be cut: the
        // angle is half of what it says. It asks for more height than the sliders, and gives it back
        // while dragging, which is when the panel fades.
        EditorSheet.COLOR_GRADING -> (editorWindow.height * ImagoSizes.WheelPanelHeightFraction)
            .coerceIn(ImagoSizes.WheelPanelHeightMin, ImagoSizes.WheelPanelHeightMax)
        // The window's height, and not the screen's: it is the same measure as the rail, and serves desktop.
        else -> (editorWindow.height * ImagoSizes.PanelHeightFraction)
            .coerceIn(ImagoSizes.PanelHeightMin, ImagoSizes.PanelHeightMax)
    }
    // Measured by the chrome blocks themselves, not estimated. The initial value is a seed so the
    // first frame does not jump — from then on the measurement rules.
    var chromeInsets by remember { mutableStateOf(EditorChromeInsets(bottom = panelHeight + 104.dp)) }
    // What the stage reads. In a rail the chrome measurement does not serve — it would measure the
    // whole column, top to bottom, and the stage would shrink until it vanished. The rail's width is known up front.
    val stageInsets = if (rail) {
        EditorChromeInsets(top = chromeInsets.top, bottom = 0.dp, end = railWidth)
    } else {
        chromeInsets
    }
    val chromeDensity = LocalDensity.current
    val cropHitRadius = with(chromeDensity) { CROP_HANDLE_HIT_RADIUS.toPx() }
    val quarterTurns by rememberUpdatedState(
        ((state.renderParameters.rotation % 360) + 360) % 360 / 90,
    )
    // The gesture uses the dimensions actually visible; that way, zoom and pan stay aligned with the
    // viewport when a crop is active.
    val imageSize by rememberUpdatedState(
        state.bitmap?.let { bitmap ->
            val swaps = quarterTurns % 2 == 1
            val orientedWidth = if (swaps) bitmap.height else bitmap.width
            val orientedHeight = if (swaps) bitmap.width else bitmap.height
            val croppedWidth = (orientedWidth * state.renderParameters.cropWidth).roundToInt().coerceAtLeast(1)
            val croppedHeight = (orientedHeight * state.renderParameters.cropHeight).roundToInt().coerceAtLeast(1)
            if (swaps) croppedHeight to croppedWidth else croppedWidth to croppedHeight
        } ?: (1 to 1),
    )
    // The **full** oriented dimensions. `imageSize` comes multiplied by the crop, but in crop mode the
    // canvas gets `cropWidth = 1` — swapping one for the other made the gesture's limits disagree with
    // the renderer right on the first frame.
    val fullImageSize by rememberUpdatedState(
        state.bitmap?.let { it.pixelWidth to it.pixelHeight } ?: (1 to 1),
    )
    // Everything the gesture block reads that is NOT a `by remember { mutableStateOf }` has to go
    // through here. A `mutableStateOf` survives recompositions and so can be read directly; a `val`
    // computed in the composition cannot — the block only restarts when `assetId` changes and stays
    // holding the value that existed then. That is how `cropMode` got stuck at false and crop mode
    // stopped responding to the finger.
    val croppingNow by rememberUpdatedState(cropMode)
    val railNow by rememberUpdatedState(rail)
    val currentGeometry by rememberUpdatedState(state.recipe?.geometry)
    val maskHitRadius = with(chromeDensity) { MASK_HANDLE_HIT_RADIUS.toPx() }
    // The bridge between what the mask stores — image coordinates — and what is on screen.
    // The guided Upright is on while its sheet is open: elsewhere a finger on the photo is the photo's.
    val guidingNow by rememberUpdatedState(
        state.sheet == EditorSheet.PERSPECTIVE && state.recipe?.geometry?.perspective?.upright == UPRIGHT_GUIDED,
    )
    val currentGuides by rememberUpdatedState(state.recipe?.geometry?.perspective?.guides.orEmpty())
    var guideFinger by remember(assetId) { mutableStateOf<Offset?>(null) }
    val guideHitRadius = with(chromeDensity) { GUIDE_HANDLE_HIT_RADIUS.toPx() }
    val loupePhoto = remember(state.bitmap) { state.bitmap?.asComposeImage() }
    val maskFrameGeometry by rememberUpdatedState(
        state.bitmap?.let { state.renderParameters.frameGeometry(it.pixelWidth, it.pixelHeight) },
    )
    // Without `panelVisible`, unlike crop. Lowering the drawer is precisely how one reaches the bottom
    // of the photo, and that is where a foreground mask has to be placeable; requiring the panel open
    // made the lower half unreachable. Crop is different because the drawer is the mode's only way out.
    val maskingNow by rememberUpdatedState(state.isMaskEditing)
    val selectedMaskNow by rememberUpdatedState(state.selectedMask)
    // The red lights up on touch and goes off on its own shortly after: fixed, it hid the result one is
    // trying to judge. "Show mask" is what pins it.
    var maskTouchCount by remember(assetId) { mutableIntStateOf(0) }
    var maskFlashing by remember(assetId) { mutableStateOf(false) }
    LaunchedEffect(maskTouchCount) {
        if (maskTouchCount == 0) return@LaunchedEffect
        maskFlashing = true
        delay(MASK_FLASH_MILLIS)
        maskFlashing = false
    }
    // The crop view has its own state. Sharing the viewer's was tempting and wrong: the `isFit` that
    // re-enables the swipe between photos is true for any zoom up to 1.01, and a zoom-out left by crop
    // reopened the pager with the photo shrunk.
    var cropTransform by remember(assetId) { mutableStateOf(PhotoTransform()) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    // Incremented by whoever wants the view to readjust to the frame. An explicit request and not a
    // reaction to the crop changing, because dragging the photo also changes the crop — and there
    // reframing would undo the choice just made.
    var reframeRequest by remember(assetId) { mutableIntStateOf(0) }
    var straightening by remember(assetId) { mutableStateOf(false) }
    val gestureScope = rememberCoroutineScope()
    var settleJob by remember(assetId) { mutableStateOf<Job?>(null) }
    var cropSettleJob by remember(assetId) { mutableStateOf<Job?>(null) }
    val reducedMotion = LocalReducedMotion.current
    // Fit and double tap animate; the pinch does not, because it is already following the finger.
    val animateTransform: (PhotoTransform) -> Unit = { target ->
        settleJob?.cancel()
        settleJob = gestureScope.launch {
            val start = transform
            if (reducedMotion) {
                transform = target
            } else {
                animate(0f, 1f, animationSpec = tween(ImagoMotion.Default)) { fraction, _ ->
                    transform = PhotoTransform(
                        zoom = start.zoom + (target.zoom - start.zoom) * fraction,
                        panX = start.panX + (target.panX - start.panX) * fraction,
                        panY = start.panY + (target.panY - start.panY) * fraction,
                    )
                }
            }
        }
    }
    val animateCropTransform: (PhotoTransform) -> Unit = { target ->
        cropSettleJob?.cancel()
        cropSettleJob = gestureScope.launch {
            val start = cropTransform
            if (reducedMotion) {
                cropTransform = target
            } else {
                animate(0f, 1f, animationSpec = tween(ImagoMotion.Default)) { fraction, _ ->
                    cropTransform = PhotoTransform(
                        zoom = start.zoom + (target.zoom - start.zoom) * fraction,
                        panX = start.panX + (target.panX - start.panX) * fraction,
                        panY = start.panY + (target.panY - start.panY) * fraction,
                    )
                }
            }
        }
    }
    // While cropping, the view always settles on the whole photo. Rotating swaps the axes and changes
    // the fit; entering the mode and resetting come back here. The crop itself is not a key — dragging
    // the photo also changes it, and reframing then would undo the choice just made.
    LaunchedEffect(
        cropMode,
        stageSize,
        reframeRequest,
        state.recipe?.geometry?.rotation,
    ) {
        if (!cropMode || stageSize == IntSize.Zero) return@LaunchedEffect
        val target = cropRestingTransform(
            stageWidth = stageSize.width,
            stageHeight = stageSize.height,
            imageWidth = fullImageSize.first,
            imageHeight = fullImageSize.second,
            quarterTurns = quarterTurns,
        )
        if (cropTransform.needsReframing(target)) animateCropTransform(target)
    }
    // Leaving crop cannot leave the viewer with a zoom-out it does not accept.
    LaunchedEffect(cropMode) {
        if (!cropMode) cropTransform = PhotoTransform()
    }
    val pagerState = rememberPagerState(initialPage = selectedIndex) { assets.size }
    val context = LocalPlatformContext.current
    val gestureHaptic = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val saveToDevice = rememberSaveToDevice(onSave = onExportGallery, onDenied = onExportPermissionDenied)
    LaunchedEffect(state.exportMessage, state.exportError) {
        val message = state.exportMessage ?: state.exportError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolveNow())
        onConsumeExportResult()
    }
    LaunchedEffect(state.recipeNotice) {
        val message = state.recipeNotice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolveNow())
        onConsumeRecipeNotice()
    }
    LaunchedEffect(selectedIndex, assets.size) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex.coerceIn(0, assets.lastIndex))
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        val settledPage = pagerState.settledPage
        if (settledPage != selectedIndex) {
            gestureHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onSelectIndex(settledPage)
        }
    }
    // The editor's best state is the one where almost only the photo is seen. With the tools in view,
    // or with a menu open, the chrome stays — disappearing under the finger would be worse. In a rail
    // this means the top bar never hides, and that is what we want: there it covers no photo, and
    // undo and compare are worth more at hand than hidden.
    LaunchedEffect(chromeInteraction, toolsVisible, showContextMenu, state.isSaving) {
        if (toolsVisible || showContextMenu || state.isSaving) {
            chromeVisible = true
            return@LaunchedEffect
        }
        chromeVisible = true
        delay(CHROME_IDLE_TIMEOUT_MS)
        chromeVisible = false
    }
    // The source of the recipe previews is the same bitmap as the preview, reduced once. Switching
    // photos throws away the previous ones, which no longer describe anything.
    LaunchedEffect(assetId, state.bitmap) {
        val bitmap = state.bitmap ?: return@LaunchedEffect
        RecipePreviewCache.setSource(assetId ?: return@LaunchedEffect, bitmap)
    }
    // Undo, redo and compare are what is done most often while developing a photo, and on a tablet
    // with a keyboard there is no reason to fetch them from the bar every time. Backslash is the compare
    // key by convention, and does here the same as a long press on the photo.
    val keyboardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyboardFocus.requestFocus() } }
    Scaffold(
        modifier = Modifier
            .focusRequester(keyboardFocus)
            .focusable()
            .onKeyEvent { event ->
                when {
                    // Compare: while the key is down the original shows, like the long press on the
                    // photo. Backslash is the convention; space is what the hand already has when
                    // developing with the mouse in the other.
                    event.type == KeyEventType.KeyUp && (event.key == Key.Backslash || event.key == Key.Spacebar) -> {
                        onShowOriginal(false); true
                    }
                    event.type != KeyEventType.KeyDown -> false
                    event.isCtrlPressed && event.key == Key.Z && event.isShiftPressed -> {
                        if (state.canRedo) onRedo(); true
                    }
                    event.isCtrlPressed && event.key == Key.Z -> {
                        if (state.canUndo) onUndo(); true
                    }
                    event.isCtrlPressed && event.key == Key.Y -> {
                        if (state.canRedo) onRedo(); true
                    }
                    event.key == Key.Backslash || event.key == Key.Spacebar -> { onShowOriginal(true); true }
                    event.isCtrlPressed && event.key == Key.C -> { onCopyRecipe(); true }
                    event.isCtrlPressed && event.key == Key.V -> {
                        if (state.canPasteRecipe) onPasteRecipe(); true
                    }
                    // The arrows fine-tune the last adjustment touched; with none at hand, they move
                    // between photos — which is what the finger does by swiping.
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> {
                        val forward = event.key == Key.DirectionRight
                        // Colour grading joins the list because blending and balance are sliders like
                        // the others; the wheels are not fine-tuned with the arrows.
                        val adjustment = lastAdjustment?.takeIf {
                            state.sheet == EditorSheet.ADJUSTMENTS ||
                                state.sheet == EditorSheet.MASK_ADJUSTMENTS ||
                                state.sheet == EditorSheet.COLOR_GRADING
                        }
                        if (adjustment != null) {
                            val span = adjustment.range.endInclusive - adjustment.range.start
                            val step = span / 200f * (if (event.isShiftPressed) 10f else 1f)
                            val value = state.valueOf(adjustment) + if (forward) step else -step
                            onAdjustment(adjustment, value.coerceIn(adjustment.range.start, adjustment.range.endInclusive))
                            onAdjustmentFinished()
                        } else if (assets.size > 1) {
                            val next = (selectedIndex + if (forward) 1 else -1).coerceIn(0, assets.lastIndex)
                            if (next != selectedIndex) onSelectIndex(next)
                        }
                        true
                    }
                    else -> false
                }
            },
        containerColor = ImagoColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The photo is the whole screen, under the system bars; it is the controls, and only they,
        // that step back to the safe areas.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                key = { page -> assets[page].id },
                beyondViewportPageCount = 1,
                // With a mask open, a horizontal drag is for its handles and not for switching
                // photos.
                userScrollEnabled = transform.isFit && !cropMode && !state.isMaskEditing,
                modifier = Modifier
                    .fillMaxSize()
                    // The rail does not sit over the photo, it sits beside it: the stage ends where the
                    // column starts, and so does the swipe between photos.
                    .padding(end = stageInsets.end)
                    .clipToBounds()
                    .background(Color.Black),
            ) { page ->
                val pageAsset = assets[page]
                val isActiveAsset = pageAsset.id == assetId
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    if (!isActiveAsset || state.bitmap == null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(rememberEditorImageData(pageAsset))
                                .libraryAuth(pageAsset.apiKey)
                                .crossfade(false)
                                .build(),
                            contentDescription = pageAsset.fileName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black),
                        )
                    }
                    if (isActiveAsset && state.bitmap != null) {
                        val renderParameters = if (cropMode) {
                            state.renderParameters.copy(cropX = 0f, cropY = 0f, cropWidth = 1f, cropHeight = 1f)
                        } else {
                            state.renderParameters
                        }
                        // The stage. The photo, the frame and the gestures are three `fillMaxSize`
                        // children of a single container: outside crop it fills the screen, as it always
                        // did; while cropping it steps back by what the chrome takes, once and in one
                        // place. As long as all three read the same box, they cannot disagree again about
                        // where the photo is — which is what two copied `padding` chains guaranteed.
                        Box(
                            modifier = (
                                if (cropMode) {
                                    Modifier
                                        .fillMaxSize()
                                        .padding(top = stageInsets.top, bottom = stageInsets.bottom)
                                } else {
                                    Modifier.fillMaxSize()
                                }
                                ).onSizeChanged { stageSize = it },
                        ) {
                            PhotoCanvas(
                                bitmap = state.bitmap,
                                parameters = renderParameters,
                                showOriginal = if (cropMode) false else state.showOriginal,
                                transform = if (cropMode) cropTransform else transform,
                                minZoom = if (cropMode) MIN_CROP_ZOOM else MIN_PHOTO_ZOOM,
                                // The channel comes from the already filtered list and not from
                                // `recipe.masks`: a mask that is off or has no adjustments does not
                                // reach the render, and the ones after it move up a channel.
                                maskOverlay = if (!cropMode && (state.maskOverlayPinned || maskFlashing)) {
                                    renderParameters.masks.indexOfFirst { it.id == state.selectedMaskId }
                                } else {
                                    -1
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            val guidesGeometry = maskFrameGeometry
                            if (guidingNow && !cropMode && guidesGeometry != null && stageSize != IntSize.Zero) {
                                UprightGuidesOverlay(
                                    guides = state.recipe?.geometry?.perspective?.guides.orEmpty(),
                                    bounds = photoBounds(
                                        surfaceWidth = stageSize.width,
                                        surfaceHeight = stageSize.height,
                                        imageWidth = imageSize.first,
                                        imageHeight = imageSize.second,
                                        transform = transform,
                                        quarterTurns = quarterTurns,
                                    ),
                                    geometry = guidesGeometry,
                                    photo = loupePhoto,
                                    finger = guideFinger,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (editingKey in PERSPECTIVE_ADJUSTMENTS && !cropMode && stageSize != IntSize.Zero) {
                                PerspectiveGrid(
                                    bounds = photoBounds(
                                        surfaceWidth = stageSize.width,
                                        surfaceHeight = stageSize.height,
                                        imageWidth = imageSize.first,
                                        imageHeight = imageSize.second,
                                        transform = transform,
                                        quarterTurns = quarterTurns,
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            val maskToDraw = selectedMaskNow?.takeIf { maskingNow && !cropMode }
                            val maskGeometry = maskFrameGeometry
                            if (maskToDraw != null && maskGeometry != null && stageSize != IntSize.Zero) {
                                MaskOverlay(
                                    mask = maskToDraw,
                                    bounds = photoBounds(
                                        surfaceWidth = stageSize.width,
                                        surfaceHeight = stageSize.height,
                                        imageWidth = imageSize.first,
                                        imageHeight = imageSize.second,
                                        transform = transform,
                                        quarterTurns = quarterTurns,
                                    ),
                                    geometry = maskGeometry,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            if (cropMode && state.recipe != null) {
                                CropOverlay(
                                    imageWidth = state.bitmap.pixelWidth,
                                    imageHeight = state.bitmap.pixelHeight,
                                    rotation = state.recipe.geometry.rotation,
                                    cropRect = state.recipe.geometry.cropRect,
                                    aspectLock = state.recipe.geometry.aspectLock,
                                    transform = cropTransform,
                                    gridDivisions = if (straightening) 9 else 3,
                                    onGestureStart = onCropGestureStart,
                                    onCropRectChange = onCropRectChange,
                                    onGestureFinished = onCropGestureFinished,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            // A single gesture block, not two. With the frame and the photo responding
                            // to the same finger, whoever decides whose gesture it is has to be the
                            // same `awaitFirstDown` — two sibling blocks would fight over the touch.
                            // Semantics is not composition: the texts are read before it.
                            val croppingDescription = stringResource(Res.string.editor_state_cropping)
                            val fitDescription = stringResource(Res.string.editor_state_fit)
                            val zoomDescription = stringResource(Res.string.editor_state_zoom, "%.1f".format(transform.zoom))
                            val zoomInAction = stringResource(Res.string.editor_action_zoom_in)
                            val fitAction = stringResource(Res.string.editor_action_fit)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .semantics {
                                        stateDescription = if (cropMode) {
                                            croppingDescription
                                        } else if (transform.isFit) {
                                            fitDescription
                                        } else {
                                            zoomDescription
                                        }
                                        // Pinch and double tap do not exist for TalkBack users.
                                        customActions = listOf(
                                            CustomAccessibilityAction(zoomInAction) {
                                                animateTransform(
                                                    PhotoTransform(zoom = DOUBLE_TAP_PHOTO_ZOOM),
                                                )
                                                true
                                            },
                                            CustomAccessibilityAction(fitAction) {
                                                animateTransform(PhotoTransform())
                                                true
                                            },
                                        )
                                    }
                                    // `transform` must NOT go into the keys: changing it relaunches the
                                    // block, and the new block's `awaitFirstDown` only resolves with a new
                                    // touch — fingers already down and dragging are never seen again. The
                                    // state is read from the snapshot, so it is always up to date. The
                                    // same goes for `cropMode` and the crop itself.
                                    .pointerInput(assetId) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            settleJob?.cancel()
                                            cropSettleJob?.cancel()

                                            val cropping = croppingNow
                                            val geometry = currentGeometry
                                            val minZoom =
                                                if (cropping) MIN_CROP_ZOOM else MIN_PHOTO_ZOOM
                                            val (imageWidth, imageHeight) =
                                                if (cropping) fullImageSize else imageSize
                                            var current = if (cropping) cropTransform else transform

                                            fun bounds(of: PhotoTransform) = photoBounds(
                                                surfaceWidth = size.width,
                                                surfaceHeight = size.height,
                                                imageWidth = imageWidth,
                                                imageHeight = imageHeight,
                                                transform = of,
                                                quarterTurns = quarterTurns,
                                                minZoom = minZoom,
                                            )

                                            // Only handles and edges move the frame. The inside became
                                            // photo like anywhere else — that is what makes "dragging
                                            // moves the image" hold everywhere.
                                            var handle: CropHandle? = null
                                            var workingCrop = geometry?.cropRect ?: CropRect()
                                            var frozenFrame: CropFrame? = null
                                            var pinching = false
                                            var moved = false

                                            if (cropping && geometry != null) {
                                                val frame = cropFrameOf(bounds(current), workingCrop)
                                                handle = hitCropHandle(
                                                    frame = frame,
                                                    position = down.position,
                                                    radius = cropHitRadius,
                                                )?.takeIf { it != CropHandle.MOVE }
                                                // Without a handle, the frame freezes: it stays still on
                                                // screen and it is the photo that moves underneath.
                                                if (handle == null) frozenFrame = frame
                                                // Opens the history entry for the whole gesture. A
                                                // touch that moves nothing leaves no mark —
                                                // `finishAdjustment` compares with the initial state.
                                                onCropGestureStart()
                                            }

                                            // The masks branch mirrors crop's, and for the same
                                            // reason: without a handle, the finger belongs to the
                                            // photo. What changes is the space — a mask handle lives
                                            // in image coordinates, not frame ones.
                                            val maskGeometry = maskFrameGeometry
                                            val activeMask = selectedMaskNow
                                                ?.takeIf { maskingNow && !cropping && maskGeometry != null }
                                            var maskHandle: MaskHandle? = null
                                            if (activeMask != null && maskGeometry != null) {
                                                val projection = MaskProjection(bounds(current), maskGeometry)
                                                maskHandle = maskHandlesOf(activeMask, projection)?.let {
                                                    hitMaskHandle(it, down.position, maskHitRadius)
                                                }
                                                if (maskHandle != null) {
                                                    maskTouchCount++
                                                    maskActions.onBeginGesture(activeMask.id, uiText(Res.string.editor_mask_adjusted))
                                                }
                                            }

                                            // The guided Upright's guides, the masks' way: in image
                                            // coordinates, an end under the finger is dragged, and
                                            // elsewhere — with room for another — the finger draws a
                                            // new guide from where it landed.
                                            var guideGrip: GuideGrip? = null
                                            var workingGuides = currentGuides
                                            var guiding = false
                                            if (guidingNow && !cropping && maskGeometry != null) {
                                                val projection = MaskProjection(bounds(current), maskGeometry)
                                                guideGrip = hitGuideEnd(workingGuides, projection, down.position, guideHitRadius)
                                                if (guideGrip == null && workingGuides.size < MAX_UPRIGHT_GUIDES) {
                                                    val start = projection.toImage(down.position)
                                                    if (start.x in 0f..1f && start.y in 0f..1f) {
                                                        workingGuides = workingGuides + UprightGuide(start.x, start.y, start.x, start.y)
                                                        guideGrip = GuideGrip(workingGuides.lastIndex, 2)
                                                    }
                                                }
                                                if (guideGrip != null) {
                                                    guiding = true
                                                    perspectiveActions.onGuideGestureStart()
                                                    perspectiveActions.onGuidesChange(workingGuides)
                                                    guideFinger = down.position
                                                }
                                            }

                                            // `fullImageSize` and not `state.bitmap` for the same
                                            // reason: when the block started the bitmap could still
                                            // be null, and the aspect ratio got stuck at 1:1.
                                            val (sourceWidth, sourceHeight) = fullImageSize
                                            val lockedRatio = lockedCropRatio(
                                                aspectLock = geometry?.aspectLock,
                                                imageWidth = sourceWidth,
                                                imageHeight = sourceHeight,
                                                rotation = geometry?.rotation ?: 0,
                                            )
                                            val imageRatio = orientedImageRatio(
                                                imageWidth = sourceWidth,
                                                imageHeight = sourceHeight,
                                                rotation = geometry?.rotation ?: 0,
                                            )

                                            do {
                                                val event = awaitPointerEvent()
                                                val pressed = event.changes.filter { it.pressed }
                                                if (pressed.size >= 2) {
                                                    // A second finger abandons the frame and becomes
                                                    // the pinch. From here there is no going back:
                                                    // returning the gesture to the frame when a finger
                                                    // lifts would jump where it was.
                                                    if (handle != null) {
                                                        handle = null
                                                        frozenFrame = cropFrameOf(bounds(current), workingCrop)
                                                    }
                                                    // The same abandonment for the mask: without it a
                                                    // radius would be left half pulled by the pinch.
                                                    maskHandle = null
                                                    guideGrip = null
                                                    guideFinger = null
                                                    pinching = true
                                                    val centroid = event.calculateCentroid(useCurrent = true)
                                                    if (centroid != Offset.Unspecified) {
                                                        val panChange = event.calculatePan()
                                                        current = current.pinch(
                                                            // This frame's ratio, of the order of 1.005 —
                                                            // it accumulates, never compared with a threshold.
                                                            zoomChange = event.calculateZoom(),
                                                            panChangeX = panChange.x,
                                                            panChangeY = panChange.y,
                                                            centroidX = centroid.x,
                                                            centroidY = centroid.y,
                                                            surfaceWidth = size.width,
                                                            surfaceHeight = size.height,
                                                            imageWidth = imageWidth,
                                                            imageHeight = imageHeight,
                                                            quarterTurns = quarterTurns,
                                                            minZoom = minZoom,
                                                        )
                                                        val frame = frozenFrame
                                                        if (cropping && frame != null) {
                                                            current = current.clampedToCropFrame(
                                                                stageWidth = size.width,
                                                                stageHeight = size.height,
                                                                imageWidth = imageWidth,
                                                                imageHeight = imageHeight,
                                                                quarterTurns = quarterTurns,
                                                                frame = frame,
                                                            )
                                                            cropTransform = current
                                                            workingCrop = cropRectOf(bounds(current), frame)
                                                            onCropRectChange(workingCrop)
                                                        } else {
                                                            transform = current
                                                        }
                                                        moved = true
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                } else if (maskHandle != null && !pinching &&
                                                    activeMask != null && maskGeometry != null
                                                ) {
                                                    val change = pressed.firstOrNull() ?: continue
                                                    val projection = MaskProjection(bounds(current), maskGeometry)
                                                    val now = projection.toImage(change.position)
                                                    val centre = activeMask.centreInImage()
                                                    val aspect = maskGeometry.imageAspect
                                                    when (maskHandle) {
                                                        MaskHandle.MOVE -> {
                                                            val before = projection.toImage(change.previousPosition)
                                                            maskActions.onMove(
                                                                activeMask.id,
                                                                now.x - before.x,
                                                                now.y - before.y,
                                                            )
                                                        }
                                                        MaskHandle.ROTATE -> if (centre != null) {
                                                            maskActions.onRotate(
                                                                activeMask.id,
                                                                maskAngleFrom(
                                                                    centreX = centre.x,
                                                                    centreY = centre.y,
                                                                    pointX = now.x,
                                                                    pointY = now.y,
                                                                    aspect = aspect,
                                                                    handleOffset = activeMask.rotateHandleOffset(),
                                                                ),
                                                            )
                                                        }
                                                        // Absolute and not incremental: that way the
                                                        // handle stays under the finger instead of drifting.
                                                        else -> if (centre != null) {
                                                            val local = maskLocalOffset(
                                                                centreX = centre.x,
                                                                centreY = centre.y,
                                                                pointX = now.x,
                                                                pointY = now.y,
                                                                angleDegrees = activeMask.angleInImage(),
                                                                aspect = aspect,
                                                            )
                                                            when (maskHandle) {
                                                                MaskHandle.RADIUS_X ->
                                                                    maskActions.onSetRadius(activeMask.id, abs(local.x), null)
                                                                MaskHandle.RADIUS_Y ->
                                                                    maskActions.onSetRadius(activeMask.id, null, abs(local.y))
                                                                else ->
                                                                    maskActions.onSetWidth(activeMask.id, abs(local.y) * 2f)
                                                            }
                                                        }
                                                    }
                                                    moved = true
                                                    change.consume()
                                                } else if (guideGrip != null && !pinching && maskGeometry != null) {
                                                    val change = pressed.firstOrNull() ?: continue
                                                    val grip = guideGrip
                                                    val point = MaskProjection(bounds(current), maskGeometry).toImage(change.position)
                                                    workingGuides = workingGuides.mapIndexed { index, guide ->
                                                        if (index != grip.index) {
                                                            guide
                                                        } else {
                                                            guide.withEnd(grip.end, point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
                                                        }
                                                    }
                                                    perspectiveActions.onGuidesChange(workingGuides)
                                                    guideFinger = change.position
                                                    change.consume()
                                                } else if (handle != null && !pinching) {
                                                    val change = pressed.firstOrNull() ?: continue
                                                    val delta = change.position - change.previousPosition
                                                    // The delta is normalised by the photo's REAL
                                                    // rectangle, not by the fit: with zoom, a finger
                                                    // pixel no longer meant the same in crop.
                                                    val drawn = bounds(current)
                                                    workingCrop = workingCrop.dragged(
                                                        handle = handle,
                                                        deltaX = delta.x / drawn.width.coerceAtLeast(1f),
                                                        deltaY = delta.y / drawn.height.coerceAtLeast(1f),
                                                        lockedPixelRatio = lockedRatio,
                                                        imagePixelRatio = imageRatio,
                                                    )
                                                    onCropRectChange(workingCrop)
                                                    moved = true
                                                    change.consume()
                                                } else if (pressed.size == 1 && !pinching &&
                                                    (cropping || !current.isFit)
                                                ) {
                                                    val change = pressed.first()
                                                    val delta = change.position - change.previousPosition
                                                    current = current.drag(
                                                        deltaX = delta.x,
                                                        deltaY = delta.y,
                                                        surfaceWidth = size.width,
                                                        surfaceHeight = size.height,
                                                        imageWidth = imageWidth,
                                                        imageHeight = imageHeight,
                                                        quarterTurns = quarterTurns,
                                                        minZoom = minZoom,
                                                    )
                                                    val frame = frozenFrame
                                                    if (cropping && frame != null) {
                                                        current = current.clampedToCropFrame(
                                                            stageWidth = size.width,
                                                            stageHeight = size.height,
                                                            imageWidth = imageWidth,
                                                            imageHeight = imageHeight,
                                                            quarterTurns = quarterTurns,
                                                            frame = frame,
                                                        )
                                                        cropTransform = current
                                                        // The frame stayed still on screen; what
                                                        // changed was the image under it, and that is
                                                        // where the new crop comes from.
                                                        workingCrop = cropRectOf(bounds(current), frame)
                                                        onCropRectChange(workingCrop)
                                                    } else {
                                                        transform = current
                                                    }
                                                    moved = true
                                                    change.consume()
                                                }
                                            } while (event.changes.any { it.pressed })

                                            if (cropping && geometry != null) onCropGestureFinished()
                                            if (maskHandle != null) onAdjustmentFinished()
                                            if (guiding) {
                                                guideFinger = null
                                                perspectiveActions.onGuideGestureEnd()
                                            }
                                            if (moved) {
                                                val settled = current.settle(
                                                    surfaceWidth = size.width,
                                                    surfaceHeight = size.height,
                                                    imageWidth = imageWidth,
                                                    imageHeight = imageHeight,
                                                    quarterTurns = quarterTurns,
                                                    minZoom = minZoom,
                                                )
                                                if (cropping) {
                                                    if (settled != current) cropTransform = settled
                                                    // Releasing a gesture does not reframe, whether it
                                                    // comes from the frame or the photo: if someone
                                                    // zoomed in to hit an edge, throwing them back to
                                                    // the whole view would undo their work. Whoever
                                                    // wants the whole photo again has the double tap
                                                    // and the reset button.
                                                } else if (settled != current) {
                                                    animateTransform(settled)
                                                }
                                            }
                                        }
                                    }
                                    // The mouse wheel does the zoom the pinch does, with the same maths
                                    // and around the cursor. The phone has no wheel; on a mouse or
                                    // touchpad it is the obvious gesture.
                                    .pointerInput(assetId) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                if (event.type != PointerEventType.Scroll) continue
                                                val change = event.changes.firstOrNull() ?: continue
                                                val delta = change.scrollDelta.y
                                                if (delta == 0f) continue
                                                change.consume()
                                                settleJob?.cancel()
                                                cropSettleJob?.cancel()
                                                val cropping = croppingNow
                                                val (imageWidth, imageHeight) = if (cropping) fullImageSize else imageSize
                                                val minZoom = if (cropping) MIN_CROP_ZOOM else MIN_PHOTO_ZOOM
                                                val current = if (cropping) cropTransform else transform
                                                val zoomed = current.pinch(
                                                    // Wheel up zooms in; one step is an eighth, which
                                                    // gives a smooth approach without jumps.
                                                    zoomChange = exp(-delta * 0.125f),
                                                    panChangeX = 0f,
                                                    panChangeY = 0f,
                                                    centroidX = change.position.x,
                                                    centroidY = change.position.y,
                                                    surfaceWidth = size.width,
                                                    surfaceHeight = size.height,
                                                    imageWidth = imageWidth,
                                                    imageHeight = imageHeight,
                                                    quarterTurns = quarterTurns,
                                                    elastic = false,
                                                    minZoom = minZoom,
                                                )
                                                if (cropping) cropTransform = zoomed else transform = zoomed
                                            }
                                        }
                                    }
                                    .pointerInput(assetId) {
                                        detectTapGestures(
                                            onTap = {
                                                // While cropping, a tap cannot close the panel: it is
                                                // the mode's only way out. In a rail neither, but for
                                                // another reason: there is no drawer to open.
                                                if (!croppingNow && !railNow) {
                                                    panelVisible = !panelVisible
                                                }
                                                // Outside the condition above because the top bar
                                                // hides on its own in both modes, and it is this tap
                                                // that brings it back.
                                                if (!croppingNow) chromeInteraction++
                                            },
                                            onDoubleTap = { position ->
                                                if (croppingNow) {
                                                    reframeRequest++
                                                } else {
                                                    val (imageWidth, imageHeight) = imageSize
                                                    animateTransform(
                                                        transform.toggleDoubleTap(
                                                            tapX = position.x,
                                                            tapY = position.y,
                                                            surfaceWidth = size.width,
                                                            surfaceHeight = size.height,
                                                            imageWidth = imageWidth,
                                                            imageHeight = imageHeight,
                                                            quarterTurns = quarterTurns,
                                                        ),
                                                    )
                                                }
                                                gestureHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onLongPress = {
                                                if (croppingNow) return@detectTapGestures
                                                gestureHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onShowOriginal(true)
                                            },
                                            onPress = {
                                                if (croppingNow) return@detectTapGestures
                                                tryAwaitRelease()
                                                onShowOriginal(false)
                                            },
                                        )
                                    },
                            )
                        }
                    }
                    if (isActiveAsset && state.isLoading) {
                        CircularProgressIndicator(color = ImagoColors.Ivory)
                    }
                    if (isActiveAsset && state.error != null) {
                        // An error must never be left without a way out. The recipe is still saved
                        // locally, so all that is left is fetching the preview again.
                        Surface(
                            color = ImagoColors.SurfaceElevated,
                            shape = RoundedCornerShape(ImagoRadii.Medium),
                            modifier = Modifier.padding(ImagoSpacing.Xxl),
                        ) {
                            Column(
                                modifier = Modifier.padding(ImagoSpacing.Lg),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
                            ) {
                                Text(text = state.error.resolve(), color = ImagoColors.TextSecondary)
                                TextButton(onClick = onRetry) { Text(stringResource(Res.string.editor_try_again)) }
                            }
                        }
                    }
                }
            }

            // The panel and the toolbar are a single piece stacked at the bottom. Aligning them
            // separately put the bar over the panel's bottom.
            AnimatedVisibility(
                // In a rail it is always in view. Hiding it was what gave the whole photo back when the
                // panel was over it; beside it, it covers nothing, and closing it would only leave an
                // empty column.
                visible = toolsVisible,
                modifier = Modifier
                    .align(if (rail) Alignment.TopEnd else Alignment.BottomCenter)
                    .then(
                        if (rail) {
                            Modifier.width(railWidth).fillMaxHeight().statusBarsPadding()
                        } else {
                            Modifier
                        },
                    )
                    // The curve floats over the photo and so steps back from the edges — but only in the
                    // drawer. In the rail the curve is one more sheet of the column, and stepping back
                    // here would only steal its width.
                    .padding(
                        start = if (!rail && state.sheet == EditorSheet.CURVE) ImagoSpacing.Md else 0.dp,
                        end = if (!rail && state.sheet == EditorSheet.CURVE) ImagoSpacing.Md else 0.dp,
                        bottom = if (!rail && state.sheet == EditorSheet.CURVE) ImagoSpacing.Xxxl else 0.dp,
                    ),
                enter = fadeIn(imagoTween(ImagoMotion.Default)) +
                    slideInVertically(imagoTween(ImagoMotion.Default)) { it / 3 },
                exit = fadeOut(imagoTween(ImagoMotion.Fast)) +
                    slideOutVertically(imagoTween(ImagoMotion.Fast)) { it / 3 },
            ) {
                // `navigationBarsPadding` moved down from `AnimatedVisibility` to here, and it is the
                // `Column` that is measured — not the container. `slideInVertically` moves the child
                // inside a container that clips it during the transition: measuring from outside would
                // give a height growing frame by frame, and the stage trembling with it.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .then(if (rail) Modifier.fillMaxSize() else Modifier)
                        // The order is not indifferent: `onSizeChanged` reports the size of what is
                        // below it in the chain. With the padding outside, the navigation bar was left
                        // out of the count and the stage stepped back less than it should.
                        .onSizeChanged { measured ->
                            // In a rail there is nothing to measure: the column is as tall as the screen
                            // and what the stage needs to know is its width, which is already known.
                            if (rail) return@onSizeChanged
                            val height = with(chromeDensity) { measured.height.toDp() }
                            if (height > 0.dp && height != chromeInsets.bottom) {
                                chromeInsets = chromeInsets.copy(bottom = height)
                            }
                        },
                ) {
                    // They float over the photo, but live inside the column being measured — that is
                    // how they look like they are on top and still count for the stage's step back.
                    // Seeing them cover the image was the defect being fixed.
                    if (cropMode && state.recipe != null) {
                        CropActionRow(
                            rotation = state.recipe.geometry.rotation,
                            mirrorH = state.recipe.geometry.mirrorH,
                            mirrorV = state.recipe.geometry.mirrorV,
                            canReset = state.recipe.geometry != Geometry(),
                            onRotateClockwise = onRotateClockwise,
                            onToggleMirrorHorizontal = onToggleMirrorHorizontal,
                            onToggleMirrorVertical = onToggleMirrorVertical,
                            onReset = {
                                onResetGeometry()
                                reframeRequest++
                            },
                            modifier = Modifier.padding(bottom = ImagoSpacing.Sm),
                        )
                        StraightenRuler(
                            degrees = state.renderParameters.straighten,
                            onDegrees = onStraighten,
                            onDragStart = { straightening = true },
                            onDragEnd = {
                                straightening = false
                                onAdjustmentFinished()
                            },
                            onReset = onResetStraighten,
                            modifier = Modifier.padding(bottom = ImagoSpacing.Sm),
                        )
                    }
                    AdjustmentPanel(
                        state = state,
                        // In the drawer it is a slice of the screen; in the rail it is all that is left
                        // between the top tools and the bottom bar.
                        modifier = if (rail) {
                            Modifier.fillMaxWidth().weight(1f)
                        } else {
                            Modifier.fillMaxWidth().height(panelHeight)
                        },
                        rail = rail,
                        editingKey = editingKey,
                        onEditing = { key ->
                            editingKey = key
                            (key as? Adjustment)?.let { lastAdjustment = it }
                        },
                        onSelectPanel = onSelectPanel,
                        onSelectSheet = onSelectSheet,
                        onRestoreHistory = onRestoreHistory,
                        onClearHistory = onClearHistory,
                        onApplyConflict = onApplyConflict,
                        onAdjustment = onAdjustment,
                        onAdjustmentFinished = onAdjustmentFinished,
                        onResetAdjustment = onResetAdjustment,
                        onSelectHslBand = onSelectHslBand,
                        onAddCurvePoint = onAddCurvePoint,
                        onMoveCurvePoint = onMoveCurvePoint,
                        onRemoveCurvePoint = onRemoveCurvePoint,
                        onResetCurve = onResetCurve,
                        onHsl = onHsl,
                        onResetHsl = onResetHsl,
                        onSelectCropAspect = onSelectCropAspect,
                        onResetCrop = onResetCrop,
                        onStraighten = onStraighten,
                        onResetStraighten = onResetStraighten,
                        onRotateClockwise = onRotateClockwise,
                        onToggleMirrorHorizontal = onToggleMirrorHorizontal,
                        onToggleMirrorVertical = onToggleMirrorVertical,
                        onResetGeometry = onResetGeometry,
                        maskActions = maskActions,
                        colorGradeActions = colorGradeActions,
                        perspectiveActions = perspectiveActions,
                    )
                    // The curve floats over the photo; there the bar would get in the way — and as it
                    // is the bar that now brings the system bar's inset, without it a spacer stands in
                    // for it.
                    if (state.sheet == EditorSheet.CURVE) {
                        Spacer(Modifier.navigationBarsPadding())
                    } else {
                        EditorToolBar(
                            selected = when (state.sheet) {
                                EditorSheet.CROP, EditorSheet.PERSPECTIVE -> EditorTool.CROP
                                EditorSheet.HISTORY -> EditorTool.HISTORY
                                else -> EditorTool.ADJUSTMENTS
                            },
                            isComparing = state.showOriginal,
                            canPasteRecipe = state.canPasteRecipe && state.recipe != null,
                            canExport = !compositionMode && state.bitmap != null && !state.isExporting,
                            tools = editorTools(recipeMode),
                            // A recipe that is not the user's yet can always be saved — that is how it
                            // becomes theirs; one that is only saves when something changed.
                            canSaveEdits = state.recipe != null && !state.needsNewerApp &&
                                (state.hasUnsavedRecipeChanges || state.recipeEdit?.saved == null),
                            onSelect = { tool ->
                                when (tool) {
                                    EditorTool.ADJUSTMENTS -> onSelectSheet(EditorSheet.ADJUSTMENTS)
                                    EditorTool.CROP -> onSelectSheet(EditorSheet.CROP)
                                    EditorTool.HISTORY -> onSelectSheet(EditorSheet.HISTORY)
                                    EditorTool.RECIPES -> showRecipeLibrary = true
                                    EditorTool.COMPARE -> onShowOriginal(!state.showOriginal)
                                    EditorTool.EXPORT -> if (!compositionMode) showExportDialog = true
                                    EditorTool.COPY_RECIPE -> onCopyRecipe()
                                    EditorTool.PASTE_RECIPE -> onPasteRecipe()
                                    EditorTool.SAVE_RECIPE -> showSaveRecipeDialog = true
                                    EditorTool.SAVE_EDITS -> recipeActions?.onSave?.invoke()
                                }
                            },
                            modifier = Modifier.alpha(if (rail || editingKey == null) 1f else 0f),
                        )
                    }
                }
            }

            // With a value being dragged, the panel left — only the parameter, the value and the ruler
            // stay, over the whole photo.
            val focused = editingKey as? Adjustment
            AnimatedVisibility(
                visible = focused != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = ImagoSpacing.Xxxl, end = stageInsets.end),
                enter = fadeIn(imagoTween(ImagoMotion.Instant)),
                exit = fadeOut(imagoTween(ImagoMotion.Instant)),
            ) {
                focused?.let { adjustment ->
                    val value = state.valueOf(adjustment)
                    FocusedAdjustmentScale(
                        label = adjustment.label(),
                        valueText = formatAdjustmentValue(adjustment, value),
                        value = value,
                        range = adjustment.range,
                        tint = if (adjustment == Adjustment.TEMPERATURE || adjustment == Adjustment.EXPOSURE) {
                            ImagoScaleTint.TEMPERATURE
                        } else {
                            ImagoScaleTint.NEUTRAL
                        },
                        formatBound = { formatAdjustmentValue(adjustment, it) },
                    )
                }
            }

            AnimatedVisibility(
                visible = chromeVisible,
                // The bar belongs to the photo, not to the screen. Full width it crossed the rail and put
                // its actions — compare, undo, redo, menu — exactly over the row of categories, which
                // could no longer be tapped. It ends where the photo ends.
                modifier = Modifier.align(Alignment.TopCenter).padding(end = stageInsets.end),
                enter = fadeIn(imagoTween(ImagoMotion.Fast)),
                exit = fadeOut(imagoTween(ImagoMotion.Fast)),
            ) {
            // The bar already applies `WindowInsets.statusBars` inside, so the measured height
            // includes the status bar — and that is why the stage stopped taking
            // `statusBarsPadding()`, which counted it a second time.
            TopAppBar(
                modifier = Modifier.onSizeChanged { measured ->
                    val height = with(chromeDensity) { measured.height.toDp() }
                    if (height > 0.dp && height != chromeInsets.top) {
                        chromeInsets = chromeInsets.copy(top = height)
                    }
                },
                windowInsets = WindowInsets.statusBars,
                title = {
                    if (recipeMode) {
                        RecipeEditTitle(state)
                    } else {
                        AutoSaveStatus(
                            isSaving = state.isSaving,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = when {
                                compositionMode -> stringResource(Res.string.editor_cancel_edit)
                                recipeMode -> stringResource(Res.string.editor_back_to_recipes)
                                else -> stringResource(Res.string.editor_back_to_library)
                            },
                        )
                    }
                },
                actions = {
                    if (compositionMode) {
                        TextButton(onClick = onConfirm, enabled = state.recipe != null) { Text(stringResource(Res.string.editor_apply)) }
                    }
                    // The long press shows the original, but a hidden gesture cannot be the only way
                    // to compare: this button does exactly the same.
                    IconButton(
                        onClick = { onShowOriginal(!state.showOriginal) },
                        enabled = state.bitmap != null,
                    ) {
                        Icon(
                            imageVector = if (state.showOriginal) {
                                Icons.Outlined.Visibility
                            } else {
                                Icons.Outlined.VisibilityOff
                            },
                            contentDescription = if (state.showOriginal) {
                                stringResource(Res.string.editor_show_edited)
                            } else {
                                stringResource(Res.string.editor_show_original)
                            },
                        )
                    }
                    IconButton(onClick = onUndo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = stringResource(Res.string.editor_undo))
                    }
                    IconButton(onClick = onRedo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = stringResource(Res.string.editor_redo))
                    }
                    Box {
                        IconButton(onClick = { showContextMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(Res.string.editor_more_options))
                        }
                        DropdownMenu(
                            expanded = showContextMenu,
                            onDismissRequest = { showContextMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_copy_recipe)) },
                                enabled = state.recipe != null,
                                onClick = {
                                    showContextMenu = false
                                    onCopyRecipe()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_paste_recipe)) },
                                enabled = state.canPasteRecipe && state.recipe != null,
                                onClick = {
                                    showContextMenu = false
                                    onPasteRecipe()
                                },
                            )
                            // Editing a recipe, saving it "to recipes" is what Save and Duplicate already do.
                            if (!recipeMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.editor_save_to_recipes)) },
                                    enabled = state.recipe != null,
                                    onClick = {
                                        showContextMenu = false
                                        showSaveRecipeDialog = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.editor_recipe_library)) },
                                onClick = {
                                    showContextMenu = false
                                    showRecipeLibrary = true
                                },
                            )
                            recipeActions?.let { actions ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.editor_duplicate)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    enabled = state.recipe != null && !state.needsNewerApp,
                                    onClick = {
                                        showContextMenu = false
                                        actions.onDuplicate()
                                    },
                                )
                                // A filter is not the user's to delete, and a new recipe has nothing yet.
                                if (state.recipeEdit?.saved != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.editor_delete), color = ImagoColors.Danger) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = ImagoColors.Danger)
                                        },
                                        onClick = {
                                            showContextMenu = false
                                            actions.onDelete()
                                        },
                                    )
                                }
                            }
                            if (!compositionMode && !recipeMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.editor_tool_export)) },
                                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                    enabled = state.bitmap != null && !state.isExporting,
                                    onClick = {
                                        showContextMenu = false
                                        showExportDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = ImagoColors.TextPrimary,
                    navigationIconContentColor = ImagoColors.TextPrimary,
                    actionIconContentColor = ImagoColors.TextPrimary,
                ),
            )
            }

            // Comparison has to announce itself, or it is mistaken for the photo reloading.
            AnimatedVisibility(
                visible = state.showOriginal,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    // Centred over the photo and not over the screen: with the rail mounted, the middle
                    // of the two is not the same place.
                    .padding(top = 72.dp, end = stageInsets.end),
                enter = fadeIn(imagoTween(ImagoMotion.Instant)),
                exit = fadeOut(imagoTween(ImagoMotion.Instant)),
            ) {
                Text(
                    text = stringResource(Res.string.editor_original),
                    style = MaterialTheme.typography.labelLarge,
                    color = ImagoColors.Ivory,
                    modifier = Modifier
                        .clip(RoundedCornerShape(ImagoRadii.Pill))
                        .background(Color.Black.copy(alpha = 0.56f))
                        .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Xs),
                )
            }

            if (!transform.isFit) {
                Text(
                    text = "%.1f×".format(transform.zoom),
                    style = MaterialTheme.typography.labelSmall,
                    color = ImagoColors.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // It sits on the measured chrome, and not on the panel's height: that one does
                        // not count the toolbar, and the badge was hidden behind it.
                        // `chromeInsets.bottom` already includes the navigation bar.
                        .padding(
                            bottom = if (panelVisible && !rail) {
                                stageInsets.bottom + ImagoSpacing.Md
                            } else {
                                ImagoSpacing.Xl
                            },
                            end = stageInsets.end,
                        )
                        .clip(RoundedCornerShape(ImagoRadii.Pill))
                        .background(Color.Black.copy(alpha = 0.56f))
                        .padding(horizontal = ImagoSpacing.Sm, vertical = ImagoSpacing.Xs),
                )
            }
        }
    }
    if (showSaveRecipeDialog) {
        RecipeDetailsDialog(
            title = stringResource(Res.string.editor_save_recipe),
            initialName = "",
            initialCollection = stringResource(Res.string.editor_default_collection),
            confirmLabel = stringResource(Res.string.editor_tool_save),
            onDismiss = { showSaveRecipeDialog = false },
            onConfirm = { name, collection ->
                onSaveRecipe(name, collection)
                showSaveRecipeDialog = false
            },
        )
    }
    editingSavedRecipe?.let { saved ->
        RecipeDetailsDialog(
            title = stringResource(Res.string.editor_organize_recipe),
            initialName = saved.name,
            initialCollection = saved.collection,
            confirmLabel = stringResource(Res.string.editor_save_changes),
            onDismiss = {
                editingSavedRecipe = null
                showRecipeLibrary = true
            },
            onConfirm = { name, collection ->
                onUpdateSavedRecipe(saved.id, name, collection)
                editingSavedRecipe = null
                showRecipeLibrary = true
            },
        )
    }
    if (showRecipeLibrary) {
        // With the photo open, each recipe shows already applied to it — the difference between
        // choosing by name and choosing by result.
        RecipeLibraryScreen(
            saved = state.savedRecipes.toCards(),
            builtIn = builtInCards(),
            isLoading = state.isRecipeLibraryLoading,
            assetId = state.asset?.id,
            onBack = { showRecipeLibrary = false },
            onSelect = { card ->
                if (card.isBuiltIn) onApplyBuiltInRecipe(card.id) else onApplySavedRecipe(card.id)
                showRecipeLibrary = false
            },
            onToggleFavorite = { onToggleRecipeFavorite(it.id) },
            onRename = { card ->
                showRecipeLibrary = false
                editingSavedRecipe = state.savedRecipes.firstOrNull { it.id == card.id }
            },
            onDelete = { card ->
                showRecipeLibrary = false
                deletingSavedRecipe = state.savedRecipes.firstOrNull { it.id == card.id }
            },
            onCreate = {
                showRecipeLibrary = false
                showSaveRecipeDialog = true
            },
        )
    }
    deletingSavedRecipe?.let { saved ->
        AlertDialog(
            onDismissRequest = {
                deletingSavedRecipe = null
                showRecipeLibrary = true
            },
            title = { Text(stringResource(Res.string.editor_delete_recipe_question)) },
            text = { Text(stringResource(Res.string.editor_recipe_delete_body, saved.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSavedRecipe(saved.id)
                        deletingSavedRecipe = null
                        showRecipeLibrary = true
                    },
                ) { Text(stringResource(Res.string.editor_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletingSavedRecipe = null
                        showRecipeLibrary = true
                    },
                ) { Text(stringResource(Res.string.editor_cancel)) }
            },
        )
    }
    if (!compositionMode && showExportDialog) {
        var exportDestination by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!state.isExporting) showExportDialog = false },
            title = { Text(stringResource(Res.string.editor_export_photo)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.editor_export_body))
                    eu.studio742.imago.feature.library.ExportLibrarySelector(
                        originId = state.asset?.id?.let { eu.studio742.imago.core.model.AssetReference.parse(it).libraryId },
                        onSelected = { exportDestination = it },
                    )
                    Button(
                        onClick = {
                            showExportDialog = false
                            saveToDevice()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(SaveToDeviceLabel)) }
                    Button(
                        onClick = {
                            showExportDialog = false
                            exportDestination?.let(onExportImmich)
                        },
                        enabled = exportDestination != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(Res.string.editor_send_to_immich)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) { Text(stringResource(Res.string.editor_cancel)) }
            },
        )
    }
    if (state.isExporting) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    state.exportPhase?.resolve() ?: stringResource(Res.string.editor_exporting),
                    color = Color.White,
                    modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

/** The recipe editor's title: which recipe, and whether what is on screen is saved. */
@Composable
private fun RecipeEditTitle(state: EditorUiState) {
    val edit = state.recipeEdit ?: return
    Column {
        Text(
            text = edit.saved?.name ?: edit.suggestedName?.resolve() ?: stringResource(Res.string.editor_new_recipe),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.hasUnsavedRecipeChanges) {
            Text(
                text = stringResource(Res.string.editor_unsaved_marker),
                style = MaterialTheme.typography.labelSmall,
                color = ImagoColors.TextSecondary,
            )
        }
    }
}

@Composable
internal fun RecipeDetailsDialog(
    title: String,
    initialName: String,
    initialCollection: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var collection by remember(initialCollection) { mutableStateOf(initialCollection) }
    val defaultCollection = stringResource(Res.string.editor_default_collection)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.editor_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = collection,
                    onValueChange = { collection = it },
                    label = { Text(stringResource(Res.string.editor_collection)) },
                    supportingText = { Text(stringResource(Res.string.editor_collection_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), collection.trim().ifBlank { defaultCollection }) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.editor_cancel)) } },
    )
}

@Composable
private fun AdjustmentPanel(
    state: EditorUiState,
    modifier: Modifier,
    rail: Boolean,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    onSelectPanel: (EditorPanel) -> Unit,
    onSelectSheet: (EditorSheet) -> Unit,
    onRestoreHistory: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onApplyConflict: (Long) -> Unit,
    onAdjustment: (Adjustment, Float) -> Unit,
    onAdjustmentFinished: () -> Unit,
    onResetAdjustment: (Adjustment) -> Unit,
    onSelectHslBand: (HslColorBand) -> Unit,
    onAddCurvePoint: (Int, Int) -> Unit,
    onMoveCurvePoint: (Int, Int, Int) -> Unit,
    onRemoveCurvePoint: (Int) -> Unit,
    onResetCurve: () -> Unit,
    onHsl: (HslColorBand, HslComponent, Float) -> Unit,
    onResetHsl: (HslColorBand, HslComponent) -> Unit,
    onSelectCropAspect: (String?) -> Unit,
    onResetCrop: () -> Unit,
    onStraighten: (Float) -> Unit,
    onResetStraighten: () -> Unit,
    onRotateClockwise: () -> Unit,
    onToggleMirrorHorizontal: () -> Unit,
    onToggleMirrorVertical: () -> Unit,
    onResetGeometry: () -> Unit,
    maskActions: MaskActions,
    colorGradeActions: ColorGradeActions,
    perspectiveActions: PerspectiveActions,
) {
    // Dragging a value fades the panel to let the photo show underneath. In a rail there is nothing
    // underneath — the photo is beside it, always in view — and fading it would make the column the
    // finger rests on disappear under it.
    val isEditing = editingKey != null && !rail
    val fade = imagoTween<Float>(ImagoMotion.Instant)
    val chromeAlpha by animateFloatAsState(
        targetValue = if (isEditing) 0f else 1f,
        animationSpec = fade,
        label = "chrome",
    )
    val panelColor by animateColorAsState(
        targetValue = when {
            isEditing -> Color.Transparent
            state.sheet == EditorSheet.CURVE -> Color.Black.copy(alpha = 0.42f)
            else -> ImagoColors.Glass
        },
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "panelColor",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isEditing) Color.Transparent else ImagoColors.BorderSubtle,
        animationSpec = imagoTween(ImagoMotion.Instant),
        label = "panelBorder",
    )
    val panelShape = when {
        // In the rail the panel is not a sheet rising from the bottom but a column against the edge:
        // rounding the corners left background gaps against the screen's edges. The 1dp outline
        // stays, and its inner edge is what separates the photo from the tools.
        rail -> RectangleShape
        state.sheet == EditorSheet.CURVE -> RoundedCornerShape(ImagoRadii.Panel)
        else -> RoundedCornerShape(topStart = ImagoRadii.Panel, topEnd = ImagoRadii.Panel)
    }
    Box(modifier) {
        // The blur is on a layer underneath: applying it to the Surface would blur the sliders too.
        // In the rail there is nothing to blur — the photo ends before the column starts — and a blur
        // over a plain background is just GPU work with nothing to show.
        if (!isEditing && !rail) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(panelShape)
                    .imagoGlassBlur()
                    .background(Color.Transparent),
            )
        }
    Surface(
        color = panelColor,
        shape = panelShape,
        modifier = Modifier
            .fillMaxSize()
            .border(width = 1.dp, color = borderColor, shape = panelShape),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // The handle and the categories are panel chrome: while a value is dragged they leave the
            // scene with the rest, so the screen keeps only the photo and the active control.
            if (state.sheet != EditorSheet.CURVE) {
                SheetHandle(Modifier.fillMaxWidth().alpha(chromeAlpha))
            }
            when (state.sheet) {
                EditorSheet.ADJUSTMENTS -> {
                    AdjustmentPanelHeader(
                        panel = state.panel,
                        onSelectPanel = onSelectPanel,
                        onSelectSheet = onSelectSheet,
                        modifier = Modifier.fillMaxWidth().alpha(chromeAlpha),
                    )
                    SliderPanel(
                        state = state,
                        groups = state.panel.adjustmentGroups(),
                        editingKey = editingKey,
                        onEditing = onEditing,
                        onAdjustment = onAdjustment,
                        onAdjustmentFinished = onAdjustmentFinished,
                        onResetAdjustment = onResetAdjustment,
                    )
                }
                EditorSheet.CURVE -> CurvePanel(
                    state = state,
                    editingKey = editingKey,
                    onEditing = onEditing,
                    onAddPoint = onAddCurvePoint,
                    onMovePoint = onMoveCurvePoint,
                    onRemovePoint = onRemoveCurvePoint,
                    onGestureFinished = onAdjustmentFinished,
                    onReset = onResetCurve,
                    onClose = { onSelectSheet(EditorSheet.ADJUSTMENTS) },
                )
                EditorSheet.HSL -> HslPanel(
                    state = state,
                    editingKey = editingKey,
                    onEditing = onEditing,
                    onSelectBand = onSelectHslBand,
                    onHsl = onHsl,
                    onAdjustmentFinished = onAdjustmentFinished,
                    onResetHsl = onResetHsl,
                    onClose = { onSelectSheet(EditorSheet.ADJUSTMENTS) },
                )
                EditorSheet.COLOR_GRADING -> ColorGradingPanel(
                    state = state,
                    editingKey = editingKey,
                    onEditing = onEditing,
                    onAdjustment = onAdjustment,
                    onResetAdjustment = onResetAdjustment,
                    actions = colorGradeActions,
                    onClose = { onSelectSheet(EditorSheet.ADJUSTMENTS) },
                )
                EditorSheet.CROP -> CropAspectSheet(
                    state = state,
                    onSelectCropAspect = onSelectCropAspect,
                    onOpenPerspective = { onSelectSheet(EditorSheet.PERSPECTIVE) },
                )
                EditorSheet.PERSPECTIVE -> PerspectivePanel(
                    state = state,
                    editingKey = editingKey,
                    onEditing = onEditing,
                    onAdjustment = onAdjustment,
                    onAdjustmentFinished = onAdjustmentFinished,
                    onResetAdjustment = onResetAdjustment,
                    actions = perspectiveActions,
                    onClose = { onSelectSheet(EditorSheet.CROP) },
                )
                EditorSheet.HISTORY -> HistoryPanel(
                    entries = state.history,
                    currentIndex = state.historyIndex,
                    onRestore = onRestoreHistory,
                    onClearAll = onClearHistory,
                    conflicts = state.conflicts,
                    onApplyConflict = onApplyConflict,
                )
                EditorSheet.MASKS -> {
                    ToolCategoryBar(
                        selected = state.panel,
                        onSelect = onSelectPanel,
                        modifier = Modifier.fillMaxWidth().alpha(chromeAlpha),
                    )
                    MaskListPanel(
                        masks = state.masks,
                        notice = state.maskNotice,
                        actions = maskActions,
                    )
                }
                // The same `SliderPanel` as the global categories, with the groups filtered. A second
                // copy of it would be a second way for the same slider to behave.
                EditorSheet.MASK_ADJUSTMENTS -> {
                    val mask = state.selectedMask
                    if (mask == null) {
                        MaskListPanel(
                            masks = state.masks,
                            notice = state.maskNotice,
                            actions = maskActions,
                        )
                    } else {
                        MaskAdjustmentsHeader(
                            mask = mask,
                            overlayPinned = state.maskOverlayPinned,
                            actions = maskActions,
                            modifier = Modifier.alpha(chromeAlpha),
                        )
                        SliderPanel(
                            state = state,
                            groups = EditorPanel.MASKS.adjustmentGroups(),
                            editingKey = editingKey,
                            onEditing = onEditing,
                            onAdjustment = onAdjustment,
                            onAdjustmentFinished = onAdjustmentFinished,
                            onResetAdjustment = onResetAdjustment,
                        )
                    }
                }
            }
        }
    }
    }
}

/** A group of sliders, with a header when the panel has more than one family of adjustments. */
private data class AdjustmentGroup(val title: String?, val entries: List<Pair<Adjustment, String>>)

@Composable
private fun EditorPanel.adjustmentGroups(): List<AdjustmentGroup> = when (this) {
    EditorPanel.LIGHT -> listOf(
        AdjustmentGroup(
            title = null,
            entries = listOf(
                Adjustment.EXPOSURE,
                Adjustment.CONTRAST,
                Adjustment.HIGHLIGHTS,
                Adjustment.SHADOWS,
                Adjustment.WHITES,
                Adjustment.BLACKS,
            ).withLabels(),
        ),
    )
    EditorPanel.COLOR -> listOf(
        AdjustmentGroup(
            title = null,
            entries = listOf(
                Adjustment.TEMPERATURE,
                Adjustment.TINT,
                Adjustment.VIBRANCE,
                Adjustment.SATURATION,
            ).withLabels(),
        ),
    )
    EditorPanel.DETAIL -> listOf(
        AdjustmentGroup(
            title = null,
            entries = listOf(
                Adjustment.TEXTURE,
                Adjustment.CLARITY,
                Adjustment.DEHAZE,
            ).withLabels(),
        ),
    )
    EditorPanel.EFFECTS -> listOf(
        AdjustmentGroup(
            title = stringResource(Res.string.editor_group_vignette),
            entries = listOf(
                Adjustment.VIGNETTE_AMOUNT to stringResource(Res.string.editor_amount),
                Adjustment.VIGNETTE_MIDPOINT to stringResource(Res.string.editor_midpoint),
                Adjustment.VIGNETTE_ROUNDNESS to stringResource(Res.string.editor_roundness),
                Adjustment.VIGNETTE_FEATHER to stringResource(Res.string.editor_feather),
            ),
        ),
        AdjustmentGroup(
            title = stringResource(Res.string.editor_group_grain),
            entries = listOf(
                Adjustment.GRAIN_AMOUNT to stringResource(Res.string.editor_amount),
                Adjustment.GRAIN_SIZE to stringResource(Res.string.editor_size),
                Adjustment.GRAIN_ROUGHNESS to stringResource(Res.string.editor_roughness),
            ),
        ),
    )
    // The thirteen adjustments a region accepts, by the same families as the global categories. The
    // curve, HSL, vignette and grain are not here — see `Adjustment.isLocal()`.
    EditorPanel.MASKS -> listOf(
        AdjustmentGroup(
            title = stringResource(Res.string.editor_panel_light),
            entries = listOf(
                Adjustment.EXPOSURE,
                Adjustment.CONTRAST,
                Adjustment.HIGHLIGHTS,
                Adjustment.SHADOWS,
                Adjustment.WHITES,
                Adjustment.BLACKS,
            ).withLabels(),
        ),
        AdjustmentGroup(
            title = stringResource(Res.string.editor_panel_color),
            entries = listOf(
                Adjustment.TEMPERATURE,
                Adjustment.TINT,
                Adjustment.VIBRANCE,
                Adjustment.SATURATION,
            ).withLabels(),
        ),
        AdjustmentGroup(
            title = stringResource(Res.string.editor_panel_detail),
            entries = listOf(
                Adjustment.TEXTURE,
                Adjustment.CLARITY,
                Adjustment.DEHAZE,
            ).withLabels(),
        ),
    )
}

/**
 * Lends each adjustment the name the history also uses.
 *
 * Inside a group with a header — "Vignette", "Grain" — the full label would be redundant, and so
 * those are still written by hand as "Amount" or "Size".
 */
@Composable
private fun List<Adjustment>.withLabels(): List<Pair<Adjustment, String>> = map { it to it.label() }

@Composable
private fun SliderPanel(
    state: EditorUiState,
    groups: List<AdjustmentGroup>,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    onAdjustment: (Adjustment, Float) -> Unit,
    onAdjustmentFinished: () -> Unit,
    onResetAdjustment: (Adjustment) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
    ) {
        groups.forEach { group ->
            group.title?.let { title ->
                val headerAlpha by animateFloatAsState(
                    targetValue = if (editingKey == null) 1f else 0f,
                    animationSpec = tween(durationMillis = 160),
                    label = "groupHeader",
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ImagoColors.Gold,
                    modifier = Modifier
                        .alpha(headerAlpha)
                        .padding(top = ImagoSpacing.Sm, bottom = ImagoSpacing.Xs),
                )
            }
            group.entries.forEach { (adjustment, label) ->
                val parent = adjustment.parent()
                val enabled = parent == null || state.valueOf(parent) != parent.neutral
                ImagoParameterSlider(
                    label = label,
                    value = state.valueOf(adjustment),
                    neutral = adjustment.neutral,
                    range = adjustment.range,
                    pointerKey = adjustment,
                    enabled = enabled,
                    editingKey = editingKey,
                    onEditing = onEditing,
                    valueText = { formatAdjustmentValue(adjustment, it) },
                    onValueChange = { onAdjustment(adjustment, it) },
                    onValueChangeFinished = onAdjustmentFinished,
                    onReset = { onResetAdjustment(adjustment) },
                )
            }
        }
    }
}

/**
 * The precision tools that open from inside a category. The curve belongs to light, HSL and grading
 * to colour: they are refinements of the same matter, and that is where they open from.
 */
internal fun EditorPanel.subTools(): List<EditorSheet> = when (this) {
    EditorPanel.LIGHT -> listOf(EditorSheet.CURVE)
    EditorPanel.COLOR -> listOf(EditorSheet.HSL, EditorSheet.COLOR_GRADING)
    else -> emptyList()
}

/**
 * The top of the adjustments panel: the categories across the whole width, and under them the
 * active category's precision tools.
 *
 * The tools used to sit in the categories' row. With two of them, in Colour, the row ran out of width
 * and the last category — Masks — fell off the screen; and side by side with the categories they read
 * as one more category instead of something inside the selected one.
 */
@Composable
internal fun AdjustmentPanelHeader(
    panel: EditorPanel,
    onSelectPanel: (EditorPanel) -> Unit,
    onSelectSheet: (EditorSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        ToolCategoryBar(selected = panel, onSelect = onSelectPanel)
        val subTools = panel.subTools()
        if (subTools.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ImagoSpacing.Lg),
                horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
            ) {
                subTools.forEach { sheet ->
                    SubToolChip(
                        label = sheet.subToolLabel(),
                        icon = sheet.subToolIcon(),
                        onClick = { onSelectSheet(sheet) },
                    )
                }
            }
        }
    }
}

/** The name of each mode with its own interface, as the chip that opens it shows it. */
@Composable
private fun EditorSheet.subToolLabel(): String = when (this) {
    EditorSheet.CURVE -> stringResource(Res.string.editor_chip_curve)
    EditorSheet.PERSPECTIVE -> stringResource(Res.string.editor_chip_perspective)
    EditorSheet.HSL -> "HSL"
    EditorSheet.COLOR_GRADING -> stringResource(Res.string.editor_chip_grading)
    else -> ""
}

private fun EditorSheet.subToolIcon(): ImageVector = when (this) {
    EditorSheet.HSL -> Icons.Filled.Circle
    EditorSheet.COLOR_GRADING -> Icons.Outlined.ColorLens
    EditorSheet.PERSPECTIVE -> Icons.Outlined.Transform
    else -> Icons.Outlined.Timeline
}

/**
 * The door to a precision tool.
 *
 * Gold and a chevron, where the categories are white icons: it has to read as a tool that opens from
 * the category, not as one more category beside it.
 */
@Composable
private fun SubToolChip(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ImagoRadii.Pill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(ImagoColors.Gold.copy(alpha = 0.10f))
            .border(1.dp, ImagoColors.Gold.copy(alpha = 0.35f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(start = ImagoSpacing.Md, end = ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = ImagoColors.Gold,
            modifier = Modifier.size(ImagoSizes.IconSmall),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = ImagoColors.Gold,
            maxLines = 1,
            modifier = Modifier.padding(start = ImagoSpacing.Xs),
        )
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = ImagoColors.Gold,
            modifier = Modifier.size(ImagoSizes.IconSmall),
        )
    }
}

@Composable
private fun ToolCategoryBar(
    selected: EditorPanel,
    onSelect: (EditorPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Icons instead of labels: four words side by side fought the panel for width and read like a
    // menu. The name goes into `contentDescription`, which is what TalkBack reads.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ImagoSpacing.Sm),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        EditorPanel.entries.forEach { panel ->
            val isSelected = selected == panel
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(ImagoRadii.Small))
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(panel) },
                    )
                    .sizeIn(minWidth = ImagoSizes.TouchTarget, minHeight = ImagoSizes.TouchTarget)
                    .padding(horizontal = ImagoSpacing.Md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = panel.vector(),
                    contentDescription = panel.label(),
                    tint = if (isSelected) ImagoColors.TextPrimary else ImagoColors.TextTertiary,
                    modifier = Modifier.size(ImagoSizes.IconLarge),
                )
                // A dot, and not an underline: it is what the mockup shows, and it weighs less over
                // the photo than a gold bar.
                Box(
                    Modifier
                        .padding(top = ImagoSpacing.Sm)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) ImagoColors.BrandWhite else Color.Transparent),
                )
            }
        }
    }
}

@Composable
internal fun EditorPanel.label() = when (this) {
    EditorPanel.LIGHT -> stringResource(Res.string.editor_panel_light)
    EditorPanel.COLOR -> stringResource(Res.string.editor_panel_color)
    EditorPanel.DETAIL -> stringResource(Res.string.editor_panel_detail)
    EditorPanel.EFFECTS -> stringResource(Res.string.editor_panel_effects)
    EditorPanel.MASKS -> stringResource(Res.string.editor_panel_masks)
}

private fun EditorPanel.vector() = when (this) {
    EditorPanel.LIGHT -> Icons.Outlined.WbSunny
    EditorPanel.COLOR -> Icons.Outlined.Palette
    EditorPanel.DETAIL -> Icons.Outlined.Deblur
    EditorPanel.EFFECTS -> Icons.Outlined.Grain
    EditorPanel.MASKS -> Icons.Outlined.Adjust
}

@Composable
private fun CurvePanel(
    state: EditorUiState,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    onAddPoint: (Int, Int) -> Unit,
    onMovePoint: (Int, Int, Int) -> Unit,
    onRemovePoint: (Int) -> Unit,
    onGestureFinished: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val curve = state.renderParameters.toneCurveRgb
    val points = state.recipe?.editableCurvePoints().orEmpty()
    val currentPoints by rememberUpdatedState(points)
    var activePoint by remember { mutableStateOf<Int?>(null) }
    val gridColor = ImagoColors.BorderVisible
    val curveColor = ImagoColors.Ivory
    val hintAlpha by animateFloatAsState(
        targetValue = if (editingKey == null) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "curveHint",
    )
    val gridAlpha by animateFloatAsState(
        targetValue = if (editingKey == null) 0.16f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "curveGrid",
    )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubToolHeader(
            title = stringResource(Res.string.editor_tone_curve),
            onClose = onClose,
            onReset = onReset,
            modifier = Modifier.alpha(hintAlpha),
        )
        Text(
            stringResource(Res.string.editor_curve_hint),
            style = MaterialTheme.typography.bodySmall,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.alpha(hintAlpha),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = gridAlpha))
                .border(1.dp, ImagoColors.BorderVisible.copy(alpha = 0.14f * hintAlpha), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    val hitRadius = 24.dp.toPx()
                    fun pointIndexAt(position: Offset): Int? = currentPoints
                        .mapIndexed { index, point ->
                            val pointPosition = Offset(point.x / 255f * size.width, (1f - point.y / 255f) * size.height)
                            index to (pointPosition - position).getDistance()
                        }
                        .filter { it.second <= hitRadius }
                        .minByOrNull { it.second }
                        ?.first
                    fun curveCoordinates(position: Offset) =
                        ((position.x / size.width * 255f).toInt().coerceIn(0, 255)) to
                            (((1f - position.y / size.height) * 255f).toInt().coerceIn(0, 255))
                    detectTapGestures(
                        onTap = { position ->
                            if (pointIndexAt(position) == null) {
                                val (x, y) = curveCoordinates(position)
                                onAddPoint(x, y)
                                onGestureFinished()
                            }
                        },
                        onDoubleTap = { position -> pointIndexAt(position)?.let(onRemovePoint) },
                    )
                }
                .pointerInput(Unit) {
                    val hitRadius = 24.dp.toPx()
                    fun pointIndexAt(position: Offset): Int? = currentPoints
                        .mapIndexed { index, point ->
                            val pointPosition = Offset(point.x / 255f * size.width, (1f - point.y / 255f) * size.height)
                            index to (pointPosition - position).getDistance()
                        }
                        .filter { it.second <= hitRadius }
                        .minByOrNull { it.second }
                        ?.first
                    detectDragGestures(
                        onDragStart = { position ->
                            activePoint = pointIndexAt(position)
                            if (activePoint != null) onEditing(CURVE_EDITING_KEY)
                        },
                        onDragEnd = {
                            if (activePoint != null) onGestureFinished()
                            activePoint = null
                            onEditing(null)
                        },
                        onDragCancel = {
                            if (activePoint != null) onGestureFinished()
                            activePoint = null
                            onEditing(null)
                        },
                    ) { change, _ ->
                        activePoint?.let { index ->
                            val x = (change.position.x / size.width * 255f).toInt().coerceIn(0, 255)
                            val y = ((1f - change.position.y / size.height) * 255f).toInt().coerceIn(0, 255)
                            onMovePoint(index, x, y)
                            change.consume()
                        }
                    }
                },
        ) {
            repeat(5) { index ->
                val line = gridColor.copy(alpha = gridColor.alpha * hintAlpha)
                val position = index * size.width / 4f
                drawLine(line, Offset(position, 0f), Offset(position, size.height))
                val vertical = index * size.height / 4f
                drawLine(line, Offset(0f, vertical), Offset(size.width, vertical))
            }
            for (index in 0 until curve.lastIndex) {
                drawLine(
                    color = curveColor,
                    start = Offset(index / 255f * size.width, (1f - curve[index]) * size.height),
                    end = Offset((index + 1) / 255f * size.width, (1f - curve[index + 1]) * size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
            points.forEachIndexed { index, point ->
                drawCircle(
                    color = if (activePoint == index) Color.White else curveColor,
                    radius = if (activePoint == index) 9.dp.toPx() else 7.dp.toPx(),
                    center = Offset(point.x / 255f * size.width, (1f - point.y / 255f) * size.height),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.75f),
                    radius = if (activePoint == index) 5.dp.toPx() else 3.5.dp.toPx(),
                    center = Offset(point.x / 255f * size.width, (1f - point.y / 255f) * size.height),
                )
            }
        }
    }
}

/**
 * The crop frame, drawn over the photo.
 *
 * It only draws. The gestures left here for the stage's single block when the photo started moving
 * under the frame: with both responding to the same finger, whoever decides whose touch it is has to
 * be a single `awaitFirstDown` — two sibling blocks fought over it.
 *
 * The rectangle it sits on comes from [photoBounds] and not from a fit computed here. It is the same
 * calculation the renderer uses for the viewport, which is why the frame does not come loose from
 * the photo when there is zoom.
 */
@Composable
private fun CropOverlay(
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int,
    cropRect: CropRect,
    aspectLock: String?,
    transform: PhotoTransform,
    gridDivisions: Int,
    onGestureStart: () -> Unit,
    onCropRectChange: (CropRect) -> Unit,
    onGestureFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCrop by rememberUpdatedState(cropRect)
    val lockedRatio by rememberUpdatedState(
        lockedCropRatio(aspectLock, imageWidth, imageHeight, rotation),
    )
    val imageRatio = orientedImageRatio(imageWidth, imageHeight, rotation)
    val quarterTurns = ((rotation % 360) + 360) % 360 / 90
    val density = LocalDensity.current
    val borderWidth = with(density) { 2.dp.toPx() }
    val gridWidth = with(density) { 1.dp.toPx() }
    val handleWidth = with(density) { 4.dp.toPx() }
    val handleLength = with(density) { 18.dp.toPx() }

    fun nudge(handle: CropHandle, dx: Float, dy: Float) {
        onGestureStart()
        onCropRectChange(currentCrop.dragged(handle, dx, dy, lockedRatio, imageRatio))
        onGestureFinished()
    }

    val frameDescription = stringResource(Res.string.editor_crop_frame)
    val freeAspect = stringResource(Res.string.editor_aspect_free_state)
    val moveLeft = stringResource(Res.string.editor_crop_move_left)
    val moveRight = stringResource(Res.string.editor_crop_move_right)
    val moveUp = stringResource(Res.string.editor_crop_move_up)
    val moveDown = stringResource(Res.string.editor_crop_move_down)
    val enlarge = stringResource(Res.string.editor_crop_enlarge)
    val shrink = stringResource(Res.string.editor_crop_shrink)
    Canvas(
        modifier = modifier.semantics {
            contentDescription = frameDescription
            stateDescription = aspectLock ?: freeAspect
            // Neither the pinch nor the drag exist for TalkBack users, and the inside of the frame no
            // longer moves it — without these actions crop would stop being reachable.
            customActions = listOf(
                CustomAccessibilityAction(moveLeft) {
                    nudge(CropHandle.MOVE, -CROP_NUDGE, 0f); true
                },
                CustomAccessibilityAction(moveRight) {
                    nudge(CropHandle.MOVE, CROP_NUDGE, 0f); true
                },
                CustomAccessibilityAction(moveUp) {
                    nudge(CropHandle.MOVE, 0f, -CROP_NUDGE); true
                },
                CustomAccessibilityAction(moveDown) {
                    nudge(CropHandle.MOVE, 0f, CROP_NUDGE); true
                },
                CustomAccessibilityAction(enlarge) {
                    nudge(CropHandle.BOTTOM_RIGHT, CROP_NUDGE, CROP_NUDGE); true
                },
                CustomAccessibilityAction(shrink) {
                    nudge(CropHandle.BOTTOM_RIGHT, -CROP_NUDGE, -CROP_NUDGE); true
                },
            )
        },
    ) {
        val bounds = photoBounds(
            surfaceWidth = size.width.toInt(),
            surfaceHeight = size.height.toInt(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            transform = transform,
            quarterTurns = quarterTurns,
            minZoom = MIN_CROP_ZOOM,
        )
        val crop = cropFrameOf(bounds, cropRect)

        // The dimming covers the whole stage and not just the photo: with the zoom-out, what is
        // around it is also outside the crop and should not compete with what is inside.
        val scrim = Color.Black.copy(alpha = 0.62f)
        drawRect(scrim, Offset.Zero, Size(size.width, crop.top.coerceAtLeast(0f)))
        drawRect(scrim, Offset(0f, crop.bottom), Size(size.width, (size.height - crop.bottom).coerceAtLeast(0f)))
        drawRect(scrim, Offset(0f, crop.top), Size(crop.left.coerceAtLeast(0f), crop.height))
        drawRect(scrim, Offset(crop.right, crop.top), Size((size.width - crop.right).coerceAtLeast(0f), crop.height))

        drawRect(
            color = Color.White.copy(alpha = 0.94f),
            topLeft = Offset(crop.left, crop.top),
            size = Size(crop.width, crop.height),
            style = Stroke(width = borderWidth),
        )
        // Thirds at rest; denser while straightening, which is when it serves as a plumb line.
        for (division in 1 until gridDivisions) {
            val x = crop.left + crop.width * division / gridDivisions
            val y = crop.top + crop.height * division / gridDivisions
            drawLine(Color.White.copy(alpha = 0.48f), Offset(x, crop.top), Offset(x, crop.bottom), gridWidth)
            drawLine(Color.White.copy(alpha = 0.48f), Offset(crop.left, y), Offset(crop.right, y), gridWidth)
        }

        fun handleLine(start: Offset, end: Offset) = drawLine(
            color = Color.White,
            start = start,
            end = end,
            strokeWidth = handleWidth,
            cap = StrokeCap.Square,
        )
        val centreX = (crop.left + crop.right) / 2f
        val centreY = (crop.top + crop.bottom) / 2f
        handleLine(Offset(crop.left, crop.top), Offset(crop.left + handleLength, crop.top))
        handleLine(Offset(crop.left, crop.top), Offset(crop.left, crop.top + handleLength))
        handleLine(Offset(crop.right, crop.top), Offset(crop.right - handleLength, crop.top))
        handleLine(Offset(crop.right, crop.top), Offset(crop.right, crop.top + handleLength))
        handleLine(Offset(crop.left, crop.bottom), Offset(crop.left + handleLength, crop.bottom))
        handleLine(Offset(crop.left, crop.bottom), Offset(crop.left, crop.bottom - handleLength))
        handleLine(Offset(crop.right, crop.bottom), Offset(crop.right - handleLength, crop.bottom))
        handleLine(Offset(crop.right, crop.bottom), Offset(crop.right, crop.bottom - handleLength))
        handleLine(Offset(centreX - handleLength / 2f, crop.top), Offset(centreX + handleLength / 2f, crop.top))
        handleLine(Offset(centreX - handleLength / 2f, crop.bottom), Offset(centreX + handleLength / 2f, crop.bottom))
        handleLine(Offset(crop.left, centreY - handleLength / 2f), Offset(crop.left, centreY + handleLength / 2f))
        handleLine(Offset(crop.right, centreY - handleLength / 2f), Offset(crop.right, centreY + handleLength / 2f))
    }
}

/**
 * Which handle the finger grabbed, or `null` if it grabbed none.
 *
 * [CropHandle.MOVE] is still returned for the inside of the frame, but the gesture discards it: from
 * the moment dragging moves the photo, moving the frame by its middle would be asking two things of
 * the same touch. Whoever needs that has the accessibility actions.
 */
private fun hitCropHandle(frame: CropFrame, position: Offset, radius: Float): CropHandle? {
    val nearLeft = kotlin.math.abs(position.x - frame.left) <= radius
    val nearRight = kotlin.math.abs(position.x - frame.right) <= radius
    val nearTop = kotlin.math.abs(position.y - frame.top) <= radius
    val nearBottom = kotlin.math.abs(position.y - frame.bottom) <= radius
    val beside = position.y in (frame.top - radius)..(frame.bottom + radius)
    val across = position.x in (frame.left - radius)..(frame.right + radius)
    return when {
        nearLeft && nearTop -> CropHandle.TOP_LEFT
        nearRight && nearTop -> CropHandle.TOP_RIGHT
        nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
        nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
        nearLeft && beside -> CropHandle.LEFT
        nearRight && beside -> CropHandle.RIGHT
        nearTop && across -> CropHandle.TOP
        nearBottom && across -> CropHandle.BOTTOM
        position.x in frame.left..frame.right && position.y in frame.top..frame.bottom -> CropHandle.MOVE
        else -> null
    }
}

/**
 * The crop drawer: only the aspect ratios.
 *
 * Everything else — straighten, rotate, flip, reset — moved up over the photo. Here there was a list
 * of nine sections squeezed into three hundred points of height, and each of them was height stolen
 * from what is being framed. The aspect ratios stay because they are a choice between names, and a
 * name is not dragged over an image.
 */
@Composable
private fun CropAspectSheet(
    state: EditorUiState,
    onSelectCropAspect: (String?) -> Unit,
    onOpenPerspective: () -> Unit,
) {
    val geometry = state.recipe?.geometry ?: Geometry()
    val aspectDescription = stringResource(Res.string.editor_crop_aspect_description)
    // The perspective sits where the curve sits beside Light: fixed at the end, not lost after the
    // last aspect ratio of a row that scrolls.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState())
            .padding(start = ImagoSpacing.Lg, end = ImagoSpacing.Sm, top = ImagoSpacing.Md, bottom = ImagoSpacing.Md)
            .semantics { contentDescription = aspectDescription },
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
    ) {
        CropAspectButton(
            label = stringResource(Res.string.editor_aspect_free),
            selected = geometry.aspectLock == null,
            onClick = { onSelectCropAspect(null) },
        )
        CropAspectButton(
            label = stringResource(Res.string.editor_original),
            selected = geometry.aspectLock == ORIGINAL_CROP_ASPECT,
            onClick = { onSelectCropAspect(ORIGINAL_CROP_ASPECT) },
        )
        commonCropAspects.forEach { aspect ->
            CropAspectButton(
                label = aspect.id,
                selected = geometry.aspectLock == aspect.id,
                onClick = { onSelectCropAspect(aspect.id) },
            )
        }
    }
    SubToolChip(
        label = EditorSheet.PERSPECTIVE.subToolLabel(),
        icon = EditorSheet.PERSPECTIVE.subToolIcon(),
        onClick = onOpenPerspective,
        modifier = Modifier.padding(end = ImagoSpacing.Lg),
    )
    }
}

/** What the perspective sheet asks of the screen besides the sliders, which go the way of all others. */
data class PerspectiveActions(
    val onConstrainCrop: (Boolean) -> Unit,
    val onReset: () -> Unit,
    val onUprightMode: (String) -> Unit,
    val onGuideGestureStart: () -> Unit,
    val onGuidesChange: (List<UprightGuide>) -> Unit,
    val onGuideGestureEnd: () -> Unit,
    val onRemoveLastGuide: () -> Unit,
    val onClearGuides: () -> Unit,
)

/**
 * The perspective, opened from the crop the way the curve opens from Light: the same `SliderPanel`,
 * under a header that goes back to the crop.
 *
 * The stage shows the result and not the crop's whole photo: the sliders are judged by the frame
 * they produce, and the grid drawn while one is dragged is what tells parallel lines from nearly
 * parallel ones.
 */
@Composable
private fun PerspectivePanel(
    state: EditorUiState,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    onAdjustment: (Adjustment, Float) -> Unit,
    onAdjustmentFinished: () -> Unit,
    onResetAdjustment: (Adjustment) -> Unit,
    actions: PerspectiveActions,
    onClose: () -> Unit,
) {
    val chromeAlpha by animateFloatAsState(
        targetValue = if (editingKey == null) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "perspectiveChrome",
    )
    val perspective = state.recipe?.geometry?.perspective ?: Perspective()
    Column(Modifier.fillMaxSize()) {
        SubToolHeader(
            title = EditorSheet.PERSPECTIVE.subToolLabel(),
            onClose = onClose,
            onReset = actions.onReset.takeIf { perspective != Perspective() },
            modifier = Modifier.alpha(chromeAlpha).padding(horizontal = ImagoSpacing.Lg),
        )
        UprightRow(
            perspective = perspective,
            detecting = state.isDetectingUpright,
            notice = state.uprightNotice,
            actions = actions,
            modifier = Modifier.alpha(chromeAlpha).padding(horizontal = ImagoSpacing.Lg),
        )
        ConstrainCropRow(
            checked = perspective.constrainCrop,
            onCheckedChange = actions.onConstrainCrop,
            modifier = Modifier.alpha(chromeAlpha).padding(horizontal = ImagoSpacing.Lg),
        )
        SliderPanel(
            state = state,
            groups = listOf(
                AdjustmentGroup(
                    title = null,
                    entries = listOf(
                        Adjustment.PERSPECTIVE_VERTICAL to stringResource(Res.string.editor_perspective_vertical),
                        Adjustment.PERSPECTIVE_HORIZONTAL to stringResource(Res.string.editor_perspective_horizontal),
                        Adjustment.PERSPECTIVE_ASPECT to stringResource(Res.string.editor_perspective_aspect),
                        Adjustment.PERSPECTIVE_SCALE to stringResource(Res.string.editor_perspective_scale),
                        Adjustment.PERSPECTIVE_OFFSET_X to stringResource(Res.string.editor_perspective_offset_x),
                        Adjustment.PERSPECTIVE_OFFSET_Y to stringResource(Res.string.editor_perspective_offset_y),
                    ),
                ),
            ),
            editingKey = editingKey,
            onEditing = onEditing,
            onAdjustment = onAdjustment,
            onAdjustmentFinished = onAdjustmentFinished,
            onResetAdjustment = onResetAdjustment,
        )
    }
}

/**
 * Lightroom's Upright modes that the app can do so far — off and guided — and, in guided, what the
 * finger does on the photo and how many guides there are. The automatic modes join this row when
 * they exist, not before.
 */
@Composable
private fun UprightRow(
    perspective: Perspective,
    detecting: Boolean,
    notice: UiText?,
    actions: PerspectiveActions,
    modifier: Modifier = Modifier,
) {
    val guided = perspective.upright == UPRIGHT_GUIDED
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.editor_upright),
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (detecting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ImagoColors.Gold)
            }
        }
        // Six modes do not fit a phone's width: the row scrolls, as the crop's aspect ratios do.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        ) {
            listOf(UPRIGHT_OFF, UPRIGHT_AUTO, UPRIGHT_LEVEL, UPRIGHT_VERTICAL, UPRIGHT_FULL, UPRIGHT_GUIDED).forEach { mode ->
                CropAspectButton(
                    label = stringResource(uprightModeName(mode)),
                    selected = perspective.upright == mode,
                    onClick = { actions.onUprightMode(mode) },
                )
            }
        }
        notice?.let {
            Text(
                text = it.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
            )
        }
        if (guided) {
            Text(
                text = stringResource(Res.string.editor_upright_guided_hint, MIN_UPRIGHT_GUIDES, MAX_UPRIGHT_GUIDES),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.editor_upright_guides_count, perspective.guides.size, MAX_UPRIGHT_GUIDES),
                    style = MaterialTheme.typography.labelLarge,
                    color = ImagoColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onRemoveLastGuide, enabled = perspective.guides.isNotEmpty()) {
                    Text(stringResource(Res.string.editor_upright_remove_last))
                }
                TextButton(onClick = actions.onClearGuides, enabled = perspective.guides.isNotEmpty()) {
                    Text(stringResource(Res.string.editor_upright_clear))
                }
            }
        }
    }
}

@Composable
private fun ConstrainCropRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .sizeIn(minHeight = ImagoSizes.TouchTarget)
            .padding(vertical = ImagoSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.editor_constrain_crop),
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoColors.TextPrimary,
            )
            Text(
                text = stringResource(Res.string.editor_constrain_crop_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ImagoColors.TextTertiary,
            )
        }
        // The row is the control: the switch only shows its state, so the touch is not counted twice.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = ImagoColors.Gold),
        )
    }
}

/**
 * The grid drawn over the photo while a perspective slider is dragged. Lines that should be vertical
 * or level are judged against it; without it "almost parallel" looks parallel.
 */
@Composable
private fun PerspectiveGrid(bounds: PhotoBounds, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.35f)
        for (division in 1 until PERSPECTIVE_GRID_DIVISIONS) {
            val x = bounds.left + bounds.width * division / PERSPECTIVE_GRID_DIVISIONS
            val y = bounds.top + bounds.height * division / PERSPECTIVE_GRID_DIVISIONS
            drawLine(color, Offset(x, bounds.top), Offset(x, bounds.bottom), stroke)
            drawLine(color, Offset(bounds.left, y), Offset(bounds.right, y), stroke)
        }
    }
}

private const val PERSPECTIVE_GRID_DIVISIONS = 8

/** How far from a guide's end a finger still takes it: the size of a fingertip, as the crop's handles. */
private val GUIDE_HANDLE_HIT_RADIUS = 28.dp

/**
 * Rotate, flip and reset, in a capsule over the photo.
 *
 * They were in the drawer, and forced opening a tall panel for a one-tap action. The capsule is the
 * same language as the zoom badge — translucent black and rounded corners — so there are not two ways
 * of saying "this floats over the image".
 */
@Composable
private fun CropActionRow(
    rotation: Int,
    mirrorH: Boolean,
    mirrorV: Boolean,
    canReset: Boolean,
    onRotateClockwise: () -> Unit,
    onToggleMirrorHorizontal: () -> Unit,
    onToggleMirrorVertical: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Pill))
            .background(Color.Black.copy(alpha = 0.56f))
            .padding(horizontal = ImagoSpacing.Xs),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CropActionButton(
            icon = Icons.Outlined.Rotate90DegreesCw,
            label = if (rotation == 0) stringResource(Res.string.editor_rotate_90) else stringResource(Res.string.editor_rotate_90_now, rotation),
            active = rotation != 0,
            onClick = onRotateClockwise,
        )
        CropActionButton(
            icon = Icons.Outlined.SwapHoriz,
            label = stringResource(Res.string.editor_flip_horizontal),
            active = mirrorH,
            onClick = onToggleMirrorHorizontal,
        )
        CropActionButton(
            icon = Icons.Outlined.SwapVert,
            label = stringResource(Res.string.editor_flip_vertical),
            active = mirrorV,
            onClick = onToggleMirrorVertical,
        )
        CropActionButton(
            icon = Icons.Outlined.Restore,
            label = stringResource(Res.string.editor_reset_geometry),
            active = false,
            enabled = canReset,
            onClick = onReset,
        )
    }
}

@Composable
private fun CropActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                !enabled -> ImagoColors.TextDisabled
                active -> ImagoColors.Gold
                else -> ImagoColors.TextPrimary
            },
            modifier = Modifier.size(ImagoSizes.IconLarge),
        )
    }
}

@Composable
private fun CropAspectButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) ImagoColors.Gold.copy(alpha = 0.22f) else ImagoColors.SurfaceElevated,
        shape = RoundedCornerShape(ImagoRadii.Medium),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) ImagoColors.Gold else ImagoColors.BorderVisible,
        ),
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) ImagoColors.Ivory else ImagoColors.TextSecondary,
            modifier = Modifier.padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Md),
        )
    }
}

@Composable
private fun HslPanel(
    state: EditorUiState,
    editingKey: Any?,
    onEditing: (Any?) -> Unit,
    onSelectBand: (HslColorBand) -> Unit,
    onHsl: (HslColorBand, HslComponent, Float) -> Unit,
    onAdjustmentFinished: () -> Unit,
    onResetHsl: (HslColorBand, HslComponent) -> Unit,
    onClose: () -> Unit,
) {
    val selectorAlpha by animateFloatAsState(
        targetValue = if (editingKey == null) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "hslSelector",
    )
    Column(Modifier.fillMaxSize()) {
        SubToolHeader(
            title = stringResource(Res.string.editor_color_mixer),
            onClose = onClose,
            onReset = null,
            modifier = Modifier.alpha(selectorAlpha).padding(horizontal = ImagoSpacing.Lg),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(selectorAlpha)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Xs),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xl),
        ) {
            HslColorBand.entries.forEach { band ->
                val bandLabel = band.historyLabel()
                val selected = state.selectedHslBand == band
                Box(
                    modifier = Modifier
                        .size(ImagoSizes.TouchTarget)
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelectBand(band) },
                        )
                        // Without this TalkBack only sees a coloured circle: the colour is the only clue.
                        .semantics { contentDescription = bandLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(if (selected) 27.dp else 23.dp)
                            .clip(CircleShape)
                            .background(band.selectorColor())
                            .then(
                                if (selected) Modifier.border(2.dp, ImagoColors.Ivory, CircleShape)
                                else Modifier.border(1.dp, ImagoColors.BorderVisible, CircleShape),
                            ),
                    )
                }
            }
        }
        val band = state.selectedHslBand
        val values = state.recipe?.hslBand(band) ?: eu.studio742.imago.core.model.HslBand()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ImagoSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs),
        ) {
            HslComponent.entries.forEach { component ->
                ImagoParameterSlider(
                    label = component.historyLabel(),
                    value = values.component(component),
                    neutral = 0f,
                    range = -100f..100f,
                    pointerKey = "$band-$component",
                    editingKey = editingKey,
                    onEditing = onEditing,
                    valueText = { "%+.0f".format(it) },
                    onValueChange = { onHsl(band, component, it) },
                    onValueChangeFinished = onAdjustmentFinished,
                    onReset = { onResetHsl(band, component) },
                )
            }
        }
    }
}

private fun HslColorBand.selectorColor() = when (this) {
    HslColorBand.RED -> Color(0xFFE14B4B)
    HslColorBand.ORANGE -> Color(0xFFF08A3C)
    HslColorBand.YELLOW -> Color(0xFFE4C84B)
    HslColorBand.GREEN -> Color(0xFF52A86A)
    HslColorBand.AQUA -> Color(0xFF48B9B3)
    HslColorBand.BLUE -> Color(0xFF4F79D8)
    HslColorBand.PURPLE -> Color(0xFF8B68C8)
    HslColorBand.MAGENTA -> Color(0xFFC85A99)
}

/** The curve's edit key; its handles are not sliders but fade the panel all the same. */
private const val CURVE_EDITING_KEY = "curve"
