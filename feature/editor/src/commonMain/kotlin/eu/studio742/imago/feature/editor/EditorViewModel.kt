package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.asUiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import eu.studio742.imago.core.render.libraryAuth
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.Bitmap
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.toBitmap
import eu.studio742.imago.core.render.pixelHeight
import eu.studio742.imago.core.render.pixelWidth
import eu.studio742.imago.core.render.softwareBitmap
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.model.BUILT_IN_RECIPES
import eu.studio742.imago.core.model.BuiltInRecipe
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.RecipeConflictVersion
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Perspective
import eu.studio742.imago.core.model.MAX_UPRIGHT_GUIDES
import eu.studio742.imago.core.model.AUTOMATIC_UPRIGHT_MODES
import eu.studio742.imago.core.model.UPRIGHT_AUTO
import eu.studio742.imago.core.model.UPRIGHT_FULL
import eu.studio742.imago.core.model.UPRIGHT_LEVEL
import eu.studio742.imago.core.model.UPRIGHT_VERTICAL
import eu.studio742.imago.core.render.LineSegment
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.detectLineSegments
import eu.studio742.imago.core.render.solveAutoUpright
import eu.studio742.imago.core.render.toPixelBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.model.UPRIGHT_GUIDED
import eu.studio742.imago.core.model.UPRIGHT_OFF
import eu.studio742.imago.core.model.UprightGuide
import eu.studio742.imago.core.render.frameGeometry
import eu.studio742.imago.core.render.solveGuidedUpright
import kotlin.math.hypot
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions
import eu.studio742.imago.core.immich.requirePermission
import eu.studio742.imago.core.render.RenderParameters
import eu.studio742.imago.core.render.toRenderParameters
import kotlin.math.floor
import java.io.File
import java.time.Instant
import java.util.UUID

data class EditorAsset(
    val id: String,
    val checksum: String,
    val fileName: String,
    val previewUrl: String,
    val apiKey: String,
    val fileCreatedAt: String,
)

sealed interface PhotoEditTarget {
    val asset: EditorAsset

    data class Asset(override val asset: EditorAsset) : PhotoEditTarget
    data class Composition(
        override val asset: EditorAsset,
        val recipe: EditRecipe,
    ) : PhotoEditTarget

    /**
     * A recipe edited on its own, over a sample photo. Opening the same [source] with another sample
     * only swaps the photo: the edits, the history and what is still unsaved stay.
     */
    data class Recipe(
        override val asset: EditorAsset,
        val source: RecipeSource,
    ) : PhotoEditTarget
}

/** What the recipe editor opened. Only [Saved] is the user's; the others become theirs when saved. */
sealed interface RecipeSource {
    /** Tells one editing session from another, so that swapping the sample does not start over. */
    val key: String

    data class Saved(val saved: SavedRecipe) : RecipeSource {
        override val key get() = savedKey(saved.id)
    }

    data class BuiltIn(val builtIn: BuiltInRecipe) : RecipeSource {
        override val key get() = builtInKey(builtIn.id)
    }

    /** Each new recipe is its own session: a second one must not reopen what the first left behind. */
    data class New(val id: String = UUID.randomUUID().toString()) : RecipeSource {
        override val key get() = "new:$id"
    }

    companion object {
        fun savedKey(id: String) = "saved:$id"
        fun builtInKey(id: String) = "built-in:$id"

        /**
         * The source a [key] names, from what is at hand. Null while a saved recipe is not in [saved] —
         * not loaded yet, or deleted meanwhile.
         */
        fun fromKey(key: String, saved: List<SavedRecipe>): RecipeSource? = when {
            key.startsWith("saved:") -> saved.firstOrNull { it.id == key.removePrefix("saved:") }?.let(::Saved)
            key.startsWith("built-in:") -> BUILT_IN_RECIPES.firstOrNull { it.id == key.removePrefix("built-in:") }?.let(::BuiltIn)
            key.startsWith("new:") -> New(key.removePrefix("new:"))
            else -> null
        }
    }
}

/** The recipe editor's part of the state; null while editing a photo. */
data class RecipeEditState(
    /** The recipe being edited, once it is the user's. Null for a filter or a new recipe not saved yet. */
    val saved: SavedRecipe?,
    /** What the name dialog proposes when saving something that is not the user's yet. */
    val suggestedName: UiText?,
    val suggestedCollection: UiText?,
    /** The recipe as it was last saved, or as it opened — what "unsaved changes" is measured against. */
    val baseline: EditRecipe,
)

/**
 * The same look, whatever the moment it was touched: undoing back to where the recipe started is not
 * a change, even though every step stamped a new `updatedAt`.
 */
internal fun EditRecipe.sameLookAs(other: EditRecipe): Boolean = copy(updatedAt = other.updatedAt) == other

/**
 * The categories of the panel's bar.
 *
 * There are five, not six: the design system fixes the list as Light, Colour, Detail, Optics, Effects
 * and Masks, and of these the app can do five already — Optics is missing. The curve and HSL, which
 * used to be categories, became tools opened from inside Light and Colour — they are precision
 * instruments, not families of adjustments.
 */
enum class EditorPanel { LIGHT, COLOR, DETAIL, EFFECTS, MASKS }

/**
 * What the bottom panel is showing.
 *
 * [ADJUSTMENTS] is the normal state — the categories and their sliders. The others are modes with
 * their own interface, which replace the panel's content without changing screen.
 */
enum class EditorSheet { ADJUSTMENTS, CURVE, HSL, COLOR_GRADING, CROP, PERSPECTIVE, HISTORY, MASKS, MASK_ADJUSTMENTS }

/**
 * The buttons of the bar under the photo, in its order.
 *
 * Curve and HSL are not here on purpose — they belong to Light and Colour, and that is where they
 * open from.
 */
enum class EditorTool {
    ADJUSTMENTS,
    CROP,
    RECIPES,
    HISTORY,
}

/** The bar's tools. Editing a recipe there is no crop — a frame belongs to one photo, not to a look meant for any. */
internal fun editorTools(recipeMode: Boolean): List<EditorTool> =
    if (recipeMode) EditorTool.entries - EditorTool.CROP else EditorTool.entries

enum class HslColorBand { RED, ORANGE, YELLOW, GREEN, AQUA, BLUE, PURPLE, MAGENTA }
enum class HslComponent { HUE, SATURATION, LUMINANCE }

/**
 * The four wheels of colour grading (step 12).
 *
 * The order is that of the panel's row of icons, and [GLOBAL] comes last because it is not a tonal
 * zone: it tints the whole photo and obeys neither blending nor balance.
 */
enum class ColorGradeWheel { SHADOWS, MIDTONES, HIGHLIGHTS, GLOBAL }

/**
 * The three values of a wheel.
 *
 * They are the same names as HSL, and not by chance: the wheel's handle is the angle and radius of a
 * hue and a saturation, and the slider beside it is a luminance. They are separate enumerations
 * because the ranges do not match — here hue is 0..360 and saturation is never negative.
 */
enum class ColorGradeComponent { HUE, SATURATION, LUMINANCE }

/** The icon of a history entry. Only the name here; the drawing is on the UI side. */
enum class HistoryIcon {
    ORIGINAL, EXPOSURE, CONTRAST, HIGHLIGHTS, SHADOWS, WHITES, BLACKS,
    COLOR, CURVE, HSL, GRADE, DETAIL, EFFECTS, CROP, RECIPE, MASK,
}

/**
 * A step of the history.
 *
 * It keeps the recipe **after** the change, not before: that way tapping an entry restores exactly
 * what it describes, without the user having to think about off-by-one.
 */
data class HistoryEntry(
    val id: String,
    val label: UiText,
    val detail: UiText,
    val valueText: UiText?,
    val icon: HistoryIcon,
    val timestamp: String,
    val recipe: EditRecipe,
)

enum class Adjustment(val neutral: Float, val range: ClosedFloatingPointRange<Float>) {
    EXPOSURE(0f, -5f..5f),
    CONTRAST(0f, -100f..100f),
    HIGHLIGHTS(0f, -100f..100f),
    SHADOWS(0f, -100f..100f),
    WHITES(0f, -100f..100f),
    BLACKS(0f, -100f..100f),
    TEMPERATURE(0f, -100f..100f),
    TINT(0f, -100f..100f),
    VIBRANCE(0f, -100f..100f),
    SATURATION(0f, -100f..100f),
    TEXTURE(0f, -100f..100f),
    CLARITY(0f, -100f..100f),
    DEHAZE(0f, -100f..100f),
    VIGNETTE_AMOUNT(0f, -100f..100f),
    VIGNETTE_MIDPOINT(50f, 0f..100f),
    VIGNETTE_ROUNDNESS(0f, -100f..100f),
    VIGNETTE_FEATHER(50f, 0f..100f),
    GRAIN_AMOUNT(0f, 0f..100f),
    GRAIN_SIZE(25f, 0f..100f),
    GRAIN_ROUGHNESS(50f, 0f..100f),
    GRADE_BLENDING(50f, 0f..100f),
    GRADE_BALANCE(0f, -100f..100f),
    PERSPECTIVE_VERTICAL(0f, -100f..100f),
    PERSPECTIVE_HORIZONTAL(0f, -100f..100f),
    PERSPECTIVE_ASPECT(0f, -100f..100f),
    PERSPECTIVE_SCALE(0f, -50f..100f),
    PERSPECTIVE_OFFSET_X(0f, -100f..100f),
    PERSPECTIVE_OFFSET_Y(0f, -100f..100f),
}

/** The sliders of the perspective sheet, in Lightroom's order. */
val PERSPECTIVE_ADJUSTMENTS = listOf(
    Adjustment.PERSPECTIVE_VERTICAL,
    Adjustment.PERSPECTIVE_HORIZONTAL,
    Adjustment.PERSPECTIVE_ASPECT,
    Adjustment.PERSPECTIVE_SCALE,
    Adjustment.PERSPECTIVE_OFFSET_X,
    Adjustment.PERSPECTIVE_OFFSET_Y,
)

/**
 * The adjustment that has to be off neutral for this one to do anything.
 *
 * Dragging the midpoint of a zero-amount vignette changes no pixel; the interface uses this to
 * disable the slider instead of letting it respond with no visible effect.
 */
fun Adjustment.parent(): Adjustment? = when (this) {
    Adjustment.VIGNETTE_MIDPOINT,
    Adjustment.VIGNETTE_ROUNDNESS,
    Adjustment.VIGNETTE_FEATHER,
    -> Adjustment.VIGNETTE_AMOUNT
    Adjustment.GRAIN_SIZE, Adjustment.GRAIN_ROUGHNESS -> Adjustment.GRAIN_AMOUNT
    else -> null
}

/**
 * The adjustment's name.
 *
 * It lives here, and not next to the slider groups, because the history needs exactly the same text
 * — two tables would give two names for the same thing.
 */
val Adjustment.labelRes: StringResource
    get() = when (this) {
    Adjustment.EXPOSURE -> Res.string.editor_adj_exposure
    Adjustment.CONTRAST -> Res.string.editor_adj_contrast
    Adjustment.HIGHLIGHTS -> Res.string.editor_adj_highlights
    Adjustment.SHADOWS -> Res.string.editor_adj_shadows
    Adjustment.WHITES -> Res.string.editor_adj_whites
    Adjustment.BLACKS -> Res.string.editor_adj_blacks
    Adjustment.TEMPERATURE -> Res.string.editor_adj_temp
    Adjustment.TINT -> Res.string.editor_adj_tint
    Adjustment.VIBRANCE -> Res.string.editor_adj_vibrance
    Adjustment.SATURATION -> Res.string.editor_adj_saturation
    Adjustment.TEXTURE -> Res.string.editor_adj_texture
    Adjustment.CLARITY -> Res.string.editor_adj_clarity
    Adjustment.DEHAZE -> Res.string.editor_adj_dehaze
    Adjustment.VIGNETTE_AMOUNT -> Res.string.editor_adj_vignette
    Adjustment.VIGNETTE_MIDPOINT -> Res.string.editor_adj_vignette_midpoint
    Adjustment.VIGNETTE_ROUNDNESS -> Res.string.editor_adj_vignette_roundness
    Adjustment.VIGNETTE_FEATHER -> Res.string.editor_adj_vignette_feather
    Adjustment.GRAIN_AMOUNT -> Res.string.editor_adj_grain
    Adjustment.GRAIN_SIZE -> Res.string.editor_adj_grain_size
    Adjustment.GRAIN_ROUGHNESS -> Res.string.editor_adj_grain_roughness
    Adjustment.GRADE_BLENDING -> Res.string.editor_adj_grading_blending
    Adjustment.GRADE_BALANCE -> Res.string.editor_adj_grading_balance
    Adjustment.PERSPECTIVE_VERTICAL -> Res.string.editor_adj_perspective_vertical
    Adjustment.PERSPECTIVE_HORIZONTAL -> Res.string.editor_adj_perspective_horizontal
    Adjustment.PERSPECTIVE_ASPECT -> Res.string.editor_adj_perspective_aspect
    Adjustment.PERSPECTIVE_SCALE -> Res.string.editor_adj_perspective_scale
    Adjustment.PERSPECTIVE_OFFSET_X -> Res.string.editor_adj_perspective_offset_x
    Adjustment.PERSPECTIVE_OFFSET_Y -> Res.string.editor_adj_perspective_offset_y
    }

@Composable
fun Adjustment.label(): String = stringResource(labelRes)

private fun Adjustment.historyIcon(): HistoryIcon = when (this) {
    Adjustment.EXPOSURE -> HistoryIcon.EXPOSURE
    Adjustment.CONTRAST -> HistoryIcon.CONTRAST
    Adjustment.HIGHLIGHTS -> HistoryIcon.HIGHLIGHTS
    Adjustment.SHADOWS -> HistoryIcon.SHADOWS
    Adjustment.WHITES -> HistoryIcon.WHITES
    Adjustment.BLACKS -> HistoryIcon.BLACKS
    Adjustment.TEMPERATURE, Adjustment.TINT, Adjustment.VIBRANCE, Adjustment.SATURATION -> HistoryIcon.COLOR
    Adjustment.TEXTURE, Adjustment.CLARITY, Adjustment.DEHAZE -> HistoryIcon.DETAIL
    Adjustment.GRADE_BLENDING, Adjustment.GRADE_BALANCE -> HistoryIcon.GRADE
    in PERSPECTIVE_ADJUSTMENTS -> HistoryIcon.CROP
    else -> HistoryIcon.EFFECTS
}

val HslColorBand.labelRes: StringResource
    get() = when (this) {
        HslColorBand.RED -> Res.string.editor_band_red
        HslColorBand.ORANGE -> Res.string.editor_band_orange
        HslColorBand.YELLOW -> Res.string.editor_band_yellow
        HslColorBand.GREEN -> Res.string.editor_band_green
        HslColorBand.AQUA -> Res.string.editor_band_aqua
        HslColorBand.BLUE -> Res.string.editor_band_blue
        HslColorBand.PURPLE -> Res.string.editor_band_purple
        HslColorBand.MAGENTA -> Res.string.editor_band_magenta
    }

@Composable
fun HslColorBand.historyLabel(): String = stringResource(labelRes)

val HslComponent.labelRes: StringResource
    get() = when (this) {
        HslComponent.HUE -> Res.string.editor_component_hue
        HslComponent.SATURATION -> Res.string.editor_component_saturation
        HslComponent.LUMINANCE -> Res.string.editor_component_luminance
    }

@Composable
fun HslComponent.historyLabel(): String = stringResource(labelRes)

val ColorGradeWheel.labelRes: StringResource
    get() = when (this) {
        ColorGradeWheel.SHADOWS -> Res.string.editor_wheel_shadows
        ColorGradeWheel.MIDTONES -> Res.string.editor_wheel_midtones
        ColorGradeWheel.HIGHLIGHTS -> Res.string.editor_wheel_highlights
        ColorGradeWheel.GLOBAL -> Res.string.editor_wheel_global
    }

@Composable
fun ColorGradeWheel.historyLabel(): String = stringResource(labelRes)

val ColorGradeComponent.labelRes: StringResource
    get() = when (this) {
        ColorGradeComponent.HUE -> Res.string.editor_component_hue
        ColorGradeComponent.SATURATION -> Res.string.editor_component_saturation
        ColorGradeComponent.LUMINANCE -> Res.string.editor_component_luminance
    }

@Composable
fun ColorGradeComponent.historyLabel(): String = stringResource(labelRes)

/** The range of each value of a wheel. Hue wraps around; the other two are scales. */
fun ColorGradeComponent.range(): ClosedFloatingPointRange<Float> = when (this) {
    ColorGradeComponent.HUE -> 0f..360f
    ColorGradeComponent.SATURATION -> 0f..100f
    ColorGradeComponent.LUMINANCE -> -100f..100f
}

/** The value as it appears beside the slider and in the history entry. */
fun ColorGradeComponent.format(value: Float): String = when (this) {
    ColorGradeComponent.HUE -> "%.0f°".format(value)
    ColorGradeComponent.SATURATION -> "%.0f".format(value)
    ColorGradeComponent.LUMINANCE -> "%+.0f".format(value)
}

/** The value as it appears beside the slider and in the history entry. */
fun formatAdjustmentValue(adjustment: Adjustment, value: Float): String = when {
    adjustment == Adjustment.EXPOSURE -> "%+.2f EV".format(value)
    // A range that never goes below zero is a quantity, not a deviation: showing it a sign would only
    // suggest there is a negative side.
    adjustment.range.start >= 0f -> "%.0f".format(value)
    else -> "%+.0f".format(value)
}

data class EditorUiState(
    val asset: EditorAsset? = null,
    val bitmap: Bitmap? = null,
    val recipe: EditRecipe? = null,
    val panel: EditorPanel = EditorPanel.LIGHT,
    val sheet: EditorSheet = EditorSheet.ADJUSTMENTS,
    val selectedHslBand: HslColorBand = HslColorBand.RED,
    val selectedColorGradeWheel: ColorGradeWheel = ColorGradeWheel.SHADOWS,
    val selectedMaskId: String? = null,
    /** "Show mask" pins the red; otherwise it only lights up during the gesture. */
    val maskOverlayPinned: Boolean = false,
    val maskNotice: UiText? = null,
    /** An automatic Upright mode is looking for the photo's lines. */
    val isDetectingUpright: Boolean = false,
    /** Said when an automatic mode found no lines to work on; cleared by the next mode. */
    val uprightNotice: UiText? = null,
    val history: List<HistoryEntry> = emptyList(),
    val historyIndex: Int = 0,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val showOriginal: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isExporting: Boolean = false,
    val exportPhase: UiText? = null,
    val exportMessage: UiText? = null,
    val exportError: UiText? = null,
    val savedRecipes: List<SavedRecipe> = emptyList(),
    val canPasteRecipe: Boolean = false,
    val isRecipeLibraryLoading: Boolean = false,
    val recipeNotice: UiText? = null,
    /** The versions of this photo that lost conflicts with other devices. */
    val conflicts: List<RecipeConflictVersion> = emptyList(),
    /**
     * The saved recipe was made by a newer version of the app: it is not shown with the old pipeline
     * and is not saved over.
     */
    val needsNewerApp: Boolean = false,
    val recipeEdit: RecipeEditState? = null,
    val error: UiText? = null,
) {
    val hasUnsavedRecipeChanges: Boolean
        get() {
            val edit = recipeEdit ?: return false
            val current = recipe ?: return false
            return !current.sameLookAs(edit.baseline)
        }

    val renderParameters: RenderParameters
        get() = recipe?.toRenderParameters() ?: RenderParameters()

    /** Crop is the only mode that needs to turn off zoom and the pager. */
    val isCropping: Boolean get() = sheet == EditorSheet.CROP

    /** Editing a mask: one is selected and the panel is on one of its sheets. */
    val isMaskEditing: Boolean
        get() = selectedMaskId != null &&
            (sheet == EditorSheet.MASKS || sheet == EditorSheet.MASK_ADJUSTMENTS)

    val masks: List<LocalMask> get() = recipe?.masks.orEmpty()

    val selectedMask: LocalMask? get() = selectedMaskId?.let { id -> masks.firstOrNull { it.id == id } }

    /**
     * A slider's value: the selected mask's when there is one and the adjustment is local, and the
     * global one in every other case.
     *
     * It exists so that the slider panel does not need to know whether it is editing a mask — it is
     * the same `AdjustmentPanel` in both cases, and a second copy of it would be a second way for the
     * same slider to behave.
     */
    fun valueOf(adjustment: Adjustment): Float {
        val current = recipe ?: return adjustment.neutral
        val maskId = selectedMaskId
        return if (maskId != null && adjustment.isLocal() && sheet == EditorSheet.MASK_ADJUSTMENTS) {
            current.localAdjustmentValue(maskId, adjustment)
        } else {
            current.adjustmentValue(adjustment)
        }
    }
}

/**
 * The editor, in both apps. On Android it comes through `HiltEditorViewModel`; on desktop, assembled
 * with the app's data layer.
 *
 * @param context the image loader's context: the application's on Android, the only one there is on desktop.
 */
open class EditorViewModel(
    private val context: PlatformContext,
    private val recipes: RecipeRepository,
    private val savedRecipes: SavedRecipeRepository,
    private val derivedAssets: DerivedAssetRepository,
    private val configuration: ConfigurationRepository,
    private val immichApi: ImmichApi,
    private val exporter: EditorExporter,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EditorUiState())
    val state = mutableState.asStateFlow()

    private val history = EditHistory()
    private var editStart: EditRecipe? = null

    /** What is being moved right now, so the entry knows what to call itself when it closes. */
    private var pendingChange: PendingChange? = null
    private var loadJob: Job? = null
    private var conflictsJob: Job? = null
    private var saveJob: Job? = null
    private val geometryMirror = ImmichGeometryMirror(immichApi, configuration)
    private var uprightJob: Job? = null

    /**
     * The lines found in the open photo, kept for as long as it stays open: every automatic mode
     * solves from the same lines, and so do a quarter turn and a mirror. They are in the original
     * image's coordinates, which neither changes.
     */
    private var detectedLines: Pair<Bitmap, List<LineSegment>>? = null
    private var mirrorJob: Job? = null
    /** The recipe whose geometry is waiting to go to Immich, so leaving the photo does not lose it. */
    private var pendingMirror: EditRecipe? = null
    /** The last geometry handed to the mirror, so a change of exposure does not ask the server again. */
    private var mirroredGeometry: Pair<Geometry, Int>? = null
    private var shouldPersist = false
    private var needsNewerApp = false
    private var activeTarget: PhotoEditTarget? = null
    /** The recipe editor's session, kept apart from [activeTarget] so that swapping the sample keeps it. */
    private var recipeSessionKey: String? = null
    private var copiedRecipe: EditRecipe? = null

    /** Only a photo's own edits go to the photo's record; a composition and a recipe keep theirs elsewhere. */
    private val persistsToPhoto: Boolean get() = activeTarget is PhotoEditTarget.Asset

    private sealed interface PendingChange {
        /** The label and the value come from the adjustment itself, once the drag has ended. */
        data class OfAdjustment(val adjustment: Adjustment, val maskId: String? = null) : PendingChange
        data class Fixed(
            val label: UiText,
            val detail: UiText,
            val valueText: UiText?,
            val icon: HistoryIcon,
        ) : PendingChange
    }

    init {
        refreshSavedRecipes()
    }

    fun open(target: PhotoEditTarget) {
        val asset = target.asset
        val sameRecipeSession = target is PhotoEditTarget.Recipe && target.source.key == recipeSessionKey
        val keyMatches = when (val active = activeTarget) {
            is PhotoEditTarget.Asset -> target is PhotoEditTarget.Asset && active.asset.id == asset.id
            is PhotoEditTarget.Composition -> target is PhotoEditTarget.Composition &&
                active.asset.id == asset.id && active.recipe.updatedAt == target.recipe.updatedAt
            is PhotoEditTarget.Recipe -> sameRecipeSession && active.asset.id == asset.id
            null -> false
        }
        if (keyMatches && state.value.bitmap != null) return
        if (sameRecipeSession && state.value.recipe != null) return switchSample(target as PhotoEditTarget.Recipe)
        activeTarget = target
        recipeSessionKey = (target as? PhotoEditTarget.Recipe)?.source?.key
        loadJob?.cancel()
        conflictsJob?.cancel()
        saveJob?.cancel()
        flushGeometryMirror()
        mirroredGeometry = null
        editStart = null
        pendingChange = null
        shouldPersist = false
        val retained = state.value
        mutableState.value = EditorUiState(
            asset = asset,
            isLoading = true,
            savedRecipes = retained.savedRecipes,
            canPasteRecipe = copiedRecipe != null,
            isRecipeLibraryLoading = retained.isRecipeLibraryLoading,
            recipeNotice = retained.recipeNotice,
        )
        loadJob = viewModelScope.launch {
            runCatching {
                coroutineScope {
                    val bitmap = async { loadBitmap(asset) }
                    val stored = async {
                        when (target) {
                            is PhotoEditTarget.Asset -> recipes.get(asset.id)
                            is PhotoEditTarget.Composition -> target.recipe
                            is PhotoEditTarget.Recipe -> when (val source = target.source) {
                                is RecipeSource.Saved -> source.saved.recipe
                                is RecipeSource.BuiltIn -> source.builtIn.recipe
                                is RecipeSource.New -> null
                            }
                        }
                    }
                    val now = Instant.now().toString()
                    val fromStore = stored.await()
                    // A recipe from a newer app never goes through the old pipeline: the original
                    // opens, and nothing is saved over it.
                    needsNewerApp = fromStore != null && fromStore.processVersion > CURRENT_PROCESS_VERSION
                    val storedRecipe = fromStore?.takeIf {
                        !needsNewerApp &&
                            (it.originalChecksum.isBlank() || asset.checksum.isBlank() || it.originalChecksum == asset.checksum)
                    }
                    val recipe = if (target is PhotoEditTarget.Recipe) {
                        // Seen through `rebasedOnto`, a recipe is exactly what applying it to a photo
                        // would give — the sample shows the truth, not the stored record.
                        val base = EditRecipe(assetId = RECIPE_EDIT_ASSET_ID, originalChecksum = "", createdAt = now, updatedAt = now)
                        storedRecipe?.rebasedOnto(base, now) ?: base
                    } else {
                        storedRecipe ?: EditRecipe(
                            assetId = asset.id,
                            originalChecksum = asset.checksum,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                    Triple(bitmap.await(), recipe, storedRecipe != null)
                }
            }.onSuccess { (bitmap, recipe, existed) ->
                shouldPersist = target is PhotoEditTarget.Asset && existed
                // The recipe the photo opens with is the history's zero point, whether or not it has
                // adjustments from an earlier session: this is where "Delete all" goes back to.
                history.reset(
                    HistoryEntry(
                        id = "original",
                        label = uiText(Res.string.editor_original),
                        detail = uiText(if (existed) Res.string.editor_history_saved_recipe else Res.string.editor_history_no_adjustments),
                        valueText = null,
                        icon = HistoryIcon.ORIGINAL,
                        timestamp = Instant.now().toString(),
                        recipe = recipe,
                    ),
                )
                mutableState.update {
                    it.copy(
                        bitmap = bitmap,
                        recipe = recipe,
                        isLoading = false,
                        history = history.all,
                        historyIndex = history.index,
                        canUndo = false,
                        canRedo = false,
                        needsNewerApp = needsNewerApp,
                        recipeNotice = if (needsNewerApp) uiText(Res.string.editor_needs_newer_app) else it.recipeNotice,
                        recipeEdit = (target as? PhotoEditTarget.Recipe)?.source?.let { source -> source.editState(recipe) },
                    )
                }
                if (target is PhotoEditTarget.Asset) {
                    // Opening is when another device's recipe reaches this library's server: the
                    // device that has the key is the one that writes (sync spec, §5.6).
                    if (existed && !needsNewerApp) {
                        scheduleGeometryMirror(recipe, delayMillis = 0)
                    } else {
                        mirroredGeometry = recipe.geometry to recipe.processVersion
                    }
                    conflictsJob = viewModelScope.launch {
                        recipes.conflicts(asset.id).collect { versions -> mutableState.update { it.copy(conflicts = versions) } }
                    }
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(isLoading = false, error = error.toUiText(Res.string.editor_open_failed))
                }
            }
        }
    }

    /** Another sample under the recipe being edited: the photo changes, and nothing else does. */
    private fun switchSample(target: PhotoEditTarget.Recipe) {
        activeTarget = target
        loadJob?.cancel()
        mutableState.update { it.copy(asset = target.asset, bitmap = null, isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            runCatching { loadBitmap(target.asset) }
                .onSuccess { bitmap -> mutableState.update { it.copy(bitmap = bitmap, isLoading = false) } }
                .onFailure { error ->
                    mutableState.update { it.copy(isLoading = false, error = error.toUiText(Res.string.editor_open_failed)) }
                }
        }
    }

    /**
     * Leaving the recipe editor, saved or discarded. Without this, opening the same recipe again would
     * find the session still alive and bring back edits that were thrown away.
     */
    fun closeRecipe() {
        if (activeTarget !is PhotoEditTarget.Recipe) return
        loadJob?.cancel()
        activeTarget = null
        recipeSessionKey = null
        mutableState.update { EditorUiState(savedRecipes = it.savedRecipes, canPasteRecipe = it.canPasteRecipe) }
    }

    /**
     * Saves the recipe editor's work. A recipe that is already the user's is updated where it is; a
     * filter or a new recipe becomes a new one, under [name] and [collection].
     */
    fun saveRecipeEdits(name: String? = null, collection: String? = null) {
        val edit = state.value.recipeEdit ?: return
        val current = state.value.recipe ?: return
        if (needsNewerApp) return
        val now = Instant.now().toString()
        val existing = edit.saved
        val saved = if (existing != null) {
            existing.copy(recipe = current.storedOver(existing.recipe, now), updatedAt = now)
        } else {
            val cleanName = name?.trim().orEmpty()
            if (cleanName.isBlank()) {
                mutableState.update { it.copy(recipeNotice = uiText(Res.string.editor_recipe_name_needed)) }
                return
            }
            SavedRecipe(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                collection = collection?.trim().orEmpty(),
                recipe = current.copy(updatedAt = now),
                createdAt = now,
                updatedAt = now,
            )
        }
        persistRecipeEdit(saved, current, uiText(Res.string.editor_recipe_saved))
    }

    /**
     * Saves what is on screen as a new recipe and goes on editing that one. The recipe it came from
     * stays as it was last saved: the copy is where the unsaved edits go.
     */
    fun duplicateRecipeEdit() {
        val edit = state.value.recipeEdit ?: return
        val current = state.value.recipe ?: return
        if (needsNewerApp) return
        viewModelScope.launch {
            val now = Instant.now().toString()
            val baseName = edit.saved?.name ?: edit.suggestedName?.resolveNow() ?: appString(Res.string.editor_new_recipe)
            val copy = SavedRecipe(
                id = UUID.randomUUID().toString(),
                name = appString(Res.string.editor_recipe_copy_name, baseName),
                collection = edit.saved?.collection
                    ?: edit.suggestedCollection?.resolveNow()
                    ?: appString(Res.string.editor_default_collection),
                recipe = current.copy(updatedAt = now),
                createdAt = now,
                updatedAt = now,
            )
            persistRecipeEdit(copy, current, uiText(Res.string.editor_recipe_duplicated, copy.name))
        }
    }

    /** Deletes the recipe being edited. Only one that is the user's can be: a filter never is. */
    fun deleteRecipeEdit(onDeleted: () -> Unit) {
        val saved = state.value.recipeEdit?.saved ?: return
        viewModelScope.launch {
            runCatching { savedRecipes.delete(saved.id) }
                .onSuccess {
                    refreshSavedRecipes()
                    onDeleted()
                }
                .onFailure { error -> mutableState.update { it.copy(recipeNotice = error.toUiText()) } }
        }
    }

    private fun persistRecipeEdit(saved: SavedRecipe, savedLook: EditRecipe, notice: UiText) {
        viewModelScope.launch {
            runCatching { savedRecipes.save(saved) }
                .onSuccess {
                    refreshSavedRecipes()
                    mutableState.update {
                        it.copy(
                            recipeEdit = it.recipeEdit?.copy(saved = saved, baseline = savedLook),
                            recipeNotice = notice,
                        )
                    }
                }
                .onFailure { error -> mutableState.update { it.copy(recipeNotice = error.toUiText()) } }
        }
    }

    /**
     * Tries opening the photo again after a loading failure. The saved recipe is not lost: it stays
     * in Room and is read again in [open].
     */
    fun retry() {
        val target = activeTarget ?: return
        mutableState.update { it.copy(error = null) }
        open(target)
    }

    fun currentRecipe(): EditRecipe? = state.value.recipe

    /**
     * Masks are the only category that does not open on sliders: it opens on the list, because there
     * are no adjustments to show before there is a region to apply them to. Leaving the category drops
     * the selection, or a global slider would move a mask that is no longer in view.
     */
    fun selectPanel(panel: EditorPanel) = mutableState.update {
        if (panel == EditorPanel.MASKS) {
            it.copy(panel = panel, sheet = EditorSheet.MASKS, selectedMaskId = null, maskOverlayPinned = false)
        } else {
            it.copy(
                panel = panel,
                sheet = EditorSheet.ADJUSTMENTS,
                selectedMaskId = null,
                maskOverlayPinned = false,
            )
        }
    }

    fun selectSheet(sheet: EditorSheet) = mutableState.update { it.copy(sheet = sheet) }

    fun selectHslBand(band: HslColorBand) = mutableState.update { it.copy(selectedHslBand = band) }

    fun selectColorGradeWheel(wheel: ColorGradeWheel) =
        mutableState.update { it.copy(selectedColorGradeWheel = wheel) }
    fun showOriginal(show: Boolean) = mutableState.update { it.copy(showOriginal = show) }

    /**
     * A single slider, two destinations.
     *
     * With a mask open the value goes into it; otherwise it goes to the recipe. The routing lives here
     * and not in the interface so the slider panel keeps not knowing masks exist.
     */
    fun updateAdjustment(adjustment: Adjustment, value: Float) {
        val current = state.value.recipe ?: return
        val snapshot = state.value
        val maskId = snapshot.selectedMaskId
            ?.takeIf { adjustment.isLocal() && snapshot.sheet == EditorSheet.MASK_ADJUSTMENTS }
            ?.takeIf { current.mask(it) != null }
        val clamped = value.coerceIn(adjustment.range.start, adjustment.range.endInclusive).let { bounded ->
            // With the crop constrained the frame cannot shrink below the cover: the slider stops at
            // zero instead of moving with no effect.
            if (adjustment == Adjustment.PERSPECTIVE_SCALE && current.geometry.perspective.constrainCrop) {
                bounded.coerceAtLeast(0f)
            } else {
                bounded
            }
        }
        val existing = if (maskId != null) {
            current.localAdjustmentValue(maskId, adjustment)
        } else {
            current.adjustmentValue(adjustment)
        }
        if (existing == clamped) return
        if (editStart == null) {
            editStart = current
            pendingChange = PendingChange.OfAdjustment(adjustment, maskId)
        }
        // The dormant geometry is cleared first and the change applied after: the other way round, a
        // perspective slider was the one change this step threw away.
        val active = current.copy(geometry = current.geometry.activeAtProcess(current.processVersion))
        val edited = if (maskId != null) {
            active.withLocalAdjustment(maskId, adjustment, clamped)
        } else {
            active.withAdjustment(adjustment, clamped)
        }
        val updated = edited.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            updatedAt = Instant.now().toString(),
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true) }
        scheduleSave(updated)
    }

    /**
     * Closes the drag and turns it into a history entry.
     *
     * One entry per gesture, not per frame: it is here, when the finger lifts, that the final value
     * is known and that the change deserves to be recorded.
     */
    fun finishAdjustment() {
        val start = editStart ?: return
        val current = state.value.recipe ?: return
        val change = pendingChange
        editStart = null
        pendingChange = null
        if (start == current || change == null) return
        val entry = when (change) {
            is PendingChange.OfAdjustment -> HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = UiText.Resource(change.adjustment.labelRes),
                detail = change.maskId?.let { id -> current.mask(id)?.name?.let { uiText(Res.string.editor_history_mask_named, it) } }
                    ?: uiText(Res.string.editor_history_manual),
                valueText = formatAdjustmentValue(
                    change.adjustment,
                    change.maskId?.let { current.localAdjustmentValue(it, change.adjustment) }
                        ?: current.adjustmentValue(change.adjustment),
                ).asUiText(),
                icon = if (change.maskId != null) HistoryIcon.MASK else change.adjustment.historyIcon(),
                timestamp = Instant.now().toString(),
                recipe = current,
            )
            is PendingChange.Fixed -> HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = change.label,
                detail = change.detail,
                valueText = change.valueText,
                icon = change.icon,
                timestamp = Instant.now().toString(),
                recipe = current,
            )
        }
        pushHistory(entry)
    }

    fun resetAdjustment(adjustment: Adjustment) {
        updateAdjustment(adjustment, adjustment.neutral)
        finishAdjustment()
    }

    fun addCurvePoint(x: Int, y: Int) {
        val current = state.value.recipe ?: return
        val points = current.editableCurvePoints()
        val updatedPoints = points.withAddedCurvePoint(x, y)
        if (updatedPoints == points) return
        beginCurveEdit(current)
        applyCurvePoints(current, updatedPoints)
    }

    fun moveCurvePoint(index: Int, x: Int, y: Int) {
        val current = state.value.recipe ?: return
        val points = current.editableCurvePoints()
        if (index !in points.indices) return
        beginCurveEdit(current)
        val updated = points.withMovedCurvePoint(index, x, y)
        if (updated == points) return
        applyCurvePoints(current, updated)
    }

    fun removeCurvePoint(index: Int) {
        val current = state.value.recipe ?: return
        val points = current.editableCurvePoints()
        val updatedPoints = points.withRemovedCurvePoint(index)
        if (updatedPoints == points) return
        beginCurveEdit(current)
        applyCurvePoints(current, updatedPoints)
        finishAdjustment()
    }

    fun resetCurve() {
        val current = state.value.recipe ?: return
        val neutral = listOf(
            eu.studio742.imago.core.model.CurvePoint(0, 0),
            eu.studio742.imago.core.model.CurvePoint(255, 255),
        )
        if (current.editableCurvePoints() == neutral) return
        beginCurveEdit(current, detail = uiText(Res.string.editor_history_reset_f))
        applyCurvePoints(current, neutral)
        finishAdjustment()
    }

    private fun beginCurveEdit(current: EditRecipe, detail: UiText = uiText(Res.string.editor_tone_curve)) {
        if (editStart != null) return
        editStart = current
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_chip_curve),
            detail = detail,
            valueText = null,
            icon = HistoryIcon.CURVE,
        )
    }

    private fun applyCurvePoints(
        current: EditRecipe,
        points: List<eu.studio742.imago.core.model.CurvePoint>,
    ) {
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            toneCurve = current.toneCurve.copy(rgb = points),
            updatedAt = Instant.now().toString(),
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true) }
        scheduleSave(updated)
    }

    fun updateHsl(band: HslColorBand, component: HslComponent, value: Float) {
        val current = state.value.recipe ?: return
        val clamped = value.coerceIn(-100f, 100f)
        if (current.hslBand(band).component(component) == clamped) return
        if (editStart == null) {
            editStart = current
            pendingChange = PendingChange.Fixed(
                label = uiText(Res.string.editor_history_hsl, UiText.Resource(band.labelRes)),
                detail = UiText.Resource(component.labelRes),
                valueText = "%+.0f".format(clamped).asUiText(),
                icon = HistoryIcon.HSL,
            )
        }
        val changedBand = current.hslBand(band).withComponent(component, clamped)
        val updated = current.copy(
            hsl = current.hsl.withBand(band, changedBand),
            updatedAt = Instant.now().toString(),
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true) }
        scheduleSave(updated)
    }

    fun resetHsl(band: HslColorBand, component: HslComponent) {
        updateHsl(band, component, 0f)
        finishAdjustment()
    }

    /**
     * A value of a colour grading wheel.
     *
     * Hue wraps around instead of hitting the end: going past 360° has to continue through red, which
     * is what the circle shows. The other two are scales and stay limited.
     */
    fun updateColorGrade(wheel: ColorGradeWheel, component: ColorGradeComponent, value: Float) {
        val current = state.value.recipe ?: return
        val clamped = when (component) {
            ColorGradeComponent.HUE -> positiveHue(value)
            else -> value.coerceIn(component.range().start, component.range().endInclusive)
        }
        if (current.colorGradeWheel(wheel).component(component) == clamped) return
        if (editStart == null) {
            editStart = current
            pendingChange = PendingChange.Fixed(
                label = uiText(Res.string.editor_history_grading, UiText.Resource(wheel.labelRes)),
                detail = UiText.Resource(component.labelRes),
                valueText = component.format(clamped).asUiText(),
                icon = HistoryIcon.GRADE,
            )
        }
        val changed = current.colorGradeWheel(wheel).withComponent(component, clamped)
        applyColorGrading(current, current.colorGrading.withWheel(wheel, changed))
    }

    /**
     * The handle dragged inside the circle: hue and saturation at once.
     *
     * It is worth a single history entry because it was a single gesture. Two — one per value — filled
     * the timeline with pairs nobody wants to undo separately.
     */
    fun updateColorGradeHandle(wheel: ColorGradeWheel, hue: Float, saturation: Float) {
        val current = state.value.recipe ?: return
        val existing = current.colorGradeWheel(wheel)
        val newHue = positiveHue(hue)
        val newSaturation = saturation.coerceIn(0f, 100f)
        if (existing.hue == newHue && existing.saturation == newSaturation) return
        if (editStart == null) {
            editStart = current
            pendingChange = PendingChange.Fixed(
                label = uiText(Res.string.editor_history_grading, UiText.Resource(wheel.labelRes)),
                detail = uiText(Res.string.editor_history_hue_saturation),
                valueText = "%.0f° · %.0f".format(newHue, newSaturation).asUiText(),
                icon = HistoryIcon.GRADE,
            )
        }
        val changed = existing.copy(hue = newHue, saturation = newSaturation)
        applyColorGrading(current, current.colorGrading.withWheel(wheel, changed))
    }

    /** A whole wheel back to neutral, in a single history step. */
    fun resetColorGradeWheel(wheel: ColorGradeWheel) {
        val current = state.value.recipe ?: return
        if (current.colorGradeWheel(wheel) == ColorWheel()) return
        beginColorGradingEdit(current, UiText.Resource(wheel.labelRes))
        applyColorGrading(current, current.colorGrading.withWheel(wheel, ColorWheel()))
        finishAdjustment()
    }

    /** The four wheels and the two controls at once, which is what the panel's "Reset" promises. */
    fun resetColorGrading() {
        val current = state.value.recipe ?: return
        if (current.colorGrading == ColorGrading()) return
        beginColorGradingEdit(current, uiText(Res.string.editor_history_all_wheels))
        applyColorGrading(current, ColorGrading())
        finishAdjustment()
    }

    private fun beginColorGradingEdit(current: EditRecipe, detail: UiText) {
        if (editStart != null) return
        editStart = current
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_color_grading),
            detail = detail,
            valueText = uiText(Res.string.editor_history_reset_f),
            icon = HistoryIcon.GRADE,
        )
    }

    private fun applyColorGrading(current: EditRecipe, grading: ColorGrading) {
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            colorGrading = grading,
            updatedAt = Instant.now().toString(),
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true) }
        scheduleSave(updated)
    }

    fun rotateClockwise() {
        val current = state.value.recipe ?: return
        val updated = current.rotatedClockwise()
        updateGeometry(
            updated = updated,
            detail = uiText(Res.string.editor_history_rotated),
            valueText = "${updated.geometry.rotation}°".asUiText(),
        )
    }

    fun selectCropAspect(id: String?) {
        val current = state.value.recipe ?: return
        val bitmap = state.value.bitmap ?: return
        if (id == null) {
            updateGeometry(
                updated = current.copy(geometry = current.geometry.copy(aspectLock = null)),
                detail = uiText(Res.string.editor_aspect_free_state),
            )
            return
        }
        val ratio = lockedCropRatio(
            aspectLock = id,
            imageWidth = bitmap.pixelWidth,
            imageHeight = bitmap.pixelHeight,
            rotation = current.geometry.rotation,
        ) ?: return
        val cropRect =
            centeredCropRect(
                imageWidth = bitmap.pixelWidth,
                imageHeight = bitmap.pixelHeight,
                rotation = current.geometry.rotation,
                aspectRatio = ratio,
            )
        updateGeometry(
            updated = current.copy(
                geometry = current.geometry.copy(
                    cropRect = cropRect,
                    aspectLock = id,
                ),
            ),
            detail = uiText(Res.string.editor_history_aspect_locked),
            valueText = id.asUiText(),
        )
    }

    fun resetCrop() {
        val current = state.value.recipe ?: return
        updateGeometry(
            updated = current.copy(
                geometry = current.geometry.copy(
                    cropRect = eu.studio742.imago.core.model.CropRect(),
                    aspectLock = null,
                ),
            ),
            detail = uiText(Res.string.editor_history_crop_reset),
        )
    }

    fun updateStraighten(value: Float) {
        val current = state.value.recipe ?: return
        val clamped = value.coerceIn(-45f, 45f)
        val effectiveCurrent = if (current.processVersion >= 7) current.geometry.straighten else 0f
        if (effectiveCurrent == clamped) return
        if (editStart == null) {
            editStart = current
            pendingChange = PendingChange.Fixed(
                label = uiText(Res.string.editor_straighten),
                detail = uiText(Res.string.editor_history_geometry),
                valueText = "%+.1f°".format(clamped).asUiText(),
                icon = HistoryIcon.CROP,
            )
        }
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion).copy(straighten = clamped),
            updatedAt = Instant.now().toString(),
            derivedAssetId = null,
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true) }
        scheduleSave(updated)
    }

    fun resetStraighten() {
        updateStraighten(0f)
        finishAdjustment()
    }

    fun beginCropGesture() {
        if (editStart != null) return
        editStart = state.value.recipe
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_history_crop),
            detail = uiText(Res.string.editor_history_geometry),
            valueText = null,
            icon = HistoryIcon.CROP,
        )
    }

    fun updateCropRect(cropRect: eu.studio742.imago.core.model.CropRect) {
        val current = state.value.recipe ?: return
        if (current.geometry.cropRect == cropRect) return
        beginCropGesture()
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion).copy(cropRect = cropRect),
            updatedAt = Instant.now().toString(),
            derivedAssetId = null,
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true) }
        scheduleSave(updated)
    }

    fun finishCropGesture() = finishAdjustment()

    fun toggleMirrorHorizontal() {
        val current = state.value.recipe ?: return
        updateGeometry(
            updated = current.copy(
                geometry = current.geometry.copy(mirrorH = !current.geometry.mirrorH),
            ),
            detail = uiText(Res.string.editor_history_flip_horizontal),
        )
    }

    fun toggleMirrorVertical() {
        val current = state.value.recipe ?: return
        updateGeometry(
            updated = current.copy(
                geometry = current.geometry.copy(mirrorV = !current.geometry.mirrorV),
            ),
            detail = uiText(Res.string.editor_history_flip_vertical),
        )
    }

    /** Lightroom's *Constrain Crop*: on, the frame never shows beyond the photo; off, that shows white. */
    fun setConstrainCrop(enabled: Boolean) {
        val current = state.value.recipe ?: return
        val geometry = current.geometry.activeAtProcess(current.processVersion)
        updateGeometry(
            updated = current.copy(
                geometry = geometry.copy(
                    perspective = geometry.perspective.copy(
                        constrainCrop = enabled,
                        scale = if (enabled) geometry.perspective.scale.coerceAtLeast(0f) else geometry.perspective.scale,
                    ),
                ),
            ),
            detail = uiText(if (enabled) Res.string.editor_history_constrain_on else Res.string.editor_history_constrain_off),
        )
    }

    /**
     * Lightroom's Upright modes that exist so far: off, and guided. Off forgets what the mode found and
     * its guides; guided keeps the guides there are and solves again.
     */
    fun setUprightMode(mode: String) {
        if (mode in AUTOMATIC_UPRIGHT_MODES) return applyAutomaticUpright(mode)
        uprightJob?.cancel()
        mutableState.update { it.copy(isDetectingUpright = false, uprightNotice = null) }
        val current = state.value.recipe ?: return
        val geometry = current.geometry.activeAtProcess(current.processVersion)
        val updated = if (mode == UPRIGHT_GUIDED) {
            current.copy(geometry = geometry.copy(perspective = geometry.perspective.copy(upright = UPRIGHT_GUIDED)))
                .withSolvedGuides(geometry.perspective.guides)
        } else {
            current.copy(
                geometry = geometry.copy(
                    perspective = geometry.perspective.copy(
                        upright = UPRIGHT_OFF,
                        uprightRoll = 0f,
                        uprightPitch = 0f,
                        uprightYaw = 0f,
                        guides = emptyList(),
                    ),
                ),
            )
        }
        updateGeometry(
            updated = updated,
            detail = uiText(if (mode == UPRIGHT_GUIDED) Res.string.editor_history_upright_guided else Res.string.editor_history_upright_off),
        )
    }

    /**
     * An automatic mode: the photo's lines are found once — off the main thread, on a reduced copy —
     * and the mode's angles solved from them and stored. A photo without the lines the mode needs
     * keeps the mode chosen with nothing corrected, and the panel says why.
     */
    private fun applyAutomaticUpright(mode: String) {
        val bitmap = state.value.bitmap ?: return
        uprightJob?.cancel()
        mutableState.update { it.copy(isDetectingUpright = true, uprightNotice = null) }
        uprightJob = viewModelScope.launch {
            val lines = linesOf(bitmap)
            val current = state.value.recipe ?: return@launch
            val geometry = current.geometry.activeAtProcess(current.processVersion)
            val chosen = current.copy(
                geometry = geometry.copy(perspective = geometry.perspective.copy(upright = mode, guides = emptyList())),
            )
            val (solved, found) = chosen.withAutomaticUpright(lines)
            mutableState.update {
                it.copy(
                    isDetectingUpright = false,
                    uprightNotice = if (found) null else uiText(Res.string.editor_upright_no_lines),
                )
            }
            updateGeometry(updated = solved, detail = uiText(uprightModeName(mode)))
        }
    }

    private suspend fun linesOf(bitmap: Bitmap): List<LineSegment> {
        detectedLines?.takeIf { it.first === bitmap }?.let { return it.second }
        val lines = withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            val reduced = RecipePixels.reduce(bitmap.toPixelBuffer(), UPRIGHT_DETECTION_SIDE)
            detectLineSegments(reduced).also { found ->
                System.err.println(
                    "[ImmichRoom/upright] ${found.size} lines in ${reduced.width}x${reduced.height} " +
                        "in ${(System.nanoTime() - started) / 1_000_000} ms",
                )
            }
        }
        detectedLines = bitmap to lines
        return lines
    }

    /** The automatic mode's angles from these lines, and whether the lines were enough for it. */
    private fun EditRecipe.withAutomaticUpright(lines: List<LineSegment>): Pair<EditRecipe, Boolean> {
        val bitmap = state.value.bitmap ?: return this to false
        val geometry = this.geometry.activeAtProcess(processVersion)
        val draft = copy(processVersion = CURRENT_PROCESS_VERSION, geometry = geometry)
        val angles = solveAutoUpright(
            draft.toRenderParameters().frameGeometry(bitmap.pixelWidth, bitmap.pixelHeight),
            lines,
            geometry.perspective.upright,
        )
        val perspective = geometry.perspective.copy(
            uprightRoll = angles?.roll ?: 0f,
            uprightPitch = angles?.pitch ?: 0f,
            uprightYaw = angles?.yaw ?: 0f,
        )
        return draft.copy(geometry = geometry.copy(perspective = perspective)) to (angles != null)
    }

    fun beginGuideGesture() {
        if (editStart != null) return
        editStart = state.value.recipe
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_history_upright),
            detail = uiText(Res.string.editor_history_guide),
            valueText = null,
            icon = HistoryIcon.CROP,
        )
    }

    /**
     * The guides while a finger moves one. Nothing is solved yet: solving moves the photo, and a
     * photo moving under the finger would take the guide away from the line it is following.
     */
    fun updateGuides(guides: List<UprightGuide>) {
        val current = state.value.recipe ?: return
        if (current.geometry.perspective.guides == guides) return
        beginGuideGesture()
        val geometry = current.geometry.activeAtProcess(current.processVersion)
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = geometry.copy(perspective = geometry.perspective.copy(guides = guides.take(MAX_UPRIGHT_GUIDES))),
            updatedAt = Instant.now().toString(),
            derivedAssetId = null,
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true) }
        scheduleSave(updated)
    }

    /** The finger lifted: a guide too short to have a direction is dropped, and the rest are solved. */
    fun finishGuideGesture() {
        val current = state.value.recipe ?: return
        val bitmap = state.value.bitmap
        val guides = current.geometry.perspective.guides.filter { guide ->
            bitmap == null || hypot(
                (guide.x2 - guide.x1) * bitmap.pixelWidth,
                (guide.y2 - guide.y1) * bitmap.pixelHeight,
            ) >= MIN_GUIDE_PIXELS
        }
        val solved = current.withSolvedGuides(guides).copy(updatedAt = Instant.now().toString())
        if (editStart?.geometry == solved.geometry) {
            editStart = null
            pendingChange = null
            mutableState.update { it.copy(recipe = solved) }
            return
        }
        mutableState.update { it.copy(recipe = solved, isSaving = true) }
        scheduleSave(solved)
        finishAdjustment()
    }

    fun removeLastGuide() {
        val current = state.value.recipe ?: return
        val guides = current.geometry.perspective.guides
        if (guides.isEmpty()) return
        updateGeometry(updated = current.withSolvedGuides(guides.dropLast(1)), detail = uiText(Res.string.editor_history_guide_removed))
    }

    fun clearGuides() {
        val current = state.value.recipe ?: return
        if (current.geometry.perspective.guides.isEmpty()) return
        updateGeometry(updated = current.withSolvedGuides(emptyList()), detail = uiText(Res.string.editor_history_guides_cleared))
    }

    /**
     * The guided mode's angles for these guides, against the rest of this recipe's geometry. Without
     * the photo's dimensions there is nothing to solve against, and the recipe stays as it was.
     */
    private fun EditRecipe.withSolvedGuides(guides: List<UprightGuide>): EditRecipe {
        val bitmap = state.value.bitmap ?: return this
        val geometry = this.geometry.activeAtProcess(processVersion)
        val perspective = geometry.perspective.copy(guides = guides)
        val draft = copy(processVersion = CURRENT_PROCESS_VERSION, geometry = geometry.copy(perspective = perspective))
        val angles = solveGuidedUpright(draft.toRenderParameters().frameGeometry(bitmap.pixelWidth, bitmap.pixelHeight), guides)
        return draft.copy(
            geometry = draft.geometry.copy(
                perspective = perspective.copy(uprightRoll = angles.roll, uprightPitch = angles.pitch, uprightYaw = angles.yaw),
            ),
        )
    }

    fun resetPerspective() {
        val current = state.value.recipe ?: return
        updateGeometry(
            updated = current.copy(geometry = current.geometry.copy(perspective = Perspective())),
            detail = uiText(Res.string.editor_history_perspective_reset),
        )
    }

    fun resetGeometry() {
        val current = state.value.recipe ?: return
        updateGeometry(
            updated = current.copy(geometry = eu.studio742.imago.core.model.Geometry()),
            detail = uiText(Res.string.editor_history_geometry_reset),
        )
    }

    fun copyCurrentRecipe() {
        copiedRecipe = state.value.recipe ?: return
        mutableState.update { it.copy(canPasteRecipe = true, recipeNotice = uiText(Res.string.editor_recipe_copied)) }
    }

    fun pasteCopiedRecipe() {
        val source = copiedRecipe ?: return
        applyReusableRecipe(source, uiText(Res.string.editor_recipe_pasted))
    }

    fun applySavedRecipe(id: String) {
        val saved = state.value.savedRecipes.firstOrNull { it.id == id } ?: return
        applyReusableRecipe(saved.recipe, uiText(Res.string.editor_recipe_applied, saved.name), label = saved.name.asUiText())
        // The "Recent" tab only exists because this is recorded; without a stamp the recipe would
        // never get there, however much it was used.
        viewModelScope.launch {
            runCatching { savedRecipes.save(saved.copy(usedAt = Instant.now().toString())) }
            refreshSavedRecipes()
        }
    }

    /** Applies one of the app's presets. They do not live in Room and have nothing to record. */
    fun applyBuiltInRecipe(id: String) {
        val builtIn = BUILT_IN_RECIPES.firstOrNull { it.id == id } ?: return
        applyReusableRecipe(builtIn.recipe, uiText(Res.string.editor_filter_applied, builtIn.nameText()), label = builtIn.nameText())
    }

    fun toggleSavedRecipeFavorite(id: String) {
        val saved = state.value.savedRecipes.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            runCatching {
                savedRecipes.save(
                    saved.copy(isFavorite = !saved.isFavorite, updatedAt = Instant.now().toString()),
                )
            }
            refreshSavedRecipes()
        }
    }

    fun saveCurrentRecipe(name: String, collection: String) {
        val current = state.value.recipe ?: return
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            mutableState.update { it.copy(recipeNotice = uiText(Res.string.editor_recipe_name_needed)) }
            return
        }
        val now = Instant.now().toString()
        val saved = SavedRecipe(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            // The default collection comes already written in the person's language (RecipeDetailsDialog).
            collection = collection.trim(),
            recipe = current,
            createdAt = now,
            updatedAt = now,
        )
        viewModelScope.launch {
            runCatching { savedRecipes.save(saved) }
                .onSuccess {
                    refreshSavedRecipes()
                    mutableState.update { it.copy(recipeNotice = uiText(Res.string.editor_recipe_saved)) }
                }
                .onFailure { error -> mutableState.update { it.copy(recipeNotice = error.toUiText()) } }
        }
    }

    fun updateSavedRecipe(id: String, name: String, collection: String) {
        val existing = state.value.savedRecipes.firstOrNull { it.id == id } ?: return
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val updated = existing.copy(
            name = cleanName,
            collection = collection.trim(),
            updatedAt = Instant.now().toString(),
        )
        viewModelScope.launch {
            runCatching { savedRecipes.save(updated) }
                .onSuccess { refreshSavedRecipes() }
                .onFailure { error -> mutableState.update { it.copy(recipeNotice = error.toUiText()) } }
        }
    }

    fun deleteSavedRecipe(id: String) {
        val name = state.value.savedRecipes.firstOrNull { it.id == id }?.name ?: return
        viewModelScope.launch {
            runCatching { savedRecipes.delete(id) }
                .onSuccess {
                    refreshSavedRecipes()
                    mutableState.update { it.copy(recipeNotice = uiText(Res.string.editor_recipe_deleted, name)) }
                }
                .onFailure { error -> mutableState.update { it.copy(recipeNotice = error.toUiText()) } }
        }
    }

    fun consumeRecipeNotice() = mutableState.update { it.copy(recipeNotice = null) }

    fun undo() = applyHistoryMove { history.undo() }

    fun redo() = applyHistoryMove { history.redo() }

    /** Goes back to the state of the given entry. */
    fun restoreHistory(index: Int) = applyHistoryMove { history.restore(index) }

    /** "Delete all": goes back to the zero point and forgets the path. */
    fun clearHistory() = applyHistoryMove { history.clear() }

    private fun applyHistoryMove(move: () -> EditRecipe?) {
        val restored = move() ?: return
        editStart = null
        pendingChange = null
        setRecipeAndSave(restored.copy(updatedAt = Instant.now().toString()))
    }

    private fun pushHistory(entry: HistoryEntry) {
        history.push(entry)
        updateHistoryState()
    }

    fun saveNow() {
        if (!persistsToPhoto || needsNewerApp) return
        flushGeometryMirror()
        if (!shouldPersist) return
        saveJob?.cancel()
        state.value.recipe?.let { recipe ->
            viewModelScope.launch {
                runCatching { recipes.save(recipe) }
                    .onSuccess { mutableState.update { it.copy(isSaving = false) } }
            }
        }
    }

    fun exportToGallery() = export { asset, jpeg, _ ->
        exporter.saveToDevice(jpeg, exportFileName(asset), asset.fileCreatedAt)
    }

    fun exportToImmich(targetLibraryId: String) = export(
        // Before rendering, which takes seconds on a large original: a key without the upload
        // permission hears it on the tap.
        before = {
            immichApi.requirePermission(configuration.source(targetLibraryId).connection(), ImmichKeyPermissions.UPLOAD_ASSET)
        },
    ) { asset, jpeg, recipe ->
        val reference = eu.studio742.imago.core.model.AssetReference.parse(asset.id)
        val connection = configuration.source(targetLibraryId).connection()
        configuration.lastExportLibraryId = targetLibraryId
        if (reference.libraryId != targetLibraryId) {
            immichApi.uploadAsset(connection, jpeg, exportFileName(asset), "image/jpeg", asset.fileCreatedAt)
            return@export uiText(Res.string.editor_export_sent_to, configuration.source(targetLibraryId).name)
        }
        val result = immichApi.exportEditedAsset(
            connection = connection,
            originalAssetId = reference.localId,
            jpeg = jpeg,
            fileName = exportFileName(asset),
            fileCreatedAt = asset.fileCreatedAt,
        )
        if (result.assetId != reference.localId) {
            // Without this record, the export shows up again in the grid as an independent photo on
            // the library's next read.
            derivedAssets.record(asset.id, eu.studio742.imago.core.model.AssetReference(targetLibraryId, result.assetId).encode())
            val saved = recipe.copy(derivedAssetId = result.assetId, updatedAt = Instant.now().toString())
            recipes.save(saved)
            shouldPersist = true
            mutableState.update { it.copy(recipe = saved, isSaving = false) }
        }
        when {
            result.status == "duplicate" -> uiText(Res.string.editor_export_duplicate)
            result.stackedWithOriginal -> uiText(Res.string.editor_export_stacked)
            result.stackingFailed -> uiText(Res.string.editor_export_not_stacked)
            else -> uiText(Res.string.editor_export_sent)
        }
    }

    fun consumeExportResult() = mutableState.update { it.copy(exportMessage = null, exportError = null) }
    fun reportExportError(message: UiText) = mutableState.update { it.copy(exportError = message) }

    private fun export(
        before: suspend () -> Unit = {},
        block: suspend (EditorAsset, File, EditRecipe) -> UiText?,
    ) {
        val snapshot = state.value
        val asset = snapshot.asset ?: return
        snapshot.bitmap ?: return
        val recipe = snapshot.recipe ?: return
        if (snapshot.isExporting) return
        mutableState.update {
            it.copy(
                isExporting = true,
                exportPhase = uiText(Res.string.editor_phase_preparing),
                exportMessage = null,
                exportError = null,
            )
        }
        viewModelScope.launch {
            runCatching {
                before()
                val jpeg = exporter.renderJpeg(asset, recipe) { phase ->
                    mutableState.update { it.copy(exportPhase = phase) }
                }
                try {
                    block(asset, jpeg, recipe)
                } finally {
                    jpeg.delete()
                }
            }.onSuccess { message ->
                mutableState.update { it.copy(isExporting = false, exportPhase = null, exportMessage = message) }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isExporting = false,
                        exportPhase = null,
                        exportError = error.toUiText(Res.string.editor_export_failed),
                    )
                }
            }
        }
    }

    private fun setRecipeAndSave(recipe: EditRecipe) {
        mutableState.update { it.copy(recipe = recipe, isSaving = true) }
        updateHistoryState()
        scheduleSave(recipe)
    }

    /**
     * Applies another device's version that lost a conflict, as a new history step: undoing goes back
     * to the version that won.
     */
    fun applyConflictVersion(id: Long) {
        val version = state.value.conflicts.firstOrNull { it.id == id } ?: return
        val current = state.value.recipe ?: return
        editStart = null
        pendingChange = null
        val now = Instant.now().toString()
        val applied = version.recipe.copy(assetId = current.assetId, originalChecksum = current.originalChecksum, updatedAt = now)
        setRecipeAndSave(applied)
        pushHistory(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = uiText(Res.string.editor_history_version_from, version.deviceName),
                detail = uiText(Res.string.editor_history_recovered_conflict),
                valueText = null,
                icon = HistoryIcon.RECIPE,
                timestamp = now,
                recipe = applied,
            ),
        )
        mutableState.update { it.copy(recipeNotice = uiText(Res.string.editor_version_applied, version.deviceName)) }
    }

    private fun applyReusableRecipe(source: EditRecipe, message: UiText, label: UiText = uiText(Res.string.editor_history_recipe)) {
        val current = state.value.recipe ?: return
        editStart = null
        pendingChange = null
        val applied = source.rebasedOnto(current, Instant.now().toString())
        setRecipeAndSave(applied)
        pushHistory(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = label,
                detail = uiText(Res.string.editor_history_recipe_applied),
                valueText = null,
                icon = HistoryIcon.RECIPE,
                timestamp = Instant.now().toString(),
                recipe = applied,
            ),
        )
        mutableState.update { it.copy(recipeNotice = message) }
    }

    // --- masks ---------------------------------------------------------------------------------

    fun selectMask(maskId: String?) = mutableState.update {
        it.copy(
            selectedMaskId = maskId,
            sheet = if (maskId == null) EditorSheet.MASKS else EditorSheet.MASK_ADJUSTMENTS,
            maskOverlayPinned = if (maskId == null) false else it.maskOverlayPinned,
        )
    }

    fun showMaskList() = mutableState.update {
        it.copy(sheet = EditorSheet.MASKS, selectedMaskId = null, maskOverlayPinned = false)
    }

    fun setMaskOverlayPinned(pinned: Boolean) = mutableState.update { it.copy(maskOverlayPinned = pinned) }

    fun dismissMaskNotice() = mutableState.update { it.copy(maskNotice = null) }

    /**
     * Creates a mask and opens it right away on the adjustments: a mask without adjustments does
     * nothing, and leaving the user on the list after creating it meant asking one more tap to get there.
     */
    fun addMask(shape: MaskShape) {
        val current = state.value.recipe ?: return
        val name = defaultMaskName(shape, current.masks)
        val (updated, id) = current.withNewMask(shape, name)
        if (id == null) {
            mutableState.update {
                it.copy(maskNotice = uiText(Res.string.editor_masks_max, MAX_LOCAL_MASKS))
            }
            return
        }
        commitMask(updated, detail = uiText(Res.string.editor_history_mask_created), valueText = name.asUiText())
        mutableState.update {
            it.copy(selectedMaskId = id, sheet = EditorSheet.MASK_ADJUSTMENTS, maskOverlayPinned = true)
        }
    }

    fun deleteMask(maskId: String) {
        val current = state.value.recipe ?: return
        val name = current.mask(maskId)?.name ?: return
        commitMask(current.withoutMask(maskId), detail = uiText(Res.string.editor_history_mask_removed), valueText = name.asUiText())
        mutableState.update {
            if (it.selectedMaskId == maskId) {
                it.copy(selectedMaskId = null, sheet = EditorSheet.MASKS, maskOverlayPinned = false)
            } else {
                it
            }
        }
    }

    fun setMaskEnabled(maskId: String, enabled: Boolean) {
        val current = state.value.recipe ?: return
        commitMask(
            current.withMask(maskId) { it.copy(enabled = enabled) },
            detail = uiText(if (enabled) Res.string.editor_history_mask_enabled else Res.string.editor_history_mask_disabled),
            valueText = current.mask(maskId)?.name?.asUiText(),
        )
    }

    fun setMaskInverted(maskId: String, inverted: Boolean) {
        val current = state.value.recipe ?: return
        commitMask(
            current.withMask(maskId) { it.copy(inverted = inverted) },
            detail = uiText(if (inverted) Res.string.editor_history_mask_inverted else Res.string.editor_history_mask_normal),
            valueText = current.mask(maskId)?.name?.asUiText(),
        )
    }

    /**
     * A handle's drag. The deltas come in normalised **image** coordinates, already converted by the
     * interface — that is where it is known where the photo is on screen.
     *
     * It does not close the history on its own: whoever starts the gesture calls [beginMaskGesture]
     * and whoever releases it calls [finishAdjustment], as in crop. One entry per gesture, not per frame.
     */
    fun moveMask(maskId: String, deltaX: Float, deltaY: Float) =
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_moved)) { it.withMaskMoved(maskId, deltaX, deltaY) }

    fun setMaskRadius(maskId: String, radiusX: Float? = null, radiusY: Float? = null) =
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_resized)) { it.withMaskRadius(maskId, radiusX, radiusY) }

    fun setMaskWidth(maskId: String, width: Float) =
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_resized)) { it.withMaskWidth(maskId, width) }

    fun rotateMask(maskId: String, degrees: Float) =
        editMaskGeometry(maskId, uiText(Res.string.editor_history_rotated)) { it.withMaskRotation(maskId, degrees) }

    /** Opens the gesture so everything it does falls into a single history entry. */
    fun beginMaskGesture(maskId: String, detail: UiText) {
        if (editStart != null) return
        val current = state.value.recipe ?: return
        editStart = current
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_history_mask),
            detail = detail,
            valueText = current.mask(maskId)?.name?.asUiText(),
            icon = HistoryIcon.MASK,
        )
    }

    private fun editMaskGeometry(maskId: String, detail: UiText, transform: (EditRecipe) -> EditRecipe) {
        val current = state.value.recipe ?: return
        beginMaskGesture(maskId, detail)
        val updated = transform(current)
        if (updated == current) return
        val committed = updated.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            updatedAt = Instant.now().toString(),
        )
        mutableState.update { it.copy(recipe = committed, isSaving = true) }
        scheduleSave(committed)
    }

    /** Create, delete, enable and invert are atomic: they close on their own, like geometry. */
    private fun commitMask(updated: EditRecipe, detail: UiText, valueText: UiText?) {
        val current = state.value.recipe ?: return
        if (updated.masks == current.masks) return
        editStart = null
        pendingChange = null
        val committed = updated.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            updatedAt = Instant.now().toString(),
        )
        setRecipeAndSave(committed)
        pushHistory(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = uiText(Res.string.editor_history_mask),
                detail = detail,
                valueText = valueText,
                icon = HistoryIcon.MASK,
                timestamp = Instant.now().toString(),
                recipe = committed,
            ),
        )
    }

    /** A geometry change is atomic: it has no drag, so it closes on its own. */
    private fun updateGeometry(updated: EditRecipe, detail: UiText, valueText: UiText? = null) {
        val current = state.value.recipe ?: return
        if (updated.geometry == current.geometry) return
        // The guided angles are measured in the turned and mirrored photo: after a quarter turn the
        // tilt that straightened a vertical would bend a level. The guides do not move, so solving
        // again gives back what the person had.
        val reoriented = updated.geometry.rotation != current.geometry.rotation ||
            updated.geometry.mirrorH != current.geometry.mirrorH ||
            updated.geometry.mirrorV != current.geometry.mirrorV
        val upright = updated.geometry.perspective.upright
        val lines = detectedLines?.takeIf { it.first === state.value.bitmap }?.second
        val resolved = when {
            !reoriented -> updated
            upright == UPRIGHT_GUIDED -> updated.withSolvedGuides(updated.geometry.perspective.guides)
            upright in AUTOMATIC_UPRIGHT_MODES && lines != null -> updated.withAutomaticUpright(lines).first
            upright in AUTOMATIC_UPRIGHT_MODES -> {
                // The lines of a photo opened again are not kept: find them, and solve after.
                commitGeometry(updated, detail, valueText)
                applyAutomaticUpright(upright)
                return
            }
            else -> updated
        }
        commitGeometry(resolved, detail, valueText)
    }

    private fun commitGeometry(updated: EditRecipe, detail: UiText, valueText: UiText?) {
        val current = state.value.recipe ?: return
        if (updated.geometry == current.geometry) return
        editStart = null
        pendingChange = null
        val committed = updated.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = updated.geometry.copy(
                straighten = if (current.processVersion >= 7) updated.geometry.straighten else 0f,
            ),
            updatedAt = Instant.now().toString(),
            derivedAssetId = null,
        )
        setRecipeAndSave(committed)
        pushHistory(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = uiText(Res.string.editor_history_geometry),
                detail = detail,
                valueText = valueText,
                icon = HistoryIcon.CROP,
                timestamp = Instant.now().toString(),
                recipe = committed,
            ),
        )
    }

    private fun refreshSavedRecipes() {
        mutableState.update { it.copy(isRecipeLibraryLoading = true) }
        viewModelScope.launch {
            runCatching { savedRecipes.list() }
                .onSuccess { items ->
                    mutableState.update { it.copy(savedRecipes = items, isRecipeLibraryLoading = false) }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(isRecipeLibraryLoading = false, recipeNotice = error.toUiText())
                    }
                }
        }
    }

    private fun updateHistoryState() = mutableState.update {
        it.copy(
            history = history.all,
            historyIndex = history.index,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
        )
    }

    private fun scheduleSave(recipe: EditRecipe) {
        if (!persistsToPhoto || needsNewerApp) {
            shouldPersist = false
            mutableState.update { it.copy(isSaving = false) }
            return
        }
        shouldPersist = true
        scheduleGeometryMirror(recipe, GEOMETRY_MIRROR_DELAY_MS)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_000)
            runCatching { recipes.save(recipe) }
                .onSuccess { mutableState.update { it.copy(isSaving = false) } }
                .onFailure { error -> mutableState.update { it.copy(error = error.toUiText(), isSaving = false) } }
        }
    }

    /**
     * Sends the geometry to Immich once it has stopped changing. Only a change of geometry counts:
     * the server holds nothing else of the recipe.
     */
    private fun scheduleGeometryMirror(recipe: EditRecipe, delayMillis: Long) {
        val geometry = recipe.geometry to recipe.processVersion
        if (geometry == mirroredGeometry) return
        mirroredGeometry = geometry
        pendingMirror = recipe
        mirrorJob?.cancel()
        mirrorJob = viewModelScope.launch {
            delay(delayMillis)
            pendingMirror = null
            geometryMirror.mirror(recipe)
        }
    }

    /** Sends a waiting geometry now. It outlives the screen: leaving is the most common moment for it. */
    private fun flushGeometryMirror() {
        val recipe = pendingMirror ?: return
        pendingMirror = null
        mirrorJob?.cancel()
        mirrorJob = viewModelScope.launch(NonCancellable) { geometryMirror.mirror(recipe) }
    }

    private suspend fun loadBitmap(asset: EditorAsset): Bitmap {
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context)
                .data(editorImageData(asset))
                .libraryAuth(asset.apiKey)
                .size(2048)
                .softwareBitmap()
                .build(),
        )
        if (result !is SuccessResult) throw LocalizedException(uiText(Res.string.editor_image_unreadable))
        return result.image.toBitmap()
    }
}

/**
 * The photo a recipe is edited against, as the recipe sees it. Not the sample's id: swapping the sample
 * must not change the recipe, or it would count as an edit.
 */
internal const val RECIPE_EDIT_ASSET_ID = "recipe"

/** The long side the lines are looked for at: every edge a building has, in a fraction of a second. */
private const val UPRIGHT_DETECTION_SIDE = 1000

/** Each Upright mode's name, on its button and in the history. */
internal fun uprightModeName(mode: String): StringResource = when (mode) {
    UPRIGHT_AUTO -> Res.string.editor_upright_auto
    UPRIGHT_LEVEL -> Res.string.editor_upright_level
    UPRIGHT_VERTICAL -> Res.string.editor_upright_vertical
    UPRIGHT_FULL -> Res.string.editor_upright_full
    UPRIGHT_GUIDED -> Res.string.editor_upright_guided
    else -> Res.string.editor_upright_off
}

/** Shorter than this, in the photo's pixels, a guide is a tap and has no direction. */
private const val MIN_GUIDE_PIXELS = 24f

/** How long the geometry has to rest before it goes to Immich: a crop being dragged is not a crop yet. */
private const val GEOMETRY_MIRROR_DELAY_MS = 2_000L

private fun RecipeSource.editState(opened: EditRecipe) = when (this) {
    is RecipeSource.Saved -> RecipeEditState(saved = saved, suggestedName = null, suggestedCollection = null, baseline = opened)
    is RecipeSource.BuiltIn -> RecipeEditState(
        saved = null,
        suggestedName = builtIn.nameText(),
        suggestedCollection = uiText(builtIn.category.labelRes()),
        baseline = opened,
    )
    is RecipeSource.New -> RecipeEditState(saved = null, suggestedName = null, suggestedCollection = null, baseline = opened)
}

/**
 * The edited look written back over the stored recipe. What ties the record to where it came from — the
 * photo it was made on, its creation — stays the record's; only the look and the moment change.
 */
internal fun EditRecipe.storedOver(stored: EditRecipe, now: String): EditRecipe = copy(
    assetId = stored.assetId,
    originalChecksum = stored.originalChecksum,
    createdAt = stored.createdAt,
    updatedAt = now,
)

internal fun EditRecipe.rebasedOnto(target: EditRecipe, now: String): EditRecipe = copy(
    assetId = target.assetId,
    originalChecksum = target.originalChecksum,
    createdAt = target.createdAt,
    updatedAt = now,
    derivedAssetId = null,
    geometry = geometry.activeAtProcess(processVersion),
    processVersion = CURRENT_PROCESS_VERSION,
)

private fun Geometry.activeAtProcess(processVersion: Int): Geometry = copy(
    cropRect = if (processVersion >= 6) cropRect else CropRect(),
    aspectLock = if (processVersion >= 6) aspectLock else null,
    straighten = if (processVersion >= 7) straighten else 0f,
    perspective = if (processVersion >= 10) perspective else Perspective(),
)

fun EditRecipe.editableCurvePoints(): List<eu.studio742.imago.core.model.CurvePoint> {
    // Before process 11 the curve was always pinned to the corners; a recipe from then that lacked a
    // corner point drew one anyway, and editing it has to start from what was drawn.
    return normalizeCurvePoints(toneCurve.rgb, pinnedToCorners = processVersion < 11)
}

private fun normalizeCurvePoints(
    source: List<eu.studio742.imago.core.model.CurvePoint>,
    pinnedToCorners: Boolean = false,
): List<eu.studio742.imago.core.model.CurvePoint> {
    val normalized = source
        .map { it.copy(x = it.x.coerceIn(0, 255), y = it.y.coerceIn(0, 255)) }
        .distinctBy { it.x }
        .sortedBy { it.x }
        .toMutableList()
    if (pinnedToCorners || normalized.size < 2) {
        if (normalized.none { it.x == 0 }) normalized.add(eu.studio742.imago.core.model.CurvePoint(0, 0))
        if (normalized.none { it.x == 255 }) normalized.add(eu.studio742.imago.core.model.CurvePoint(255, 255))
    }
    return normalized.sortedBy { it.x }
}

/** A new point goes between the end points: outside them the curve is flat, and that is their job. */
internal fun List<eu.studio742.imago.core.model.CurvePoint>.withAddedCurvePoint(
    x: Int,
    y: Int,
): List<eu.studio742.imago.core.model.CurvePoint> {
    val points = normalizeCurvePoints(this)
    if (x <= points.first().x || x >= points.last().x) return points
    if (points.size >= 16 || points.any { kotlin.math.abs(it.x - x) < 4 }) return points
    return (points + eu.studio742.imago.core.model.CurvePoint(x, y.coerceIn(0, 255))).sortedBy { it.x }
}

/**
 * Every point moves on both axes without passing its neighbours. The end points too, as in
 * Lightroom: the black point dragged in to 20 sends everything under 20 to black.
 */
internal fun List<eu.studio742.imago.core.model.CurvePoint>.withMovedCurvePoint(
    index: Int,
    x: Int,
    y: Int,
): List<eu.studio742.imago.core.model.CurvePoint> {
    if (index !in indices) return this
    val point = this[index]
    val lowest = if (index == 0) 0 else this[index - 1].x + 1
    val highest = if (index == lastIndex) 255 else this[index + 1].x - 1
    val movedX = if (lowest <= highest) x.coerceIn(lowest, highest) else point.x
    return toMutableList().apply { this[index] = point.copy(x = movedX, y = y.coerceIn(0, 255)) }
}

internal fun List<eu.studio742.imago.core.model.CurvePoint>.withRemovedCurvePoint(
    index: Int,
): List<eu.studio742.imago.core.model.CurvePoint> =
    if (index <= 0 || index >= lastIndex) this else filterIndexed { pointIndex, _ -> pointIndex != index }

fun EditRecipe.hslBand(band: HslColorBand): HslBand = when (band) {
    HslColorBand.RED -> hsl.red
    HslColorBand.ORANGE -> hsl.orange
    HslColorBand.YELLOW -> hsl.yellow
    HslColorBand.GREEN -> hsl.green
    HslColorBand.AQUA -> hsl.aqua
    HslColorBand.BLUE -> hsl.blue
    HslColorBand.PURPLE -> hsl.purple
    HslColorBand.MAGENTA -> hsl.magenta
}

private fun eu.studio742.imago.core.model.Hsl.withBand(band: HslColorBand, value: HslBand) = when (band) {
    HslColorBand.RED -> copy(red = value)
    HslColorBand.ORANGE -> copy(orange = value)
    HslColorBand.YELLOW -> copy(yellow = value)
    HslColorBand.GREEN -> copy(green = value)
    HslColorBand.AQUA -> copy(aqua = value)
    HslColorBand.BLUE -> copy(blue = value)
    HslColorBand.PURPLE -> copy(purple = value)
    HslColorBand.MAGENTA -> copy(magenta = value)
}

fun EditRecipe.colorGradeWheel(wheel: ColorGradeWheel): ColorWheel = when (wheel) {
    ColorGradeWheel.SHADOWS -> colorGrading.shadows
    ColorGradeWheel.MIDTONES -> colorGrading.midtones
    ColorGradeWheel.HIGHLIGHTS -> colorGrading.highlights
    ColorGradeWheel.GLOBAL -> colorGrading.global
}

private fun ColorGrading.withWheel(wheel: ColorGradeWheel, value: ColorWheel) = when (wheel) {
    ColorGradeWheel.SHADOWS -> copy(shadows = value)
    ColorGradeWheel.MIDTONES -> copy(midtones = value)
    ColorGradeWheel.HIGHLIGHTS -> copy(highlights = value)
    ColorGradeWheel.GLOBAL -> copy(global = value)
}

fun ColorWheel.component(component: ColorGradeComponent): Float = when (component) {
    ColorGradeComponent.HUE -> hue
    ColorGradeComponent.SATURATION -> saturation
    ColorGradeComponent.LUMINANCE -> luminance
}

private fun ColorWheel.withComponent(component: ColorGradeComponent, value: Float) = when (component) {
    ColorGradeComponent.HUE -> copy(hue = value)
    ColorGradeComponent.SATURATION -> copy(saturation = value)
    ColorGradeComponent.LUMINANCE -> copy(luminance = value)
}

/** The hue within one turn, with negatives wrapping around the other way. */
internal fun positiveHue(value: Float): Float = value - floor(value / 360f) * 360f

fun HslBand.component(component: HslComponent): Float = when (component) {
    HslComponent.HUE -> hue
    HslComponent.SATURATION -> saturation
    HslComponent.LUMINANCE -> luminance
}

private fun HslBand.withComponent(component: HslComponent, value: Float) = when (component) {
    HslComponent.HUE -> copy(hue = value)
    HslComponent.SATURATION -> copy(saturation = value)
    HslComponent.LUMINANCE -> copy(luminance = value)
}

internal fun EditRecipe.withAdjustment(adjustment: Adjustment, value: Float): EditRecipe = when (adjustment) {
    Adjustment.EXPOSURE -> copy(tone = tone.copy(exposure = value))
    Adjustment.CONTRAST -> copy(tone = tone.copy(contrast = value))
    Adjustment.HIGHLIGHTS -> copy(tone = tone.copy(highlights = value))
    Adjustment.SHADOWS -> copy(tone = tone.copy(shadows = value))
    Adjustment.WHITES -> copy(tone = tone.copy(whites = value))
    Adjustment.BLACKS -> copy(tone = tone.copy(blacks = value))
    Adjustment.TEMPERATURE -> copy(whiteBalance = whiteBalance.copy(temp = value))
    Adjustment.TINT -> copy(whiteBalance = whiteBalance.copy(tint = value))
    Adjustment.VIBRANCE -> copy(presence = presence.copy(vibrance = value))
    Adjustment.SATURATION -> copy(presence = presence.copy(saturation = value))
    Adjustment.TEXTURE -> copy(presence = presence.copy(texture = value))
    Adjustment.CLARITY -> copy(presence = presence.copy(clarity = value))
    Adjustment.DEHAZE -> copy(presence = presence.copy(dehaze = value))
    Adjustment.VIGNETTE_AMOUNT -> copy(effects = effects.copy(vignetteAmount = value))
    Adjustment.VIGNETTE_MIDPOINT -> copy(effects = effects.copy(vignetteMidpoint = value))
    Adjustment.VIGNETTE_ROUNDNESS -> copy(effects = effects.copy(vignetteRoundness = value))
    Adjustment.VIGNETTE_FEATHER -> copy(effects = effects.copy(vignetteFeather = value))
    Adjustment.GRAIN_AMOUNT -> copy(effects = effects.copy(grainAmount = value))
    Adjustment.GRAIN_SIZE -> copy(effects = effects.copy(grainSize = value))
    Adjustment.GRAIN_ROUGHNESS -> copy(effects = effects.copy(grainRoughness = value))
    Adjustment.GRADE_BLENDING -> copy(colorGrading = colorGrading.copy(blending = value))
    Adjustment.GRADE_BALANCE -> copy(colorGrading = colorGrading.copy(balance = value))
    Adjustment.PERSPECTIVE_VERTICAL -> withPerspective { copy(vertical = value) }
    Adjustment.PERSPECTIVE_HORIZONTAL -> withPerspective { copy(horizontal = value) }
    Adjustment.PERSPECTIVE_ASPECT -> withPerspective { copy(aspect = value) }
    Adjustment.PERSPECTIVE_SCALE -> withPerspective { copy(scale = value) }
    Adjustment.PERSPECTIVE_OFFSET_X -> withPerspective { copy(offsetX = value) }
    Adjustment.PERSPECTIVE_OFFSET_Y -> withPerspective { copy(offsetY = value) }
}

private fun EditRecipe.withPerspective(change: Perspective.() -> Perspective) =
    copy(geometry = geometry.copy(perspective = geometry.perspective.change()))

fun EditRecipe.adjustmentValue(adjustment: Adjustment): Float = when (adjustment) {
    Adjustment.EXPOSURE -> tone.exposure
    Adjustment.CONTRAST -> tone.contrast
    Adjustment.HIGHLIGHTS -> tone.highlights
    Adjustment.SHADOWS -> tone.shadows
    Adjustment.WHITES -> tone.whites
    Adjustment.BLACKS -> tone.blacks
    Adjustment.TEMPERATURE -> whiteBalance.temp
    Adjustment.TINT -> whiteBalance.tint
    Adjustment.VIBRANCE -> presence.vibrance
    Adjustment.SATURATION -> presence.saturation
    Adjustment.TEXTURE -> presence.texture
    Adjustment.CLARITY -> presence.clarity
    Adjustment.DEHAZE -> presence.dehaze
    Adjustment.VIGNETTE_AMOUNT -> effects.vignetteAmount
    Adjustment.VIGNETTE_MIDPOINT -> effects.vignetteMidpoint
    Adjustment.VIGNETTE_ROUNDNESS -> effects.vignetteRoundness
    Adjustment.VIGNETTE_FEATHER -> effects.vignetteFeather
    Adjustment.GRAIN_AMOUNT -> effects.grainAmount
    Adjustment.GRAIN_SIZE -> effects.grainSize
    Adjustment.GRAIN_ROUGHNESS -> effects.grainRoughness
    Adjustment.GRADE_BLENDING -> colorGrading.blending
    Adjustment.GRADE_BALANCE -> colorGrading.balance
    Adjustment.PERSPECTIVE_VERTICAL -> geometry.perspective.vertical
    Adjustment.PERSPECTIVE_HORIZONTAL -> geometry.perspective.horizontal
    Adjustment.PERSPECTIVE_ASPECT -> geometry.perspective.aspect
    Adjustment.PERSPECTIVE_SCALE -> geometry.perspective.scale
    Adjustment.PERSPECTIVE_OFFSET_X -> geometry.perspective.offsetX
    Adjustment.PERSPECTIVE_OFFSET_Y -> geometry.perspective.offsetY
}
