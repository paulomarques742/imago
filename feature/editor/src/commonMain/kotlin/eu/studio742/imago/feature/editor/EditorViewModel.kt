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
import eu.studio742.imago.core.render.ClippingWarning
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
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CancellationException
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
import eu.studio742.imago.core.data.lookFingerprint
import eu.studio742.imago.core.model.BUILT_IN_RECIPES
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.render.NeutralWhiteBalance
import eu.studio742.imago.core.render.autoWhiteBalanceOf
import eu.studio742.imago.core.render.withAutoWhiteBalance
import eu.studio742.imago.core.render.hasWhiteBalance
import eu.studio742.imago.core.render.withLightWhiteBalance
import eu.studio742.imago.core.model.BuiltInRecipe
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.RecipeConflictVersion
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MAX_MASK_COMPONENTS
import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.Retouch
import eu.studio742.imago.core.model.RetouchMode
import eu.studio742.imago.core.render.Retouching
import eu.studio742.imago.core.model.MaskOp
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
import eu.studio742.imago.core.render.BrushReference
import eu.studio742.imago.core.render.RangeMasks
import eu.studio742.imago.core.model.RANGE_REFINE_DEFAULT
import eu.studio742.imago.core.render.detectLineSegments
import eu.studio742.imago.core.render.solveAutoUpright
import eu.studio742.imago.core.render.toPixelBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.model.UPRIGHT_GUIDED
import eu.studio742.imago.core.model.UPRIGHT_OFF
import eu.studio742.imago.core.model.UprightGuide
import eu.studio742.imago.core.model.WhiteBalance
import eu.studio742.imago.core.render.frameGeometry
import eu.studio742.imago.core.render.solveGuidedUpright
import kotlin.math.roundToInt
import kotlin.math.hypot
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.withPartsFrom
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.generated.ImmichKeyPermissions
import eu.studio742.imago.core.immich.requirePermission
import eu.studio742.imago.core.render.RenderParameters
import eu.studio742.imago.core.render.toRenderParameters
import kotlin.math.floor
import java.io.File
import eu.studio742.imago.core.render.FloatPixelBuffer
import eu.studio742.imago.core.render.CURRENT_RAW_HIGHLIGHT
import eu.studio742.imago.core.render.RawDevelopmentUpgrade
import eu.studio742.imago.core.model.RAW_RENDERING_LINEAR
import eu.studio742.imago.core.model.rawEngine
import eu.studio742.imago.core.render.decodeRawEditorPreview
import eu.studio742.imago.core.render.CameraLight
import eu.studio742.imago.core.render.KelvinTint
import eu.studio742.imago.core.render.CameraExposureMatch
import eu.studio742.imago.core.render.shouldInspectRawOriginal
import eu.studio742.imago.core.render.LibraryRaw
import eu.studio742.imago.core.render.LibraryRawEdits
import eu.studio742.imago.core.render.createPhotoTempFile
import eu.studio742.imago.core.render.toPlatformBitmap
import eu.studio742.imago.core.model.appliesBaselineExposure
import eu.studio742.imago.core.model.CURRENT_RAW_RENDERING
import eu.studio742.imago.core.model.RAW_PROFILE_COLOR
import eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME
import eu.studio742.imago.core.model.RAW_PROFILE_NEUTRAL
import eu.studio742.imago.core.model.RAW_PROFILE_VIVID
import eu.studio742.imago.core.model.RAW_RENDERING_PROFILED
import eu.studio742.imago.core.model.needsNewerRawRendering
import eu.studio742.imago.core.model.rawHighlightMode
import eu.studio742.imago.core.model.rawRendering
import eu.studio742.imago.core.model.developedAsRaw
import eu.studio742.imago.core.model.KNOWN_RAW_ENGINES
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

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
 * The design system fixes the list as Light, Colour, Detail, Optics, Effects and Masks. The curve and HSL,
 * which used to be categories, became tools opened from inside Light and Colour — they are precision
 * instruments, not families of adjustments.
 */
enum class EditorPanel { LIGHT, COLOR, DETAIL, OPTICS, EFFECTS, MASKS }

/**
 * What the bottom panel is showing.
 *
 * [ADJUSTMENTS] is the normal state — the categories and their sliders. The others are modes with
 * their own interface, which replace the panel's content without changing screen.
 */
enum class EditorSheet { ADJUSTMENTS, CURVE, HSL, COLOR_GRADING, CROP, PERSPECTIVE, HISTORY, MASKS, MASK_ADJUSTMENTS, REMOVE }

/**
 * The buttons of the bar under the photo, in its order.
 *
 * Curve and HSL are not here on purpose — they belong to Light and Colour, and that is where they
 * open from.
 */
enum class EditorTool {
    ADJUSTMENTS,
    CROP,
    REMOVE,
    RECIPES,
    HISTORY,
}

/**
 * The bar's tools. Editing a recipe there is no crop and no Remove — a frame or a speck of dust belongs
 * to one photo, not to a look meant for any.
 */
internal fun editorTools(recipeMode: Boolean): List<EditorTool> =
    if (recipeMode) EditorTool.entries - EditorTool.CROP - EditorTool.REMOVE else EditorTool.entries

enum class HslColorBand { RED, ORANGE, YELLOW, GREEN, AQUA, BLUE, PURPLE, MAGENTA }

/** Which point curve the curve tool edits: the composite one, or one channel's. */
enum class CurveChannel { RGB, RED, GREEN, BLUE }
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
    COLOR, CURVE, HSL, GRADE, DETAIL, EFFECTS, CROP, RECIPE, MASK, REMOVE,
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
    SHARPEN_AMOUNT(0f, 0f..150f),
    SHARPEN_RADIUS(1f, 0.5f..3f),
    SHARPEN_DETAIL(25f, 0f..100f),
    SHARPEN_MASKING(0f, 0f..100f),
    NR_LUMINANCE(0f, 0f..100f),
    NR_LUMINANCE_DETAIL(50f, 0f..100f),
    NR_CONTRAST(0f, 0f..100f),
    // Neutral is off, not the 25 a new recipe starts with: the three below it wake up when it is not.
    NR_COLOR(0f, 0f..100f),
    NR_COLOR_DETAIL(50f, 0f..100f),
    NR_COLOR_SMOOTHNESS(50f, 0f..100f),
    CAL_SHADOWS_TINT(0f, -100f..100f),
    CAL_RED_HUE(0f, -100f..100f),
    CAL_RED_SATURATION(0f, -100f..100f),
    CAL_GREEN_HUE(0f, -100f..100f),
    CAL_GREEN_SATURATION(0f, -100f..100f),
    CAL_BLUE_HUE(0f, -100f..100f),
    CAL_BLUE_SATURATION(0f, -100f..100f),
    LENS_VIGNETTE_AMOUNT(0f, -100f..100f),
    LENS_VIGNETTE_MIDPOINT(50f, 0f..100f),
    LENS_PURPLE_AMOUNT(0f, 0f..100f),
    LENS_PURPLE_RANGE(50f, 0f..100f),
    LENS_GREEN_AMOUNT(0f, 0f..100f),
    LENS_GREEN_RANGE(50f, 0f..100f),
    VIGNETTE_AMOUNT(0f, -100f..100f),
    VIGNETTE_MIDPOINT(50f, 0f..100f),
    VIGNETTE_ROUNDNESS(0f, -100f..100f),
    VIGNETTE_FEATHER(50f, 0f..100f),
    GRAIN_AMOUNT(0f, 0f..100f),
    GRAIN_SIZE(25f, 0f..100f),
    GRAIN_ROUGHNESS(50f, 0f..100f),
    GRADE_BLENDING(50f, 0f..100f),
    GRADE_BALANCE(0f, -100f..100f),
    PERSPECTIVE_DISTORTION(0f, -100f..100f),
    PERSPECTIVE_VERTICAL(0f, -100f..100f),
    PERSPECTIVE_HORIZONTAL(0f, -100f..100f),
    PERSPECTIVE_ASPECT(0f, -100f..100f),
    PERSPECTIVE_SCALE(0f, -50f..100f),
    PERSPECTIVE_OFFSET_X(0f, -100f..100f),
    PERSPECTIVE_OFFSET_Y(0f, -100f..100f),
    /** Lightroom's mask Amount: it lives in the mask, not in the recipe — see [isMaskSlider]. */
    MASK_AMOUNT(100f, 0f..200f),
}

/** The sliders of the perspective sheet, in Lightroom's order. */
val PERSPECTIVE_ADJUSTMENTS = listOf(
    Adjustment.PERSPECTIVE_DISTORTION,
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
    // Radius, Detail and Masking say nothing while the Amount is at 0.
    Adjustment.SHARPEN_RADIUS, Adjustment.SHARPEN_DETAIL, Adjustment.SHARPEN_MASKING -> Adjustment.SHARPEN_AMOUNT
    Adjustment.NR_LUMINANCE_DETAIL, Adjustment.NR_CONTRAST -> Adjustment.NR_LUMINANCE
    Adjustment.NR_COLOR_DETAIL, Adjustment.NR_COLOR_SMOOTHNESS -> Adjustment.NR_COLOR
    Adjustment.LENS_VIGNETTE_MIDPOINT -> Adjustment.LENS_VIGNETTE_AMOUNT
    Adjustment.LENS_PURPLE_RANGE -> Adjustment.LENS_PURPLE_AMOUNT
    Adjustment.LENS_GREEN_RANGE -> Adjustment.LENS_GREEN_AMOUNT
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
    Adjustment.SHARPEN_AMOUNT -> Res.string.editor_adj_sharpen_amount
    Adjustment.SHARPEN_RADIUS -> Res.string.editor_adj_sharpen_radius
    Adjustment.SHARPEN_DETAIL -> Res.string.editor_adj_sharpen_detail
    Adjustment.SHARPEN_MASKING -> Res.string.editor_adj_sharpen_masking
    Adjustment.NR_LUMINANCE -> Res.string.editor_adj_nr_luminance
    Adjustment.NR_LUMINANCE_DETAIL -> Res.string.editor_adj_nr_luminance_detail
    Adjustment.NR_CONTRAST -> Res.string.editor_adj_nr_contrast
    Adjustment.NR_COLOR -> Res.string.editor_adj_nr_color
    Adjustment.NR_COLOR_DETAIL -> Res.string.editor_adj_nr_color_detail
    Adjustment.NR_COLOR_SMOOTHNESS -> Res.string.editor_adj_nr_color_smoothness
    Adjustment.CAL_SHADOWS_TINT -> Res.string.editor_adj_cal_shadows_tint
    Adjustment.CAL_RED_HUE -> Res.string.editor_adj_cal_red_hue
    Adjustment.CAL_RED_SATURATION -> Res.string.editor_adj_cal_red_saturation
    Adjustment.CAL_GREEN_HUE -> Res.string.editor_adj_cal_green_hue
    Adjustment.CAL_GREEN_SATURATION -> Res.string.editor_adj_cal_green_saturation
    Adjustment.CAL_BLUE_HUE -> Res.string.editor_adj_cal_blue_hue
    Adjustment.CAL_BLUE_SATURATION -> Res.string.editor_adj_cal_blue_saturation
    Adjustment.LENS_VIGNETTE_AMOUNT -> Res.string.editor_adj_lens_vignette
    Adjustment.LENS_VIGNETTE_MIDPOINT -> Res.string.editor_adj_lens_vignette_midpoint
    Adjustment.LENS_PURPLE_AMOUNT -> Res.string.editor_adj_lens_purple
    Adjustment.LENS_PURPLE_RANGE -> Res.string.editor_adj_lens_purple_range
    Adjustment.LENS_GREEN_AMOUNT -> Res.string.editor_adj_lens_green
    Adjustment.LENS_GREEN_RANGE -> Res.string.editor_adj_lens_green_range
    Adjustment.VIGNETTE_AMOUNT -> Res.string.editor_adj_vignette
    Adjustment.VIGNETTE_MIDPOINT -> Res.string.editor_adj_vignette_midpoint
    Adjustment.VIGNETTE_ROUNDNESS -> Res.string.editor_adj_vignette_roundness
    Adjustment.VIGNETTE_FEATHER -> Res.string.editor_adj_vignette_feather
    Adjustment.GRAIN_AMOUNT -> Res.string.editor_adj_grain
    Adjustment.GRAIN_SIZE -> Res.string.editor_adj_grain_size
    Adjustment.GRAIN_ROUGHNESS -> Res.string.editor_adj_grain_roughness
    Adjustment.GRADE_BLENDING -> Res.string.editor_adj_grading_blending
    Adjustment.GRADE_BALANCE -> Res.string.editor_adj_grading_balance
    Adjustment.PERSPECTIVE_DISTORTION -> Res.string.editor_adj_perspective_distortion
    Adjustment.PERSPECTIVE_VERTICAL -> Res.string.editor_adj_perspective_vertical
    Adjustment.PERSPECTIVE_HORIZONTAL -> Res.string.editor_adj_perspective_horizontal
    Adjustment.PERSPECTIVE_ASPECT -> Res.string.editor_adj_perspective_aspect
    Adjustment.PERSPECTIVE_SCALE -> Res.string.editor_adj_perspective_scale
    Adjustment.PERSPECTIVE_OFFSET_X -> Res.string.editor_adj_perspective_offset_x
    Adjustment.PERSPECTIVE_OFFSET_Y -> Res.string.editor_adj_perspective_offset_y
    Adjustment.MASK_AMOUNT -> Res.string.editor_amount
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
    Adjustment.TEXTURE, Adjustment.CLARITY, Adjustment.DEHAZE,
    Adjustment.SHARPEN_AMOUNT, Adjustment.SHARPEN_RADIUS, Adjustment.SHARPEN_DETAIL, Adjustment.SHARPEN_MASKING,
    Adjustment.NR_LUMINANCE, Adjustment.NR_LUMINANCE_DETAIL, Adjustment.NR_CONTRAST, Adjustment.NR_COLOR, Adjustment.NR_COLOR_DETAIL, Adjustment.NR_COLOR_SMOOTHNESS,
    -> HistoryIcon.DETAIL
    Adjustment.CAL_SHADOWS_TINT, Adjustment.CAL_RED_HUE, Adjustment.CAL_RED_SATURATION, Adjustment.CAL_GREEN_HUE, Adjustment.CAL_GREEN_SATURATION, Adjustment.CAL_BLUE_HUE, Adjustment.CAL_BLUE_SATURATION -> HistoryIcon.COLOR
    Adjustment.LENS_VIGNETTE_AMOUNT, Adjustment.LENS_VIGNETTE_MIDPOINT, Adjustment.LENS_PURPLE_AMOUNT, Adjustment.LENS_PURPLE_RANGE, Adjustment.LENS_GREEN_AMOUNT, Adjustment.LENS_GREEN_RANGE -> HistoryIcon.CROP
    Adjustment.GRADE_BLENDING, Adjustment.GRADE_BALANCE -> HistoryIcon.GRADE
    in PERSPECTIVE_ADJUSTMENTS -> HistoryIcon.CROP
    Adjustment.MASK_AMOUNT -> HistoryIcon.MASK
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

val CurveChannel.labelRes: StringResource
    get() = when (this) {
        CurveChannel.RGB -> Res.string.editor_curve_channel_rgb
        CurveChannel.RED -> Res.string.editor_curve_channel_red
        CurveChannel.GREEN -> Res.string.editor_curve_channel_green
        CurveChannel.BLUE -> Res.string.editor_curve_channel_blue
    }

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
/** Kelvin to the nearest 10 K, without thousands separators, as Lightroom writes it. */
internal fun formatKelvin(kelvin: Float): String = "%d K".format((kelvin / 10f).roundToInt() * 10)

fun formatAdjustmentValue(adjustment: Adjustment, value: Float): String = when {
    adjustment == Adjustment.EXPOSURE -> "%+.2f EV".format(value)
    adjustment == Adjustment.SHARPEN_RADIUS -> "%.1f".format(value)
    // A range that never goes below zero is a quantity, not a deviation: showing it a sign would only
    // suggest there is a negative side.
    adjustment.range.start >= 0f -> "%.0f".format(value)
    else -> "%+.0f".format(value)
}

data class EditorUiState(
    val asset: EditorAsset? = null,
    val bitmap: Bitmap? = null,
    val highPrecisionSource: FloatPixelBuffer? = null,
    /** A RAW's camera colour: its white balance reads in kelvin. Null for a JPEG, or a camera without a matrix. */
    val cameraLight: CameraLight? = null,
    val recipe: EditRecipe? = null,
    val panel: EditorPanel = EditorPanel.LIGHT,
    /** What the photo is painted with where it touches an end of the scale; the histogram's two triangles. */
    val clipping: ClippingWarning = ClippingWarning.NONE,
    val sheet: EditorSheet = EditorSheet.ADJUSTMENTS,
    val selectedHslBand: HslColorBand = HslColorBand.RED,
    val curveChannel: CurveChannel = CurveChannel.RGB,
    /** The White Balance Selector is on: a finger on the photo reads a colour instead of moving it. */
    val isPickingWhiteBalance: Boolean = false,
    /** The last area read was too dark or burnt out to say anything about the light. */
    val whiteBalanceUnreadable: Boolean = false,
    val selectedColorGradeWheel: ColorGradeWheel = ColorGradeWheel.SHADOWS,
    val selectedMaskId: String? = null,
    /** The component of the selected mask whose handles are on the photo. Read through [selectedComponent]. */
    val selectedMaskComponent: Int = 0,
    /** "Show mask" pins the red; otherwise it only lights up during the gesture. */
    val maskOverlayPinned: Boolean = false,
    /** The mask brush in the hand: Brush or Eraser, and both their settings. Remembered on the device. */
    val brushTool: BrushTool = BrushTool(),
    /** Lightroom's Shift on a Color Range: a new sample joins the others instead of replacing them. */
    val rangeAddsSamples: Boolean = false,
    /** The Remove tool in the hand: the mode and the brush of a new correction. Remembered on the device. */
    val retouchTool: RetouchTool = RetouchTool(),
    /** The correction whose pins are lit, and the one the panel's sliders and buttons act on. */
    val selectedRetouchId: String? = null,
    /** A correction's content is being worked out: a Remove's fill, or where a Heal or a Clone takes from. */
    val isSolvingRetouch: Boolean = false,
    /** Said when the last correction found nothing to take its content from, and was dropped. */
    val retouchNotice: UiText? = null,
    /** The corrections' pins and areas are drawn over the photo; off, the photo is seen whole, as the masks' eye does. */
    val showsRetouches: Boolean = true,
    /** Visualize Spots: while the Remove tool is open, the photo as a map of what stands out, at the tool's sensitivity. */
    val visualizesSpots: Boolean = false,
    /**
     * The Heal or Clone a new stroke still widens, for [RETOUCH_EXTEND_MILLIS] after a gesture on it.
     * Meanwhile its pin is small and stays put, so a stroke that starts beside it paints.
     */
    val extendableRetouchId: String? = null,
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
    val builtInMarks: List<BuiltInRecipeMark> = emptyList(),
    val canPasteRecipe: Boolean = false,
    /** What the dialogs that copy or save a recipe start with: the last choice made on this device. */
    val recipeParts: Set<RecipePart> = RecipePart.LOOK,
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
    /** The development is being redone for the new rendering. */
    val isUpgradingDevelopment: Boolean = false,
    /** The saved recipe, read before the photo is: the editor shows the edit as the library does while it develops. */
    val placeholderRecipe: EditRecipe? = null,
    val error: UiText? = null,
) {
    /**
     * A RAW whose rendering has profiles. A recipe edited on process 19's rendering keeps it, and there
     * the menu would change nothing.
     */
    val showsRawProfile: Boolean
        get() = highPrecisionSource?.linear == true && recipe?.let { it.rawRendering() >= RAW_RENDERING_PROFILED } == true

    /** A RAW kept under an older development, with a newer one to move it to. */
    val canUpgradeDevelopment: Boolean
        get() = highPrecisionSource?.linear == true && recipe?.let(RawDevelopmentUpgrade::isAvailable) == true

    val hasUnsavedRecipeChanges: Boolean
        get() {
            val edit = recipeEdit ?: return false
            val current = recipe ?: return false
            return !current.sameLookAs(edit.baseline)
        }

    /**
     * What the stage draws. The open mask goes along even with no adjustments yet, so that its red
     * can show — a brush has no outline, and the first strokes would otherwise paint invisibly.
     */
    val renderParameters: RenderParameters
        get() = recipe?.toRenderParameters(showMask = selectedMaskId.takeIf { isMaskEditing }) ?: RenderParameters()

    /** The open mask's selected shape is a range: one finger on the photo samples it. */
    val isSamplingRange: Boolean
        get() = isMaskEditing && selectedMask?.components?.getOrNull(selectedComponent)?.shape?.isRange == true

    /** The open mask's selected shape is a brush: one finger on the photo paints. */
    val isPainting: Boolean
        get() = isMaskEditing && selectedMask?.components?.getOrNull(selectedComponent)?.shape == MaskShape.BRUSH

    /** Crop is the only mode that needs to turn off zoom and the pager. */
    val isCropping: Boolean get() = sheet == EditorSheet.CROP

    /** The Remove tool is open: one finger on the photo paints a correction or moves one. */
    val isRetouching: Boolean get() = sheet == EditorSheet.REMOVE && recipe != null

    val selectedRetouch: Retouch? get() = selectedRetouchId?.let { id -> recipe?.retouch(id) }

    /** A slider of the Remove tool as it reads: the selected correction's Feather and Opacity, else the tool's. */
    fun retouchValue(setting: RetouchSetting): Float {
        val selected = selectedRetouch ?: return retouchTool.value(setting)
        return when (setting) {
            RetouchSetting.SIZE -> retouchTool.size
            RetouchSetting.FEATHER -> selected.strokes.firstOrNull()?.feather ?: retouchTool.feather
            RetouchSetting.OPACITY -> selected.opacity
            RetouchSetting.SPOTS -> retouchTool.spots
        }
    }

    /** Editing a mask: one is selected and the panel is on one of its sheets. */
    val isMaskEditing: Boolean
        get() = selectedMaskId != null &&
            (sheet == EditorSheet.MASKS || sheet == EditorSheet.MASK_ADJUSTMENTS)

    val masks: List<LocalMask> get() = recipe?.masks.orEmpty()

    val selectedMask: LocalMask? get() = selectedMaskId?.let { id -> masks.firstOrNull { it.id == id } }

    /**
     * The selected component, always one the mask has. An undo can take away the component that was
     * selected; the handles then go to the last one left instead of vanishing.
     */
    val selectedComponent: Int
        get() = selectedMaskComponent.coerceIn(0, (selectedMask?.components?.lastIndex ?: 0).coerceAtLeast(0))

    /**
     * A slider's value: the selected mask's when there is one and the adjustment is local, and the
     * global one in every other case.
     *
     * It exists so that the slider panel does not need to know whether it is editing a mask — it is
     * the same `AdjustmentPanel` in both cases, and a second copy of it would be a second way for the
     * same slider to behave.
     */
    /**
     * A slider's value as it is read. A RAW's Temperature and Tint, outside a mask, are the scene's
     * light: kelvin, and the absolute Tint; the slider itself still moves the light relative to As Shot.
     */
    fun formatValue(adjustment: Adjustment, value: Float): String {
        val light = cameraLight
        val global = !(selectedMaskId != null && adjustment.isMaskSlider() && sheet == EditorSheet.MASK_ADJUSTMENTS)
        val balance = recipe?.whiteBalance
        if (light == null || !global || balance == null) return formatAdjustmentValue(adjustment, value)
        return when (adjustment) {
            Adjustment.TEMPERATURE -> formatKelvin(light.kelvinTint(value, balance.tint).kelvin)
            Adjustment.TINT -> "%+d".format(light.kelvinTint(balance.temp, value).tint.roundToInt())
            else -> formatAdjustmentValue(adjustment, value)
        }
    }

    fun valueOf(adjustment: Adjustment): Float {
        val current = recipe ?: return adjustment.neutral
        val maskId = selectedMaskId
        return if (maskId != null && adjustment.isMaskSlider() && sheet == EditorSheet.MASK_ADJUSTMENTS) {
            current.maskSliderValue(maskId, adjustment)
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
    /** Told when an export changes a stack, so the grid shows the new cover; tests leave it out. */
    private val library: eu.studio742.imago.core.data.LibraryRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        EditorUiState(brushTool = BrushTool.decode(configuration.brushTool), retouchTool = RetouchTool.decode(configuration.retouchTool)),
    )
    val state = mutableState.asStateFlow()
    private val exportOriginChecker = ExportOriginChecker(viewModelScope) { assetId ->
        checkNotNull(library).assetDetail(assetId).asset.externalLibraryId
    }
    val exportOrigin = exportOriginChecker.state

    fun prepareExport() {
        state.value.asset?.id?.let(exportOriginChecker::check)
    }

    private val history = EditHistory()
    private var editStart: EditRecipe? = null

    /** What is being moved right now, so the entry knows what to call itself when it closes. */
    private var pendingChange: PendingChange? = null
    private var loadJob: Job? = null
    private var redevelopJob: Job? = null

    /** The rendering and engine the pixels on screen were decoded for. */
    private var developedSource: Pair<Int, String>? = null
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

    /** The open photo's Auto white balance, found once: choosing Auto and applying recipes share it. */
    private var autoWhiteBalance: Pair<Bitmap, NeutralWhiteBalance?>? = null

    /** The photo's colours as the range masks read them, built once per photo. */
    private var rangeReference: Pair<Bitmap, BrushReference>? = null

    /**
     * The photo as a new correction reads it — with the corrections before it applied, at the raster's
     * size — kept while those stay the same: a second stroke in a row reads the same photo.
     */
    private var retouchReference: Triple<Bitmap, List<Retouch>, BrushReference>? = null
    /**
     * The corrections being worked out, one job each: a second stroke can be painted while the first
     * is still being solved, and must not cancel it.
     */
    private val retouchJobs = mutableMapOf<String, Job>()

    /**
     * Content that arrived while a finger was still on the photo. Written into the recipe then, it would
     * close the history entry of a gesture still going on; it waits for the finger to lift.
     */
    private val deferredRetouches = LinkedHashMap<String, () -> Unit>()

    /**
     * The Heal or Clone a new stroke still joins, and until when: a finger that paints again within
     * [RETOUCH_EXTEND_MILLIS] of the last gesture on it (a stroke, its source dragged) is widening the
     * same area, not starting another correction.
     */
    private var extendable: Pair<String, Long>? = null

    /** The stroke being painted adds to a correction that already was; a pinch then only takes it away. */
    private var extendingRetouch = false

    /** The time, in milliseconds; a test moves it on by hand. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Shrinks the pin back to its size when the window closes. */
    private var extendWindowJob: Job? = null

    private fun closeExtendWindow() {
        extendable = null
        extendWindowJob?.cancel()
        if (state.value.extendableRetouchId != null) mutableState.update { it.copy(extendableRetouchId = null) }
    }
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

    private var copiedParts: Set<RecipePart> = RecipePart.LOOK

    init {
        mutableState.update { it.copy(recipeParts = configuration.rememberedRecipeParts()) }
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
        exportOriginChecker.reset()
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
            builtInMarks = retained.builtInMarks,
            canPasteRecipe = copiedRecipe != null,
            recipeParts = retained.recipeParts,
            isRecipeLibraryLoading = retained.isRecipeLibraryLoading,
            recipeNotice = retained.recipeNotice,
            // The brushes are the device's, not the photo's: the next photo finds them as they were left.
            brushTool = retained.brushTool,
            retouchTool = retained.retouchTool,
        )
        if (target is PhotoEditTarget.Asset) {
            viewModelScope.launch {
                val saved = runCatching { recipes.get(asset.id) }.getOrNull()
                mutableState.update { if (it.asset?.id == asset.id && it.bitmap == null) it.copy(placeholderRecipe = saved) else it }
            }
        }
        loadJob = viewModelScope.launch {
            runCatching {
                coroutineScope {
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
                    // How the original is developed is the photo's: a stored recipe keeps its own, and a
                    // recipe edited on a sample is applied as to a new photo. Decoding needs it first.
                    val development = async {
                        if (target is PhotoEditTarget.Recipe) null
                        else stored.await()?.takeIf { !it.needsNewerApp() && it.belongsTo(asset) }
                    }
                    val rendering = async { development.await()?.rawRendering() ?: CURRENT_RAW_RENDERING }
                    val bitmap = async {
                        val profiled = rendering.await() >= RAW_RENDERING_PROFILED
                        // A development already recorded keeps its exposure: nothing to measure.
                        loadPhoto(asset, profiled, measure = profiled && development.await()?.rawDevelopment == null,
                            highlight = development.await()?.rawHighlightMode() ?: CURRENT_RAW_HIGHLIGHT)
                    }
                    val appliedParts = (target as? PhotoEditTarget.Recipe)?.source?.appliedParts ?: RecipePart.entries.toSet()
                    val now = Instant.now().toString()
                    val fromStore = stored.await()
                    needsNewerApp = fromStore?.needsNewerApp() == true
                    val storedRecipe = fromStore?.takeIf { !needsNewerApp && it.belongsTo(asset) }
                    val recipe = if (target is PhotoEditTarget.Recipe) {
                        // Seen through `rebasedOnto`, a recipe is exactly what applying it to a photo
                        // would give — the sample shows the truth, not the stored record.
                        val base = EditRecipe(assetId = RECIPE_EDIT_ASSET_ID, originalChecksum = "", createdAt = now, updatedAt = now)
                        storedRecipe?.rebasedOnto(base, now, appliedParts) ?: base
                    } else {
                        storedRecipe?.openedForEditing() ?: EditRecipe(
                            assetId = asset.id,
                            originalChecksum = asset.checksum,
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                    val loaded = bitmap.await()
                    // A recipe with Auto shows the sample with the sample's own light found.
                    val shown = if (target is PhotoEditTarget.Recipe && recipe.whiteBalance.auto) {
                        recipe.withAutoWhiteBalance(autoWhiteBalanceFor(loaded.bitmap))
                    } else {
                        recipe
                    }
                    Triple(loaded, if (loaded.raw != null) shown.developedAsRaw(rendering.await(), loaded.rawExposure) else shown,
                        storedRecipe != null)
                }
            }.onSuccess { (bitmap, recipe, existed) ->
                shouldPersist = target is PhotoEditTarget.Asset && existed
                developedSource = recipe.rawRendering() to recipe.rawEngine()
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
                        bitmap = bitmap.bitmap,
                        highPrecisionSource = bitmap.raw,
                        cameraLight = bitmap.light,
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
        exportOriginChecker.reset()
        activeTarget = target
        loadJob?.cancel()
        mutableState.update { it.copy(asset = target.asset, bitmap = null, highPrecisionSource = null, cameraLight = null, isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            runCatching { loadPhoto(target.asset, state.value.recipe?.appliesBaselineExposure() ?: true, measure = false).bitmap }
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
        exportOriginChecker.reset()
        loadJob?.cancel()
        activeTarget = null
        recipeSessionKey = null
        mutableState.update {
            EditorUiState(
                savedRecipes = it.savedRecipes,
                builtInMarks = it.builtInMarks,
                canPasteRecipe = it.canPasteRecipe,
                recipeParts = it.recipeParts,
                brushTool = it.brushTool,
                retouchTool = it.retouchTool,
            )
        }
    }

    /**
     * Saves the recipe editor's work. A recipe that is already the user's is updated where it is; a
     * filter or a new recipe becomes a new one, under [name] and [collection].
     */
    fun saveRecipeEdits(name: String? = null, collection: String? = null, parts: Set<RecipePart>? = null) {
        val edit = state.value.recipeEdit ?: return
        val current = state.value.recipe ?: return
        if (needsNewerApp) return
        val now = Instant.now().toString()
        val existing = edit.saved
        val saved = if (existing != null) {
            // What was touched here goes with the recipe from now on: changing a part on the sample and
            // then seeing it left out of every photo would be an edit thrown away without a word.
            val applied = existing.appliedParts + edit.baseline.changedParts(current)
            existing.copy(
                recipe = current.rebasedOnto(existing.recipe, now, applied).storedOver(existing.recipe, now),
                updatedAt = now,
                parts = applied,
            )
        } else {
            val cleanName = name?.trim().orEmpty()
            if (cleanName.isBlank()) {
                mutableState.update { it.copy(recipeNotice = uiText(Res.string.editor_recipe_name_needed)) }
                return
            }
            val chosen = parts ?: state.value.recipeParts
            rememberRecipeParts(chosen)
            SavedRecipe(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                collection = collection?.trim().orEmpty(),
                recipe = current.copy(updatedAt = now),
                createdAt = now,
                updatedAt = now,
                parts = chosen,
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
                parts = (edit.saved?.appliedParts ?: RecipePart.LOOK) + edit.baseline.changedParts(current),
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
    fun selectPanel(panel: EditorPanel) = endWhiteBalancePickThen {
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

    fun selectSheet(sheet: EditorSheet) = endWhiteBalancePickThen { it.copy(sheet = sheet) }

    /** Leaving the panel while picking keeps what was picked, as moving on from a slider does. */
    private fun endWhiteBalancePickThen(change: (EditorUiState) -> EditorUiState) {
        if (state.value.isPickingWhiteBalance) acceptWhiteBalancePick()
        mutableState.update(change)
    }

    /**
     * Turns on the White Balance Selector. Everything picked until it closes is one history entry,
     * and cancelling goes back to the white balance there was.
     */
    fun startWhiteBalancePick() {
        val current = state.value.recipe ?: return
        if (state.value.isPickingWhiteBalance) return
        finishAdjustment()
        editStart = current
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_white_balance),
            detail = uiText(Res.string.editor_history_white_balance_picked),
            valueText = null,
            icon = HistoryIcon.COLOR,
        )
        mutableState.update { it.copy(isPickingWhiteBalance = true, whiteBalanceUnreadable = false) }
    }

    /** What the loupe read: the white balance that makes it grey, or null where it could not tell. */
    fun pickWhiteBalance(pick: NeutralWhiteBalance?) {
        if (!state.value.isPickingWhiteBalance) return
        val current = state.value.recipe ?: return
        if (pick == null) {
            mutableState.update { it.copy(whiteBalanceUnreadable = true) }
            return
        }
        val balance = current.whiteBalance.copy(temp = pick.temperature, tint = pick.tint, auto = false)
        if (balance == current.whiteBalance) {
            mutableState.update { it.copy(whiteBalanceUnreadable = false) }
            return
        }
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            whiteBalance = balance,
            updatedAt = Instant.now().toString(),
        )
        mutableState.update { it.copy(recipe = updated, isSaving = true, whiteBalanceUnreadable = false) }
        scheduleSave(updated)
    }

    fun acceptWhiteBalancePick() {
        if (!state.value.isPickingWhiteBalance) return
        mutableState.update { it.copy(isPickingWhiteBalance = false, whiteBalanceUnreadable = false) }
        finishAdjustment()
    }

    fun cancelWhiteBalancePick() {
        if (!state.value.isPickingWhiteBalance) return
        val start = editStart
        editStart = null
        pendingChange = null
        val current = state.value.recipe
        mutableState.update { it.copy(isPickingWhiteBalance = false, whiteBalanceUnreadable = false) }
        if (start != null && current != null && start != current) {
            mutableState.update { it.copy(recipe = start, isSaving = true) }
            scheduleSave(start)
        }
    }

    /** The histogram's triangles: paint, over the photo, what is cut off at one end of the scale. */
    fun toggleClipping(shadows: Boolean) = mutableState.update {
        it.copy(
            clipping = if (shadows) it.clipping.copy(shadows = !it.clipping.shadows)
            else it.clipping.copy(highlights = !it.clipping.highlights),
        )
    }

    /** The RAW's base profile, the first choice of Lightroom's Basic panel. */
    fun selectProfile(profile: RawProfileChoice) {
        val current = state.value.recipe ?: return
        if (current.profile == profile.id) return
        finishAdjustment()
        editStart = null
        pendingChange = null
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            profile = profile.id,
            updatedAt = Instant.now().toString(),
        )
        setRecipeAndSave(updated)
        pushHistory(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = uiText(Res.string.editor_profile),
                detail = UiText.Resource(profile.labelRes),
                valueText = null,
                icon = HistoryIcon.COLOR,
                timestamp = Instant.now().toString(),
                recipe = updated,
            ),
        )
    }

    /**
     * Lightroom's white balance menu for a JPEG. As Shot is the photo as it came; Auto finds the light
     * on the photo, and is remembered as Auto so that the recipe finds it again on any other photo it
     * goes to; Custom keeps the values and lets go of Auto.
     */
    fun selectWhiteBalancePreset(preset: WhiteBalancePreset) {
        val current = state.value.recipe ?: return
        if (state.value.isPickingWhiteBalance) acceptWhiteBalancePick()
        when (preset) {
            WhiteBalancePreset.AS_SHOT -> commitWhiteBalance(WhiteBalance(), preset)
            WhiteBalancePreset.CUSTOM -> commitWhiteBalance(current.whiteBalance.copy(auto = false), preset)
            WhiteBalancePreset.DAYLIGHT, WhiteBalancePreset.CLOUDY, WhiteBalancePreset.SHADE,
            WhiteBalancePreset.TUNGSTEN, WhiteBalancePreset.FLUORESCENT, WhiteBalancePreset.FLASH -> {
                val light = state.value.cameraLight ?: return
                val target = preset.light ?: return
                val (temperature, tint) = light.sliders(target.kelvin, target.tint)
                commitWhiteBalance(WhiteBalance(temp = temperature, tint = tint), preset)
            }
            WhiteBalancePreset.AUTO -> {
                val bitmap = state.value.bitmap ?: return
                viewModelScope.launch {
                    val found = autoWhiteBalanceFor(bitmap)
                    if (state.value.bitmap !== bitmap) return@launch
                    commitWhiteBalance(WhiteBalance(temp = found?.temperature ?: 0f, tint = found?.tint ?: 0f, auto = true), preset)
                }
            }
        }
    }

    private fun commitWhiteBalance(balance: WhiteBalance, preset: WhiteBalancePreset) {
        val current = state.value.recipe ?: return
        if (balance == current.whiteBalance) return
        finishAdjustment()
        editStart = null
        pendingChange = null
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            whiteBalance = balance,
            updatedAt = Instant.now().toString(),
        )
        setRecipeAndSave(updated)
        pushHistory(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = uiText(Res.string.editor_white_balance),
                detail = UiText.Resource(preset.labelRes),
                valueText = null,
                icon = HistoryIcon.COLOR,
                timestamp = Instant.now().toString(),
                recipe = updated,
            ),
        )
    }

    private suspend fun autoWhiteBalanceFor(bitmap: Bitmap): NeutralWhiteBalance? {
        autoWhiteBalance?.takeIf { it.first === bitmap }?.let { return it.second }
        val found = withContext(Dispatchers.Default) { autoWhiteBalanceOf(bitmap.toPixelBuffer()) }
        autoWhiteBalance = bitmap to found
        return found
    }

    fun selectHslBand(band: HslColorBand) = mutableState.update { it.copy(selectedHslBand = band) }

    fun selectCurveChannel(channel: CurveChannel) = mutableState.update { it.copy(curveChannel = channel) }

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
            ?.takeIf { adjustment.isMaskSlider() && snapshot.sheet == EditorSheet.MASK_ADJUSTMENTS }
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
            current.maskSliderValue(maskId, adjustment)
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
            active.withMaskSlider(maskId, adjustment, clamped)
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
        var current = state.value.recipe ?: return
        val change = pendingChange
        editStart = null
        pendingChange = null
        if (start == current || change == null) return
        var detectUpright: String? = null
        if (change is PendingChange.OfAdjustment && change.adjustment == Adjustment.PERSPECTIVE_DISTORTION) {
            val (resolved, pending) = current.withUprightAfterDistortion()
            if (resolved != current) {
                current = resolved
                setRecipeAndSave(resolved)
            }
            detectUpright = pending
        }
        val entry = when (change) {
            is PendingChange.OfAdjustment -> HistoryEntry(
                id = UUID.randomUUID().toString(),
                label = UiText.Resource(change.adjustment.labelRes),
                detail = change.maskId?.let { id -> current.mask(id)?.name?.let { uiText(Res.string.editor_history_mask_named, it) } }
                    ?: uiText(Res.string.editor_history_manual),
                valueText = (
                    change.maskId?.let { formatAdjustmentValue(change.adjustment, current.maskSliderValue(it, change.adjustment)) }
                        ?: state.value.copy(recipe = current, selectedMaskId = null)
                            .formatValue(change.adjustment, current.adjustmentValue(change.adjustment))
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
        detectUpright?.let(::applyAutomaticUpright)
    }

    /**
     * The Upright solved again after the distortion moved, as after a quarter turn: guides and lines
     * are measured with the distortion taken away, and lines that were straight before bend with it.
     * The guides are this photo's and solve at once, and so do lines already found; an automatic mode
     * whose lines are not at hand comes back as the mode to detect again, after the step is recorded.
     */
    private fun EditRecipe.withUprightAfterDistortion(): Pair<EditRecipe, String?> {
        val upright = geometry.perspective.upright
        val lines = detectedLines?.takeIf { it.first === state.value.bitmap }?.second
        return when {
            upright == UPRIGHT_GUIDED -> withSolvedGuides(geometry.perspective.guides) to null
            upright in AUTOMATIC_UPRIGHT_MODES && lines != null -> withAutomaticUpright(lines).first to null
            upright in AUTOMATIC_UPRIGHT_MODES -> this to upright
            else -> this to null
        }
    }

    fun resetAdjustment(adjustment: Adjustment) {
        updateAdjustment(adjustment, adjustment.neutral)
        finishAdjustment()
    }

    fun addCurvePoint(x: Int, y: Int) {
        val current = state.value.recipe ?: return
        val channel = state.value.curveChannel
        val points = current.editableCurvePoints(channel)
        val updatedPoints = points.withAddedCurvePoint(x, y)
        if (updatedPoints == points) return
        beginCurveEdit(current, channel)
        applyCurvePoints(current, channel, updatedPoints)
    }

    fun moveCurvePoint(index: Int, x: Int, y: Int) {
        val current = state.value.recipe ?: return
        val channel = state.value.curveChannel
        val points = current.editableCurvePoints(channel)
        if (index !in points.indices) return
        beginCurveEdit(current, channel)
        val updated = points.withMovedCurvePoint(index, x, y)
        if (updated == points) return
        applyCurvePoints(current, channel, updated)
    }

    fun removeCurvePoint(index: Int) {
        val current = state.value.recipe ?: return
        val channel = state.value.curveChannel
        val points = current.editableCurvePoints(channel)
        val updatedPoints = points.withRemovedCurvePoint(index)
        if (updatedPoints == points) return
        beginCurveEdit(current, channel)
        applyCurvePoints(current, channel, updatedPoints)
        finishAdjustment()
    }

    /** Puts back the curve on screen — the composite or one channel's — and leaves the others. */
    fun resetCurve() {
        val current = state.value.recipe ?: return
        val channel = state.value.curveChannel
        if (current.editableCurvePoints(channel) == IDENTITY_CURVE) return
        beginCurveEdit(current, channel, detail = uiText(Res.string.editor_history_reset_f))
        applyCurvePoints(current, channel, IDENTITY_CURVE)
        finishAdjustment()
    }

    private fun beginCurveEdit(current: EditRecipe, channel: CurveChannel, detail: UiText? = null) {
        if (editStart != null) return
        editStart = current
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_chip_curve),
            detail = detail ?: if (channel == CurveChannel.RGB) uiText(Res.string.editor_tone_curve) else UiText.Resource(channel.labelRes),
            valueText = null,
            icon = HistoryIcon.CURVE,
        )
    }

    private fun applyCurvePoints(
        current: EditRecipe,
        channel: CurveChannel,
        points: List<eu.studio742.imago.core.model.CurvePoint>,
    ) {
        val updated = current.copy(
            processVersion = CURRENT_PROCESS_VERSION,
            geometry = current.geometry.activeAtProcess(current.processVersion),
            toneCurve = current.toneCurve.withPoints(channel, points),
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

    /** Copies the [parts] chosen in the dialog; pasting writes only those over the other photo. */
    fun copyCurrentRecipe(parts: Set<RecipePart>) {
        copiedRecipe = state.value.recipe ?: return
        copiedParts = parts
        rememberRecipeParts(parts)
        mutableState.update { it.copy(canPasteRecipe = true, recipeNotice = uiText(Res.string.editor_recipe_copied)) }
    }

    fun pasteCopiedRecipe() {
        val source = copiedRecipe ?: return
        applyReusableRecipe(source, copiedParts, uiText(Res.string.editor_recipe_pasted))
    }

    fun applySavedRecipe(id: String) {
        val saved = state.value.savedRecipes.firstOrNull { it.id == id } ?: return
        applyReusableRecipe(
            saved.recipe,
            saved.appliedParts,
            uiText(Res.string.editor_recipe_applied, saved.name),
            label = saved.name.asUiText(),
        )
        // The "Recent" tab only exists because this is recorded; without a stamp the recipe would
        // never get there, however much it was used.
        viewModelScope.launch {
            runCatching { savedRecipes.save(saved.copy(usedAt = Instant.now().toString())) }
            refreshSavedRecipes()
        }
    }

    /** Applies one of the app's presets. The preset is code; only its last use is recorded. */
    fun applyBuiltInRecipe(id: String) {
        val builtIn = BUILT_IN_RECIPES.firstOrNull { it.id == id } ?: return
        // The app's filters are looks: they never crop, tilt or mask the photo they are put on.
        applyReusableRecipe(
            builtIn.recipe,
            RecipePart.BUILT_IN,
            uiText(Res.string.editor_filter_applied, builtIn.nameText()),
            label = builtIn.nameText(),
            fromPhoto = false,
        )
        val mark = state.value.builtInMarks.firstOrNull { it.id == id } ?: BuiltInRecipeMark(id)
        viewModelScope.launch {
            runCatching { savedRecipes.saveBuiltInMark(mark.copy(usedAt = Instant.now().toString())) }
            refreshSavedRecipes()
        }
    }

    fun toggleBuiltInRecipeFavorite(id: String) {
        val mark = state.value.builtInMarks.firstOrNull { it.id == id } ?: BuiltInRecipeMark(id)
        viewModelScope.launch {
            runCatching { savedRecipes.saveBuiltInMark(mark.copy(isFavorite = !mark.isFavorite)) }
            refreshSavedRecipes()
        }
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

    fun saveCurrentRecipe(name: String, collection: String, parts: Set<RecipePart>) {
        val current = state.value.recipe ?: return
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            mutableState.update { it.copy(recipeNotice = uiText(Res.string.editor_recipe_name_needed)) }
            return
        }
        rememberRecipeParts(parts)
        val now = Instant.now().toString()
        val saved = SavedRecipe(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            // The default collection comes already written in the person's language (RecipeDetailsDialog).
            collection = collection.trim(),
            recipe = current,
            createdAt = now,
            updatedAt = now,
            parts = parts,
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

    fun updateSavedRecipe(id: String, name: String, collection: String, parts: Set<RecipePart>) {
        val existing = state.value.savedRecipes.firstOrNull { it.id == id } ?: return
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val updated = existing.copy(
            name = cleanName,
            collection = collection.trim(),
            updatedAt = Instant.now().toString(),
            parts = existing.partsAfterChoosing(parts),
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

    private fun rememberRecipeParts(parts: Set<RecipePart>) {
        configuration.rememberRecipeParts(parts)
        mutableState.update { it.copy(recipeParts = parts) }
    }

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
        redevelopIfNeeded(restored)
    }

    /**
     * The original decoded for [recipe]'s development. Undoing an upgrade goes back to a recipe that the
     * pixels on screen were not decoded for: they are decoded again, as when the photo was opened.
     */
    private fun redevelopIfNeeded(recipe: EditRecipe) {
        val asset = state.value.asset ?: return
        if (state.value.highPrecisionSource?.linear != true) return
        val wanted = recipe.rawRendering() to recipe.rawEngine()
        if (wanted == developedSource) return
        developedSource = wanted
        redevelopJob?.cancel()
        redevelopJob = viewModelScope.launch {
            runCatching {
                loadPhoto(asset, recipe.appliesBaselineExposure(), measure = false, highlight = recipe.rawHighlightMode())
            }.onSuccess { loaded ->
                mutableState.update {
                    it.copy(bitmap = loaded.bitmap, highPrecisionSource = loaded.raw ?: it.highPrecisionSource, cameraLight = loaded.light ?: it.cameraLight)
                }
            }.onFailure { error ->
                mutableState.update { it.copy(recipeNotice = error.toUiText(Res.string.editor_open_failed)) }
            }
        }
    }

    /**
     * Moves a RAW kept under an older development to the current one, with the Exposure moved so that the
     * grey stays where it was. A step of the history like any other: undoing it brings the old one back.
     */
    fun upgradeDevelopment() {
        val current = state.value.recipe ?: return
        val asset = state.value.asset ?: return
        if (!state.value.canUpgradeDevelopment || state.value.isUpgradingDevelopment) return
        finishAdjustment()
        editStart = null
        pendingChange = null
        mutableState.update { it.copy(isUpgradingDevelopment = true) }
        viewModelScope.launch {
            runCatching {
                // Rendering 1 needs the original measured; from rendering 2 on, the exposure is already kept.
                loadPhoto(asset, baselineExposure = true, measure = current.rawRendering() == RAW_RENDERING_LINEAR, highlight = CURRENT_RAW_HIGHLIGHT)
            }.onSuccess { loaded ->
                val latest = state.value.recipe ?: current
                val upgraded = RawDevelopmentUpgrade.upgrade(latest, loaded.rawExposure, loaded.baselineExposure)
                if (upgraded == null || loaded.raw == null) {
                    mutableState.update { it.copy(isUpgradingDevelopment = false) }
                    return@onSuccess
                }
                val stamped = upgraded.copy(updatedAt = Instant.now().toString())
                developedSource = stamped.rawRendering() to stamped.rawEngine()
                mutableState.update {
                    it.copy(bitmap = loaded.bitmap, highPrecisionSource = loaded.raw, cameraLight = loaded.light ?: it.cameraLight, isUpgradingDevelopment = false)
                }
                setRecipeAndSave(stamped)
                pushHistory(
                    HistoryEntry(
                        id = UUID.randomUUID().toString(),
                        label = uiText(Res.string.editor_development_upgrade),
                        detail = uiText(Res.string.editor_development_upgraded),
                        valueText = null,
                        icon = HistoryIcon.RECIPE,
                        timestamp = Instant.now().toString(),
                        recipe = stamped,
                    ),
                )
            }.onFailure { error ->
                mutableState.update { it.copy(isUpgradingDevelopment = false, recipeNotice = error.toUiText(Res.string.editor_open_failed)) }
            }
        }
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
            keepServerRawEdit(recipe)
        }
    }

    /**
     * A server RAW's edit, rendered over the development the editor has, for the grid and the viewer to
     * show without downloading the original ([LibraryRawEdits]). A phone RAW is developed by the grid itself.
     */
    private fun keepServerRawEdit(recipe: EditRecipe) {
        val asset = state.value.asset ?: return
        val development = state.value.highPrecisionSource ?: return
        if (asset.apiKey.isBlank() || !shouldInspectRawOriginal(asset.fileName)) return
        LibraryRawEdits.keep(LibraryRaw(asset.id, asset.checksum), recipe, development, rawExposure = 0f)
    }

    /** The style the last "Show your edit" video had: the sheet opens on it. */
    val reelStyle: ReelStyle
        get() = configuration.reelStyle?.let { name -> ReelStyle.entries.firstOrNull { it.name == name } } ?: ReelStyle.PANEL

    fun rememberReelStyle(style: ReelStyle) = configuration.setReelStyle(style.name)

    fun exportToGallery() = export { asset, jpeg, _ ->
        exporter.saveToDevice(jpeg, exportFileName(asset), asset.fileCreatedAt)
    }

    /** The edited photo to another app, through [share]: the same export the other destinations read. */
    fun shareEdited(share: eu.studio742.imago.feature.library.SelectionShare) = export(background = false) { asset, jpeg, _ ->
        val folder = share.prepare() ?: return@export null
        val file = File(folder, exportFileName(asset))
        withContext(Dispatchers.IO) { jpeg.copyTo(file, overwrite = true) }
        share.deliver(listOf(file), "image/jpeg")
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
            if (result.stackedWithOriginal) runCatching { library?.stackChanged(asset.id) }
            val saved = recipe.copy(derivedAssetId = result.assetId, exportedLook = recipe.lookFingerprint(), updatedAt = Instant.now().toString())
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

    /**
     * [background]: the export carries on when the editor is left ([PhotoExports]), with a notification. A
     * share opens the system's sheet when it is done, which only makes sense with the app in front.
     */
    private fun export(
        before: suspend () -> Unit = {},
        background: Boolean = true,
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
        val work: suspend ((UiText) -> Unit) -> UiText? = { onPhase ->
            before()
            val jpeg = exporter.renderJpegOnce(asset, recipe) { phase ->
                onPhase(phase)
                mutableState.update { it.copy(exportPhase = phase) }
            }
            try {
                block(asset, jpeg, recipe)
            } finally {
                jpeg.delete()
            }
        }
        val finish: (Result<UiText?>) -> Unit = { result ->
            result.onSuccess { message ->
                mutableState.update { it.copy(isExporting = false, exportPhase = null, exportMessage = message) }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isExporting = false,
                        exportPhase = null,
                        exportError = if (error is CancellationException) null else error.toUiText(Res.string.editor_export_failed),
                    )
                }
            }
        }
        if (background && PhotoExports.start(context, work, finish)) return
        viewModelScope.launch { finish(runCatching { work {} }) }
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

    private fun applyReusableRecipe(
        source: EditRecipe,
        parts: Set<RecipePart>,
        message: UiText,
        label: UiText = uiText(Res.string.editor_history_recipe),
        fromPhoto: Boolean = true,
    ) {
        val current = state.value.recipe ?: return
        editStart = null
        pendingChange = null
        val rebased = source.rebasedOnto(current, Instant.now().toString(), parts)
        // The white balance travels as the light's difference from As Shot. Between a RAW and a JPEG that
        // is the nearest thing to the same light, and Lightroom says it may not match; so does this.
        val sourceRaw = source.rawDevelopment != null
        val targetRaw = state.value.highPrecisionSource?.linear == true
        val notice = if (fromPhoto && RecipePart.WHITE_BALANCE in parts && sourceRaw != targetRaw &&
            !source.whiteBalance.isAsShot && !source.whiteBalance.auto
        ) {
            uiText(if (sourceRaw) Res.string.editor_white_balance_from_raw else Res.string.editor_white_balance_from_jpeg)
        } else {
            message
        }
        val bitmap = state.value.bitmap
        if (!rebased.whiteBalance.auto || bitmap == null) {
            commitReusableRecipe(rebased, notice, label)
            return
        }
        // An Auto white balance travels as Auto: this photo's light is found, as Lightroom does when
        // it syncs one.
        viewModelScope.launch {
            val found = autoWhiteBalanceFor(bitmap)
            if (state.value.bitmap !== bitmap) return@launch
            commitReusableRecipe(rebased.withAutoWhiteBalance(found), notice, label)
        }
    }

    private fun commitReusableRecipe(applied: EditRecipe, message: UiText, label: UiText) {
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

    fun selectMask(maskId: String?) = selectMaskComponent(maskId, 0)

    /** Opens a mask on one of its components: the sliders below, that component's handles on the photo. */
    fun selectMaskComponent(maskId: String?, component: Int) = mutableState.update {
        it.copy(
            selectedMaskId = maskId,
            selectedMaskComponent = component,
            sheet = if (maskId == null) EditorSheet.MASKS else EditorSheet.MASK_ADJUSTMENTS,
            maskOverlayPinned = if (maskId == null) false else it.maskOverlayPinned,
        )
    }

    /** A tap on another component's pin, on the photo: the handles move to it, the sliders stay. */
    fun focusMaskComponent(component: Int) = mutableState.update { it.copy(selectedMaskComponent = component) }

    fun showMaskList() = mutableState.update {
        it.copy(sheet = EditorSheet.MASKS, selectedMaskId = null, maskOverlayPinned = false)
    }

    fun setMaskOverlayPinned(pinned: Boolean) = mutableState.update { it.copy(maskOverlayPinned = pinned) }

    fun dismissMaskNotice() = mutableState.update { it.copy(maskNotice = null) }

    /**
     * Creates a mask and opens it right away on the adjustments: a mask without adjustments does
     * nothing, and leaving the user on the list after creating it meant asking one more tap to get there.
     * [placement] is the part of the photo on screen, which the new shape is centred on and sized to.
     */
    fun addMask(shape: MaskShape, placement: MaskPlacement = MaskPlacement.WholeImage) {
        val current = state.value.recipe ?: return
        val name = defaultMaskName(shape, current.masks)
        val (updated, id) = current.withNewMask(shape, name, placement)
        if (id == null) {
            mutableState.update {
                it.copy(maskNotice = uiText(Res.string.editor_masks_max, MAX_LOCAL_MASKS))
            }
            return
        }
        commitMask(updated, detail = uiText(Res.string.editor_history_mask_created), valueText = name.asUiText())
        mutableState.update {
            it.copy(
                selectedMaskId = id,
                selectedMaskComponent = 0,
                sheet = EditorSheet.MASK_ADJUSTMENTS,
                maskOverlayPinned = true,
            )
        }
    }

    /**
     * Lightroom's Add, Subtract and Intersect: a new shape in the mask, opened right away on its
     * handles, with the red on — a subtraction cannot be placed without seeing what it takes away.
     */
    fun addMaskComponent(
        maskId: String,
        shape: MaskShape,
        op: MaskOp,
        placement: MaskPlacement = MaskPlacement.WholeImage,
    ) {
        val current = state.value.recipe ?: return
        val (updated, index) = current.withNewMaskComponent(maskId, shape, op, placement)
        if (index == null) {
            if (current.mask(maskId) != null) {
                mutableState.update { it.copy(maskNotice = uiText(Res.string.editor_mask_components_max, MAX_MASK_COMPONENTS)) }
            }
            return
        }
        val detail = when (op) {
            MaskOp.ADD -> Res.string.editor_history_mask_shape_added
            MaskOp.SUBTRACT -> Res.string.editor_history_mask_shape_subtracted
            MaskOp.INTERSECT -> Res.string.editor_history_mask_shape_intersected
        }
        commitMask(updated, detail = uiText(detail), valueText = current.mask(maskId)?.name?.asUiText())
        mutableState.update {
            it.copy(
                selectedMaskId = maskId,
                selectedMaskComponent = index,
                sheet = EditorSheet.MASK_ADJUSTMENTS,
                maskOverlayPinned = true,
            )
        }
    }

    /** Removing the last shape removes the mask, as in Lightroom. */
    fun deleteMaskComponent(maskId: String, component: Int) {
        val current = state.value.recipe ?: return
        val mask = current.mask(maskId) ?: return
        if (mask.components.size == 1) {
            deleteMask(maskId)
            return
        }
        commitMask(
            current.withoutMaskComponent(maskId, component),
            detail = uiText(Res.string.editor_history_mask_shape_removed),
            valueText = mask.name.asUiText(),
        )
        mutableState.update {
            if (it.selectedMaskId == maskId && it.selectedMaskComponent >= component) {
                it.copy(selectedMaskComponent = (it.selectedMaskComponent - 1).coerceAtLeast(0))
            } else {
                it
            }
        }
    }

    fun setMaskComponentInverted(maskId: String, component: Int, inverted: Boolean) {
        val current = state.value.recipe ?: return
        commitMask(
            current.withMaskComponentInverted(maskId, component, inverted),
            detail = uiText(if (inverted) Res.string.editor_history_mask_shape_inverted else Res.string.editor_history_mask_shape_normal),
            valueText = current.mask(maskId)?.name?.asUiText(),
        )
    }

    /** Duplicate, and Duplicate and Invert: the copy goes right after the original and is not opened. */
    fun duplicateMask(maskId: String, invert: Boolean) {
        val current = state.value.recipe ?: return
        val (updated, id) = current.withDuplicatedMask(maskId, invert)
        if (id == null) {
            if (current.mask(maskId) == null) return
            val notice = if (current.masks.size >= MAX_LOCAL_MASKS) {
                uiText(Res.string.editor_masks_max, MAX_LOCAL_MASKS)
            } else {
                uiText(Res.string.editor_mask_components_max, MAX_MASK_COMPONENTS)
            }
            mutableState.update { it.copy(maskNotice = notice) }
            return
        }
        commitMask(
            updated,
            detail = uiText(if (invert) Res.string.editor_history_mask_duplicated_inverted else Res.string.editor_history_mask_duplicated),
            valueText = updated.mask(id)?.name?.asUiText(),
        )
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
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_moved)) {
            it.withMaskMoved(maskId, deltaX, deltaY, component = state.value.selectedComponent)
        }

    fun setMaskRadius(maskId: String, radiusX: Float? = null, radiusY: Float? = null) =
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_resized)) {
            it.withMaskRadius(maskId, radiusX, radiusY, component = state.value.selectedComponent)
        }

    fun setMaskWidth(maskId: String, width: Float) =
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_resized)) {
            it.withMaskWidth(maskId, width, component = state.value.selectedComponent)
        }

    fun rotateMask(maskId: String, degrees: Float) =
        editMaskGeometry(maskId, uiText(Res.string.editor_history_rotated)) {
            it.withMaskRotation(maskId, degrees, component = state.value.selectedComponent)
        }

    // --- brush ----------------------------------------------------------------------------------

    fun setBrushErasing(erasing: Boolean) = updateBrushTool { it.copy(erasing = erasing) }

    fun setBrushAutoMask(on: Boolean) = updateBrushTool { it.withActive(it.active.copy(autoMask = on)) }

    /** A brush slider being dragged: kept in the state, written to the device when the finger lifts. */
    fun setBrushSetting(setting: BrushSetting, value: Float) = mutableState.update {
        it.copy(brushTool = it.brushTool.withActive(it.brushTool.active.with(setting, value)))
    }

    fun finishBrushSetting() = configuration.setBrushTool(state.value.brushTool.encode())

    private fun updateBrushTool(change: (BrushTool) -> BrushTool) {
        mutableState.update { it.copy(brushTool = change(it.brushTool)) }
        configuration.setBrushTool(state.value.brushTool.encode())
    }

    /**
     * A finger lands on the photo with a brush selected: a stroke starts, with the brush in the hand.
     * [x] and [y] are in normalised image coordinates, [radius] in units of the image's height — the
     * screen's brush already measured on the photo as it is shown.
     *
     * The stroke is one history entry, closed by [finishAdjustment] when the finger lifts.
     */
    fun beginBrushStroke(maskId: String, x: Float, y: Float, radius: Float) {
        val snapshot = state.value
        val component = snapshot.selectedComponent
        if (snapshot.recipe?.mask(maskId)?.components?.getOrNull(component)?.shape != MaskShape.BRUSH) return
        val tool = snapshot.brushTool
        val settings = tool.active
        val stroke = BrushStroke(
            points = listOf(roundedBrushCoordinate(x), roundedBrushCoordinate(y)),
            radius = radius,
            feather = settings.feather,
            flow = settings.flow,
            density = settings.density,
            erase = tool.erasing,
            autoMask = settings.autoMask,
        )
        val detail = if (tool.erasing) Res.string.editor_history_mask_erased else Res.string.editor_history_mask_painted
        editMaskGeometry(maskId, uiText(detail)) { it.withBrushStroke(maskId, component, stroke) }
    }

    fun extendBrushStroke(maskId: String, x: Float, y: Float) {
        if (editStart == null) return
        val component = state.value.selectedComponent
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_painted)) {
            it.withBrushStrokeExtended(maskId, component, x, y)
        }
    }

    // --- remove ---------------------------------------------------------------------------------

    /**
     * The mode the next stroke is painted in. A correction already made keeps its own: with the one just
     * painted still selected, choosing the next mode would otherwise turn it into something else.
     */
    fun setRetouchMode(mode: RetouchMode) = updateRetouchTool { it.copy(mode = mode) }

    /**
     * A slider being dragged. Size is the brush's, for the next stroke, and Spots the view's; Feather and
     * Opacity also move the selected correction, in one history entry per drag.
     */
    fun setRetouchSetting(setting: RetouchSetting, value: Float) {
        mutableState.update { it.copy(retouchTool = it.retouchTool.with(setting, value)) }
        val id = state.value.selectedRetouchId ?: return
        if (setting == RetouchSetting.SIZE || setting == RetouchSetting.SPOTS || state.value.isSolvingRetouch) return
        val clamped = value.coerceIn(setting.range.start, setting.range.endInclusive)
        beginRetouchGesture(uiText(Res.string.editor_history_retouch_changed))
        editRetouches { recipe ->
            recipe.withRetouchChanged(id) { retouch ->
                when (setting) {
                    RetouchSetting.OPACITY -> retouch.copy(opacity = clamped)
                    else -> retouch.copy(strokes = retouch.strokes.map { it.copy(feather = clamped) })
                }
            }
        }
    }

    fun finishRetouchSetting() {
        configuration.setRetouchTool(state.value.retouchTool.encode())
        finishAdjustment()
    }

    private fun updateRetouchTool(change: (RetouchTool) -> RetouchTool) {
        mutableState.update { it.copy(retouchTool = change(it.retouchTool)) }
        configuration.setRetouchTool(state.value.retouchTool.encode())
    }

    fun selectRetouch(id: String?) {
        if (id != state.value.selectedRetouchId) closeExtendWindow()
        mutableState.update { it.copy(selectedRetouchId = id, retouchNotice = null) }
    }

    fun setShowsRetouches(shown: Boolean) = mutableState.update { it.copy(showsRetouches = shown) }

    fun setVisualizesSpots(on: Boolean) = mutableState.update { it.copy(visualizesSpots = on) }

    /**
     * A finger lands on the photo away from every pin: a new correction starts, in the tool's mode.
     * [x] and [y] are in normalised image coordinates, [radius] in units of the image's height.
     *
     * Nothing changes on the photo while it is painted: a correction has no content until the stroke
     * ends ([finishRetouchGesture]), and only then does it become a history entry.
     */
    fun beginRetouchStroke(x: Float, y: Float, radius: Float) {
        val current = state.value.recipe ?: return
        if (editStart != null) return
        val tool = state.value.retouchTool
        val joined = extendable?.takeIf { (_, until) -> clock() <= until }?.first
            ?.let { current.retouch(it) }
            ?.takeIf { it.mode == tool.mode && it.mode != RetouchMode.REMOVE && it.id == state.value.selectedRetouchId }
        closeExtendWindow()
        if (joined != null) {
            extendingRetouch = true
            beginRetouchGesture(uiText(Res.string.editor_history_retouch_extended))
            mutableState.update { it.copy(retouchNotice = null, showsRetouches = true) }
            editRetouches { recipe ->
                recipe.withRetouchChanged(joined.id) { it.copy(strokes = it.strokes + tool.firstStroke(x, y, radius)) }
            }
            return
        }
        extendingRetouch = false
        val retouch = Retouch(
            id = UUID.randomUUID().toString(),
            mode = tool.mode,
            strokes = listOf(tool.firstStroke(x, y, radius)),
            opacity = tool.opacity,
        )
        editStart = current
        pendingChange = null
        // What is being painted has to be seen.
        mutableState.update { it.copy(selectedRetouchId = retouch.id, retouchNotice = null, showsRetouches = true) }
        editRetouches { it.withRetouch(retouch) }
    }

    fun extendRetouchStroke(x: Float, y: Float) {
        if (editStart == null) return
        val id = state.value.selectedRetouchId ?: return
        editRetouches { it.withRetouchStrokeExtended(id, x, y) }
    }

    /** A second finger turns the stroke into a pinch: the correction it was painting goes away. */
    fun cancelRetouchStroke() {
        val start = editStart ?: return
        editStart = null
        pendingChange = null
        val keepSelection = extendingRetouch
        extendingRetouch = false
        mutableState.update { it.copy(recipe = start, selectedRetouchId = it.selectedRetouchId.takeIf { keepSelection }, isSaving = true) }
        scheduleSave(start)
        applyDeferredRetouches()
    }

    /** The selected correction's source dragged to an offset from its area, in image units. */
    fun moveRetouchSource(id: String, sourceX: Float, sourceY: Float) {
        if (state.value.isSolvingRetouch) return
        beginRetouchGesture(uiText(Res.string.editor_history_retouch_source_moved))
        editRetouches { recipe ->
            recipe.withRetouchChanged(id) { it.copy(sourceX = sourceX, sourceY = sourceY) }
        }
    }

    /** The selected correction's area dragged by [dx], [dy] since the last call, in image units. */
    fun moveRetouch(id: String, dx: Float, dy: Float) {
        if (state.value.isSolvingRetouch || (dx == 0f && dy == 0f)) return
        beginRetouchGesture(uiText(Res.string.editor_history_retouch_moved))
        editRetouches { recipe -> recipe.withRetouchChanged(id) { it.movedBy(dx, dy) } }
    }

    /**
     * The finger lifts. A correction still without content (a new stroke, or a Remove moved to another
     * place) has it worked out now, and becomes a history entry when it has; any other gesture closes
     * as a drag does.
     */
    fun finishRetouchGesture() {
        val retouch = state.value.selectedRetouch
        extendingRetouch = false
        if (editStart != null && retouch != null && retouch.mode != RetouchMode.REMOVE) {
            extendable = retouch.id to clock() + RETOUCH_EXTEND_MILLIS
            mutableState.update { it.copy(extendableRetouchId = retouch.id) }
            extendWindowJob?.cancel()
            extendWindowJob = viewModelScope.launch {
                delay(RETOUCH_EXTEND_MILLIS)
                mutableState.update { it.copy(extendableRetouchId = null) }
            }
        }
        if (editStart != null && retouch != null && !with(Retouching) { retouch.draws() }) {
            val detail = (pendingChange as? PendingChange.Fixed)?.detail ?: uiText(retouch.mode.labelRes)
            editStart = null
            pendingChange = null
            solveAndCommit(retouch, detail)
        } else {
            finishAdjustment()
        }
        applyDeferredRetouches()
    }

    private fun applyDeferredRetouches() {
        if (editStart != null) return
        val waiting = deferredRetouches.values.toList()
        deferredRetouches.clear()
        waiting.forEach { it() }
    }

    fun deleteRetouch(id: String) {
        val current = state.value.recipe ?: return
        mutableState.update { it.copy(selectedRetouchId = null) }
        commitRetouches(current.withoutRetouch(id), uiText(Res.string.editor_history_retouch_deleted))
    }

    /** A Remove filled again from other places in the photo: Lightroom's Refresh. */
    fun refreshRetouch(id: String) {
        val retouch = state.value.recipe?.retouch(id)?.takeIf { it.mode == RetouchMode.REMOVE } ?: return
        if (state.value.isSolvingRetouch) return
        solveAndCommit(retouch.copy(fill = null), uiText(Res.string.editor_history_retouch_refreshed), seed = Random.nextLong())
    }

    private fun beginRetouchGesture(detail: UiText) {
        if (editStart != null) return
        editStart = state.value.recipe ?: return
        pendingChange = PendingChange.Fixed(
            label = uiText(Res.string.editor_tool_remove),
            detail = detail,
            valueText = null,
            icon = HistoryIcon.REMOVE,
        )
    }

    /** Mid-gesture: the recipe moves, the history waits for the finger to lift. */
    private fun editRetouches(transform: (EditRecipe) -> EditRecipe) {
        val current = state.value.recipe ?: return
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

    /** A change that is whole on its own: a correction solved, deleted or turned into another mode. */
    private fun commitRetouches(updated: EditRecipe, detail: UiText) {
        val current = state.value.recipe ?: return
        if (updated.retouches == current.retouches) return
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
                label = uiText(Res.string.editor_tool_remove),
                detail = detail,
                valueText = null,
                icon = HistoryIcon.REMOVE,
                timestamp = Instant.now().toString(),
                recipe = committed,
            ),
        )
    }

    /**
     * Works out what [retouch] lacks (a Remove's fill, a Heal's or a Clone's source) off the main
     * thread, and writes it into the recipe as one history entry. When the photo has nothing to take it
     * from, the correction goes and the panel says why.
     */
    private fun solveAndCommit(retouch: Retouch, detail: UiText, seed: Long = 0L) {
        val bitmap = state.value.bitmap ?: return
        retouchJobs.remove(retouch.id)?.cancel()
        // Worked out again (a stroke joined it): what an earlier run found no longer fits.
        deferredRetouches.remove(retouch.id)
        mutableState.update { it.copy(isSolvingRetouch = true, retouchNotice = null) }
        val previous = state.value.recipe
        lateinit var job: Job
        job = viewModelScope.launch {
            try {
                val earlier = previous?.retouches.orEmpty().takeWhile { it.id != retouch.id }
                val reference = retouchReferenceFor(bitmap, earlier)
                val solved = withContext(Dispatchers.Default) { solveRetouch(retouch, reference, seed) }
                if (state.value.bitmap !== bitmap) return@launch
                val write = { writeSolved(retouch, solved, detail) }
                if (editStart == null) write() else deferredRetouches[retouch.id] = write
            } finally {
                if (retouchJobs[retouch.id] === job) retouchJobs.remove(retouch.id)
                mutableState.update { it.copy(isSolvingRetouch = retouchJobs.isNotEmpty()) }
            }
        }
        retouchJobs[retouch.id] = job
    }

    private fun writeSolved(retouch: Retouch, solved: Retouch?, detail: UiText) {
        val current = state.value.recipe ?: return
        // Undone while it was being worked out: there is nothing left to give it to.
        if (current.retouch(retouch.id) == null) return
        if (solved != null) {
            val merged = current.withRetouchChanged(retouch.id) {
                it.copy(sourceX = solved.sourceX, sourceY = solved.sourceY, fill = solved.fill)
            }
            commitRetouches(merged, detail)
            return
        }
        mutableState.update {
            it.copy(
                selectedRetouchId = it.selectedRetouchId.takeUnless { id -> id == retouch.id },
                retouchNotice = uiText(Res.string.editor_remove_nothing_to_take),
            )
        }
        if (history.current?.recipe?.retouch(retouch.id) != null) {
            commitRetouches(current.withoutRetouch(retouch.id), uiText(Res.string.editor_history_retouch_deleted))
        } else {
            // A new correction that never was: back to before it, with no entry.
            setRecipeAndSave(current.withoutRetouch(retouch.id))
        }
    }

    private fun solveRetouch(retouch: Retouch, reference: BrushReference, seed: Long): Retouch? = when (retouch.mode) {
        RetouchMode.REMOVE -> Retouching.solveRemove(reference, retouch.strokes, seed)?.let { retouch.copy(fill = it) }
        else -> if (with(Retouching) { retouch.draws() }) {
            retouch
        } else {
            Retouching.proposeSource(reference, retouch.strokes)?.let { (dx, dy) -> retouch.copy(sourceX = dx, sourceY = dy) }
        }
    }

    private suspend fun retouchReferenceFor(bitmap: Bitmap, earlier: List<Retouch>): BrushReference {
        retouchReference?.takeIf { it.first === bitmap && it.second == earlier }?.let { return it.third }
        val reference = withContext(Dispatchers.Default) { BrushReference.of(Retouching.apply(bitmap.toPixelBuffer(), earlier)) }
        retouchReference = Triple(bitmap, earlier, reference)
        return reference
    }

    // --- ranges ---------------------------------------------------------------------------------

    fun setRangeAddsSamples(on: Boolean) = mutableState.update { it.copy(rangeAddsSamples = on) }

    /**
     * A tap or a drag on the photo with a range selected: a point when [width] and [height] are zero,
     * else the area they span around [x], [y] — all in normalised image coordinates.
     *
     * The colours are read from the same reference the engine paints the range from, so that what is
     * sampled is exactly what the mask then looks for. Each sample is one history entry.
     */
    fun sampleRange(maskId: String, x: Float, y: Float, width: Float, height: Float) {
        val snapshot = state.value
        val component = snapshot.selectedComponent
        val target = snapshot.recipe?.rangeComponent(maskId, component) ?: return
        val bitmap = snapshot.bitmap ?: return
        viewModelScope.launch {
            val reference = rangeReferenceFor(bitmap)
            if (state.value.bitmap !== bitmap) return@launch
            val current = state.value.recipe ?: return@launch
            val updated = if (target.shape == MaskShape.COLOR) {
                current.withColorSample(
                    maskId,
                    component,
                    RangeMasks.sampleColor(reference, x, y, width, height),
                    add = state.value.rangeAddsSamples,
                )
            } else {
                val picked = RangeMasks.sampleLuminance(
                    reference,
                    x,
                    y,
                    width,
                    height,
                    smoothness = target.luminance?.smoothness ?: RANGE_REFINE_DEFAULT,
                )
                current.withLuminanceRange(maskId, component, picked.low, picked.high)
            }
            commitMask(updated, uiText(Res.string.editor_history_mask_sampled), current.mask(maskId)?.name?.asUiText())
        }
    }

    fun removeColorSample(maskId: String, index: Int) {
        val current = state.value.recipe ?: return
        commitMask(
            current.withoutColorSample(maskId, state.value.selectedComponent, index),
            uiText(Res.string.editor_history_mask_sample_removed),
            current.mask(maskId)?.name?.asUiText(),
        )
    }

    /** The Luminance Range's slider, dragged: one history entry per drag, closed by [finishAdjustment]. */
    fun setLuminanceRange(maskId: String, low: Float, high: Float) {
        val component = state.value.selectedComponent
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_range_changed)) {
            it.withLuminanceRange(maskId, component, low, high)
        }
    }

    /** Refine or Smoothness, dragged: one history entry per drag, closed by [finishAdjustment]. */
    fun setRangeSetting(maskId: String, setting: RangeSetting, value: Float) {
        val component = state.value.selectedComponent
        editMaskGeometry(maskId, uiText(Res.string.editor_history_mask_range_changed)) {
            it.withRangeSetting(maskId, component, setting, value)
        }
    }

    private suspend fun rangeReferenceFor(bitmap: Bitmap): BrushReference {
        rangeReference?.takeIf { it.first === bitmap }?.let { return it.second }
        val reference = withContext(Dispatchers.Default) { BrushReference.of(bitmap.toPixelBuffer()) }
        rangeReference = bitmap to reference
        return reference
    }

    /** A second finger turns the stroke into a pinch: what it had painted goes away, as if it never started. */
    fun cancelBrushStroke() {
        val start = editStart ?: return
        editStart = null
        pendingChange = null
        mutableState.update { it.copy(recipe = start, isSaving = true) }
        scheduleSave(start)
    }

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
            runCatching { savedRecipes.list() to savedRecipes.builtInMarks() }
                .onSuccess { (items, marks) ->
                    mutableState.update { it.copy(savedRecipes = items, builtInMarks = marks, isRecipeLibraryLoading = false) }
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
            try {
                recipes.save(recipe)
                mutableState.update { it.copy(isSaving = false) }
            } catch (cancelled: CancellationException) {
                // The next change cancelled this save to make its own: not a failure. Caught as one,
                // a quick series of adjustments on a slow save (an Immich photo) showed an error.
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(error = error.toUiText(), isSaving = false) }
            }
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

    /** [rawExposure] is what matched the camera's JPEG ([CameraExposureMatch]), for a RAW developed now. */
    internal data class LoadedPhoto(
        val bitmap: Bitmap,
        val raw: FloatPixelBuffer? = null,
        val rawExposure: Float = 0f,
        val light: CameraLight? = null,
        val baselineExposure: Float = 0f,
    )

    /** The RAWs developed lately, for opening the same photo again without developing it again. */
    private val recentRaws get() = RecentRaws

    /** [baselineExposure] is the recipe's [appliesBaselineExposure]; a JPEG ignores it. */
    private suspend fun loadPhoto(asset: EditorAsset, baselineExposure: Boolean, measure: Boolean = baselineExposure, highlight: Int = CURRENT_RAW_HIGHLIGHT): LoadedPhoto {
        if (library != null && shouldInspectRawOriginal(asset.fileName)) {
            val key = "${asset.id}|${asset.checksum}|$baselineExposure|$highlight|$measure"
            recentRaws.get(key)?.let { return it }
            val original = createPhotoTempFile(context)
            try {
                library.downloadOriginal(asset.id, original)
                val preview = decodeRawEditorPreview(original, maxSide = 2048, baselineExposure = baselineExposure, highlight = highlight)
                val raw = preview?.pixels
                if (raw != null) {
                    // Measured before the original is deleted; only a development made now uses it.
                    val matched = if (measure) CameraExposureMatch.of(original, raw) else 0f
                    return LoadedPhoto(withContext(Dispatchers.Default) { raw.toPixelBuffer().toPlatformBitmap() }, raw, matched, preview.light, preview.baselineExposure)
                        .also { recentRaws.put(key, it) }
                }
            } finally { original.delete() }
        }
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context)
                .data(editorImageData(asset))
                .libraryAuth(asset.apiKey)
                .size(2048)
                .softwareBitmap()
                // The editor keeps this bitmap for as long as it is open. In the memory cache it was a
                // second copy, big enough to push the library's thumbnails out of it.
                .memoryCachePolicy(CachePolicy.READ_ONLY)
                .build(),
        )
        if (result !is SuccessResult) throw LocalizedException(uiText(Res.string.editor_image_unreadable))
        return LoadedPhoto(result.image.toBitmap())
    }
}

/** A recipe from a newer app never goes through the old pipeline: the original opens, and nothing is saved over it. */
private fun EditRecipe.needsNewerApp(): Boolean =
    processVersion > CURRENT_PROCESS_VERSION || rawDevelopment?.engine?.let { it !in KNOWN_RAW_ENGINES } == true ||
        needsNewerRawRendering()

/** A recipe stored for another version of the file is not this photo's. */
private fun EditRecipe.belongsTo(asset: EditorAsset): Boolean =
    originalChecksum.isBlank() || asset.checksum.isBlank() || originalChecksum == asset.checksum

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

/** How long after a gesture on a Heal or a Clone a new stroke still widens it instead of starting another. */
internal const val RETOUCH_EXTEND_MILLIS = 2_000L

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

/**
 * A recipe from before the white balance became a light, brought to the current process as it opens,
 * with its Temperature and Tint converted: the sliders show the values that keep its look, and the
 * first drag starts from them instead of jumping. It is only written back if the photo is edited.
 * What other gates of older versions would change is carried along — the dormant geometry, the curve
 * pinned to its corners — except the linear curve of process 1, which no editing ever kept either.
 */
internal fun EditRecipe.openedForEditing(): EditRecipe {
    if (processVersion >= CURRENT_PROCESS_VERSION || !hasWhiteBalance()) return this
    return withLightWhiteBalance().copy(
        processVersion = CURRENT_PROCESS_VERSION,
        geometry = geometry.activeAtProcess(processVersion),
        toneCurve = toneCurve.copy(rgb = editableCurvePoints()),
    )
}

/**
 * Applying a recipe stamps the result with the current process: both white balances are converted
 * first, and both geometries woken as their process had them. What [parts] leaves out stays the target's.
 */
internal fun EditRecipe.rebasedOnto(
    target: EditRecipe,
    now: String,
    parts: Set<RecipePart> = RecipePart.entries.toSet(),
): EditRecipe {
    val source = withLightWhiteBalance().copy(geometry = geometry.activeAtProcess(processVersion))
    val base = target.withLightWhiteBalance().copy(geometry = target.geometry.activeAtProcess(target.processVersion))
    return base.withPartsFrom(source, parts).copy(
        updatedAt = now,
        derivedAssetId = null,
        processVersion = CURRENT_PROCESS_VERSION,
    )
}

/** The parts in which [other] differs from this recipe. */
internal fun EditRecipe.changedParts(other: EditRecipe): Set<RecipePart> =
    RecipePart.entries.filterTo(mutableSetOf()) { withPartsFrom(other, setOf(it)) != this }

/** What a recipe from [this] source writes over a photo. */
internal val RecipeSource.appliedParts: Set<RecipePart>
    get() = when (this) {
        is RecipeSource.Saved -> saved.appliedParts
        is RecipeSource.BuiltIn -> RecipePart.BUILT_IN
        is RecipeSource.New -> RecipePart.LOOK
    }

private fun Geometry.activeAtProcess(processVersion: Int): Geometry = copy(
    cropRect = if (processVersion >= 6) cropRect else CropRect(),
    aspectLock = if (processVersion >= 6) aspectLock else null,
    straighten = if (processVersion >= 7) straighten else 0f,
    perspective = if (processVersion >= 10) perspective else Perspective(),
)

fun EditRecipe.editableCurvePoints(channel: CurveChannel = CurveChannel.RGB): List<eu.studio742.imago.core.model.CurvePoint> =
    when (channel) {
        // Before process 11 the curve was always pinned to the corners; a recipe from then that lacked a
        // corner point drew one anyway, and editing it has to start from what was drawn.
        CurveChannel.RGB -> normalizeCurvePoints(toneCurve.rgb, pinnedToCorners = processVersion < 11)
        // A channel curve only exists from process 12, so it was never pinned.
        CurveChannel.RED -> normalizeCurvePoints(toneCurve.red ?: IDENTITY_CURVE)
        CurveChannel.GREEN -> normalizeCurvePoints(toneCurve.green ?: IDENTITY_CURVE)
        CurveChannel.BLUE -> normalizeCurvePoints(toneCurve.blue ?: IDENTITY_CURVE)
    }

/** A channel back to the straight line is no channel curve at all: the recipe stays as an older app wrote it. */
private fun eu.studio742.imago.core.model.ToneCurve.withPoints(
    channel: CurveChannel,
    points: List<eu.studio742.imago.core.model.CurvePoint>,
): eu.studio742.imago.core.model.ToneCurve {
    val channelPoints = points.takeUnless { it == IDENTITY_CURVE }
    return when (channel) {
        CurveChannel.RGB -> copy(rgb = points)
        CurveChannel.RED -> copy(red = channelPoints)
        CurveChannel.GREEN -> copy(green = channelPoints)
        CurveChannel.BLUE -> copy(blue = channelPoints)
    }
}

private val IDENTITY_CURVE = listOf(
    eu.studio742.imago.core.model.CurvePoint(0, 0),
    eu.studio742.imago.core.model.CurvePoint(255, 255),
)

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
    // A hand on either slider makes the white balance Custom, as in Lightroom.
    Adjustment.TEMPERATURE -> copy(whiteBalance = whiteBalance.copy(temp = value, auto = false))
    Adjustment.TINT -> copy(whiteBalance = whiteBalance.copy(tint = value, auto = false))
    Adjustment.VIBRANCE -> copy(presence = presence.copy(vibrance = value))
    Adjustment.SATURATION -> copy(presence = presence.copy(saturation = value))
    Adjustment.TEXTURE -> copy(presence = presence.copy(texture = value))
    Adjustment.CLARITY -> copy(presence = presence.copy(clarity = value))
    Adjustment.DEHAZE -> copy(presence = presence.copy(dehaze = value))
    Adjustment.SHARPEN_AMOUNT -> copy(detail = detail.copy(sharpenAmount = value))
    Adjustment.SHARPEN_RADIUS -> copy(detail = detail.copy(sharpenRadius = value))
    Adjustment.SHARPEN_DETAIL -> copy(detail = detail.copy(sharpenDetail = value))
    Adjustment.SHARPEN_MASKING -> copy(detail = detail.copy(sharpenMasking = value))
    Adjustment.NR_LUMINANCE -> copy(detail = detail.copy(noiseReductionLuminance = value))
    Adjustment.NR_LUMINANCE_DETAIL -> copy(detail = detail.copy(noiseReductionLuminanceDetail = value))
    Adjustment.NR_CONTRAST -> copy(detail = detail.copy(noiseReductionContrast = value))
    Adjustment.NR_COLOR -> copy(detail = detail.copy(noiseReductionColor = value))
    Adjustment.NR_COLOR_DETAIL -> copy(detail = detail.copy(noiseReductionColorDetail = value))
    Adjustment.NR_COLOR_SMOOTHNESS -> copy(detail = detail.copy(noiseReductionColorSmoothness = value))
    Adjustment.CAL_SHADOWS_TINT -> copy(calibration = calibration.copy(shadowsTint = value))
    Adjustment.CAL_RED_HUE -> copy(calibration = calibration.copy(redHue = value))
    Adjustment.CAL_RED_SATURATION -> copy(calibration = calibration.copy(redSaturation = value))
    Adjustment.CAL_GREEN_HUE -> copy(calibration = calibration.copy(greenHue = value))
    Adjustment.CAL_GREEN_SATURATION -> copy(calibration = calibration.copy(greenSaturation = value))
    Adjustment.CAL_BLUE_HUE -> copy(calibration = calibration.copy(blueHue = value))
    Adjustment.CAL_BLUE_SATURATION -> copy(calibration = calibration.copy(blueSaturation = value))
    Adjustment.LENS_VIGNETTE_AMOUNT -> copy(lens = lens.copy(vignetteAmount = value))
    Adjustment.LENS_VIGNETTE_MIDPOINT -> copy(lens = lens.copy(vignetteMidpoint = value))
    Adjustment.LENS_PURPLE_AMOUNT -> copy(lens = lens.copy(purpleAmount = value))
    Adjustment.LENS_PURPLE_RANGE -> copy(lens = lens.copy(purpleRange = value))
    Adjustment.LENS_GREEN_AMOUNT -> copy(lens = lens.copy(greenAmount = value))
    Adjustment.LENS_GREEN_RANGE -> copy(lens = lens.copy(greenRange = value))
    Adjustment.VIGNETTE_AMOUNT -> copy(effects = effects.copy(vignetteAmount = value))
    Adjustment.VIGNETTE_MIDPOINT -> copy(effects = effects.copy(vignetteMidpoint = value))
    Adjustment.VIGNETTE_ROUNDNESS -> copy(effects = effects.copy(vignetteRoundness = value))
    Adjustment.VIGNETTE_FEATHER -> copy(effects = effects.copy(vignetteFeather = value))
    Adjustment.GRAIN_AMOUNT -> copy(effects = effects.copy(grainAmount = value))
    Adjustment.GRAIN_SIZE -> copy(effects = effects.copy(grainSize = value))
    Adjustment.GRAIN_ROUGHNESS -> copy(effects = effects.copy(grainRoughness = value))
    Adjustment.GRADE_BLENDING -> copy(colorGrading = colorGrading.copy(blending = value))
    Adjustment.GRADE_BALANCE -> copy(colorGrading = colorGrading.copy(balance = value))
    // Never routed here: a mask slider goes through `withMaskSlider`.
    Adjustment.MASK_AMOUNT -> this
    Adjustment.PERSPECTIVE_DISTORTION -> withPerspective { copy(distortion = value) }
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
    Adjustment.SHARPEN_AMOUNT -> detail.sharpenAmount
    Adjustment.SHARPEN_RADIUS -> detail.sharpenRadius
    Adjustment.SHARPEN_DETAIL -> detail.sharpenDetail
    Adjustment.SHARPEN_MASKING -> detail.sharpenMasking
    Adjustment.NR_LUMINANCE -> detail.noiseReductionLuminance
    Adjustment.NR_LUMINANCE_DETAIL -> detail.noiseReductionLuminanceDetail
    Adjustment.NR_CONTRAST -> detail.noiseReductionContrast
    Adjustment.NR_COLOR -> detail.noiseReductionColor
    Adjustment.NR_COLOR_DETAIL -> detail.noiseReductionColorDetail
    Adjustment.NR_COLOR_SMOOTHNESS -> detail.noiseReductionColorSmoothness
    Adjustment.CAL_SHADOWS_TINT -> calibration.shadowsTint
    Adjustment.CAL_RED_HUE -> calibration.redHue
    Adjustment.CAL_RED_SATURATION -> calibration.redSaturation
    Adjustment.CAL_GREEN_HUE -> calibration.greenHue
    Adjustment.CAL_GREEN_SATURATION -> calibration.greenSaturation
    Adjustment.CAL_BLUE_HUE -> calibration.blueHue
    Adjustment.CAL_BLUE_SATURATION -> calibration.blueSaturation
    Adjustment.LENS_VIGNETTE_AMOUNT -> lens.vignetteAmount
    Adjustment.LENS_VIGNETTE_MIDPOINT -> lens.vignetteMidpoint
    Adjustment.LENS_PURPLE_AMOUNT -> lens.purpleAmount
    Adjustment.LENS_PURPLE_RANGE -> lens.purpleRange
    Adjustment.LENS_GREEN_AMOUNT -> lens.greenAmount
    Adjustment.LENS_GREEN_RANGE -> lens.greenRange
    Adjustment.VIGNETTE_AMOUNT -> effects.vignetteAmount
    Adjustment.VIGNETTE_MIDPOINT -> effects.vignetteMidpoint
    Adjustment.VIGNETTE_ROUNDNESS -> effects.vignetteRoundness
    Adjustment.VIGNETTE_FEATHER -> effects.vignetteFeather
    Adjustment.GRAIN_AMOUNT -> effects.grainAmount
    Adjustment.GRAIN_SIZE -> effects.grainSize
    Adjustment.GRAIN_ROUGHNESS -> effects.grainRoughness
    Adjustment.GRADE_BLENDING -> colorGrading.blending
    Adjustment.GRADE_BALANCE -> colorGrading.balance
    Adjustment.PERSPECTIVE_DISTORTION -> geometry.perspective.distortion
    Adjustment.PERSPECTIVE_VERTICAL -> geometry.perspective.vertical
    Adjustment.PERSPECTIVE_HORIZONTAL -> geometry.perspective.horizontal
    Adjustment.PERSPECTIVE_ASPECT -> geometry.perspective.aspect
    Adjustment.PERSPECTIVE_SCALE -> geometry.perspective.scale
    Adjustment.PERSPECTIVE_OFFSET_X -> geometry.perspective.offsetX
    Adjustment.PERSPECTIVE_OFFSET_Y -> geometry.perspective.offsetY
    Adjustment.MASK_AMOUNT -> Adjustment.MASK_AMOUNT.neutral
}

/** The base profiles the editor offers for a RAW, in the menu's order. */
enum class RawProfileChoice(val id: String, val labelRes: org.jetbrains.compose.resources.StringResource) {
    COLOR(RAW_PROFILE_COLOR, Res.string.editor_profile_color),
    NEUTRAL(RAW_PROFILE_NEUTRAL, Res.string.editor_profile_neutral),
    VIVID(RAW_PROFILE_VIVID, Res.string.editor_profile_vivid),
    MONOCHROME(RAW_PROFILE_MONOCHROME, Res.string.editor_profile_monochrome),
    ;

    companion object {
        fun of(id: String): RawProfileChoice = entries.firstOrNull { it.id == id } ?: COLOR
    }
}

/**
 * The white balance menu. A JPEG has Lightroom's three; a RAW also the lights, at IMAGO's own values in
 * kelvin and Tint — the physical ones of each light, rounded, with the green of a fluorescent tube
 * taken out by a little magenta.
 */
enum class WhiteBalancePreset(
    val labelRes: org.jetbrains.compose.resources.StringResource,
    /** A RAW's light; null for the three every photo has. */
    val light: KelvinTint? = null,
) {
    AS_SHOT(Res.string.editor_white_balance_as_shot),
    AUTO(Res.string.editor_white_balance_auto),
    DAYLIGHT(Res.string.editor_white_balance_daylight, KelvinTint(5500f, 0f)),
    CLOUDY(Res.string.editor_white_balance_cloudy, KelvinTint(6500f, 0f)),
    SHADE(Res.string.editor_white_balance_shade, KelvinTint(7500f, 0f)),
    TUNGSTEN(Res.string.editor_white_balance_tungsten, KelvinTint(2850f, 0f)),
    FLUORESCENT(Res.string.editor_white_balance_fluorescent, KelvinTint(4000f, 10f)),
    FLASH(Res.string.editor_white_balance_flash, KelvinTint(5500f, 0f)),
    CUSTOM(Res.string.editor_white_balance_custom),
    ;

    companion object {
        /** What the menu offers: the lights only where the camera's colour says what they are. */
        fun offered(light: CameraLight?): List<WhiteBalancePreset> = entries.filter { it.light == null || light != null }

        /** A light preset is shown while the sliders are still where it put them, as in Lightroom. */
        fun of(balance: WhiteBalance, light: CameraLight? = null): WhiteBalancePreset = when {
            balance.auto -> AUTO
            balance.isAsShot -> AS_SHOT
            light == null -> CUSTOM
            else -> entries.firstOrNull { preset ->
                preset.light?.let { target ->
                    val (temperature, tint) = light.sliders(target.kelvin, target.tint)
                    kotlin.math.abs(temperature - balance.temp) < 0.05f && kotlin.math.abs(tint - balance.tint) < 0.05f
                } == true
            } ?: CUSTOM
        }
    }
}

/** Shared by every editor session, since the view model goes away with the screen and the photo may come back. */
internal object RecentRaws {
    private val sources = RecentSources<EditorViewModel.LoadedPhoto>()
    fun get(key: String) = sources.get(key)
    fun put(key: String, photo: EditorViewModel.LoadedPhoto) = sources.put(key, photo)
}
