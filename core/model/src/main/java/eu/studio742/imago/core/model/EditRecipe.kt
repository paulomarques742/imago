package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

/**
 * The pipeline's semantics, not the JSON's shape.
 *
 * 21 widens and strengthens the Highlights band (WIDE_HIGHLIGHTS_PROCESS_VERSION): it starts at 0.20
 * and not at 0.35, with a bigger gain, because at -100 the first one moved a light midtone by a few
 * hundredths. A recipe saved before keeps the band it was edited with; an app at 20 asks to be updated
 * for one saved now, since it would draw its Highlights weaker.
 *
 * 20 is the second RAW rendering ([RAW_RENDERING_PROFILED]): a DNG at its BaselineExposure, the
 * exposure its maker wants it seen at (+2 EV on a Samsung 50 MP DNG), and the linear development
 * brought to the screen through a base profile ([profile]) instead of the plain sRGB transfer. Recipes
 * written before keep the rendering they were edited on; an app at 19 would draw a new recipe dark
 * and flat.
 *
 * 19 adds a versioned RAW development stage; older apps must not render its recipes on JPEG previews.
 *
 * 17 activates range mask components (`MaskShape.LUMINANCE` and `MaskShape.COLOR`). No earlier app
 * knows the two shapes, so none can decode such a recipe and sync skips it; the version is raised anyway,
 * as for every new shape, so that the gate in `RecipeRendering` is checked and not assumed.
 *
 * 16 activates brush mask components (`MaskShape.BRUSH`). An app at 15 would decode the recipe and draw
 * the brush as nothing; one at 14 or earlier cannot decode the new shape and skips the recipe in sync.
 *
 * 15 activates the mask Amount (`LocalMask.amount`) and lets a recipe have eight masks instead of
 * four. An older app would draw a mask at 50% at full strength and drop the masks after the fourth,
 * so it has to ask to be updated instead.
 *
 * 14 activates `geometry.perspective.distortion`, Lightroom's barrel and pincushion slider. It is
 * radial and comes before the virtual camera, on the photo as the lens drew it. An older app would draw
 * a recipe with it as if it were not there, so it has to ask to be updated instead.
 *
 * 13 makes Temperature and Tint a light: a point on the Planckian locus, moved off it by the tint,
 * that a Bradford adaptation takes to D65 (`WhiteBalanceModel` in the renderer). Before it they were
 * three gains too weak for a tungsten cast. An older recipe keeps the gains until it is edited; then
 * its values are converted so that its greys keep their colour.
 *
 * 12 activates `toneCurve.red`, `green` and `blue`, which were in the schema from the start and never
 * written. Each runs after the composite curve, the order that the best evidence about Lightroom
 * points to (Adobe does not document it; Photoshop does the opposite). An older app would draw a
 * recipe with them as if they were not there, so it has to ask to be updated instead.
 *
 * 11 frees the tone curve's end points. Below the first point and above the last the curve holds
 * its height, as in Lightroom, instead of being pinned to (0, 0) and (255, 255). Earlier editors
 * always kept a point at 0 and one at 255, and every built-in look has them, so no recipe already
 * saved changes; the gate is for the recipes written from now on, which an older app would draw
 * with a line down to the corner.
 *
 * 10 activates `geometry.perspective`. The straighten becomes the roll of the same virtual camera
 * that the perspective tilts, and with the perspective neutral the matrix is exactly the rotation
 * there was before — but the gate stays, as in 6, 7 and 9, so that this is checked and not assumed.
 *
 * 9 activates `colorGrading`, step 12 of the pipeline, and adds its fourth wheel — `global`, which
 * tints the whole photo regardless of tone. The first three and the two blending controls were in
 * the schema from the start, dormant: no earlier recipe could have them off neutral, and so the gate
 * in `RecipeRendering.toRenderParameters` changes no photo already edited — it exists so that the
 * promise stays verifiable and not an assumption about the past.
 *
 * 8 activates `masks`. Earlier recipes keep the field dormant, as always: a photo edited before this
 * version has to keep opening exactly the same.
 *
 * 8 also fixes the order of geometry on export, which applied the flip before the rotation while
 * the preview always applied the opposite. The two only diverged when a flip was combined with 90°
 * or 270°, and in those cases the file came out flipped on the wrong axis. There is no old path to
 * keep: the stage has always defined the framing, and it is the export that now agrees with it.
 */
const val CURRENT_PROCESS_VERSION = 21

/** Process 21 reads the noise reduction of [Detail]; before it the fields were stored and ignored. */
const val NOISE_REDUCTION_PROCESS_VERSION = 21

/** Process 21 reads [Detail.sharpenAmount] and its three companions; before it they were stored and ignored. */
const val SHARPENING_PROCESS_VERSION = 21

/**
 * What a RAW starts with, because a development with no sharpening opens soft: IMAGO's own choice,
 * made once when the photo is first developed and then the photo's like any other value.
 */
const val RAW_INITIAL_SHARPEN_AMOUNT = 40f

/** Process 21's Highlights act on a wider band, and harder. Before it the band is the first one. */
const val WIDE_HIGHLIGHTS_PROCESS_VERSION = 21

/** Process 19 adds a versioned RAW development stage before the existing editing pipeline. */
const val RAW_PROCESS_VERSION = 19
const val RAW_ENGINE_1 = "libraw-0.22.2-imago-1"

/** Engine 2 develops with LibRaw's highlight mode 2, which blends a clipped channel towards white. */
const val RAW_ENGINE_2 = "libraw-0.22.2-imago-2"
const val CURRENT_RAW_ENGINE = RAW_ENGINE_2
val KNOWN_RAW_ENGINES = listOf(RAW_ENGINE_1, RAW_ENGINE_2)

/** Process 19's rendering: the camera's linear development shown through the sRGB transfer alone. */
const val RAW_RENDERING_LINEAR = 1

/** Process 20's rendering: the DNG's BaselineExposure, and the [EditRecipe.profile] on the way to the screen. */
const val RAW_RENDERING_PROFILED = 2

/**
 * Rendering 3's Highlights run over everything the sensor recorded above white, and not only up to it
 * (`RawProfile.HEADROOM_CEILING`). Rendering 2 keeps the band that ends at white, so what it showed
 * does not move.
 */
const val RAW_RENDERING_HEADROOM = 3
const val CURRENT_RAW_RENDERING = RAW_RENDERING_HEADROOM
const val PROFILED_RAW_PROCESS_VERSION = 20

/** The base profiles of a RAW, the first choice of Lightroom's Basic panel. A JPEG has none. */
const val RAW_PROFILE_COLOR = "color"
const val RAW_PROFILE_MONOCHROME = "monochrome"
const val RAW_PROFILE_NEUTRAL = "neutral"
const val RAW_PROFILE_VIVID = "vivid"

/** An app that does not list a profile asks to be updated for a recipe that has it: `needsNewerRawRendering`. */
val RAW_PROFILES = listOf(RAW_PROFILE_COLOR, RAW_PROFILE_NEUTRAL, RAW_PROFILE_VIVID, RAW_PROFILE_MONOCHROME)

/**
 * How the original is developed, which belongs to the photo: applying a recipe from another photo
 * keeps the target's development.
 *
 * A missing engine field always means engine 1, including after future decoder upgrades. A missing
 * [rendering] means process 19's, which every recipe edited then has.
 *
 * [exposure] is what made the development as bright as the JPEG the camera stored in the file, in
 * stops, measured once when the photo was first developed and kept: the same on every device, and not
 * changed by a better measurement later. Only rendering 2 has it; the Exposure slider is on top of it.
 */
@Serializable
data class RawDevelopment(
    val engine: String = RAW_ENGINE_1,
    val rendering: Int = RAW_RENDERING_LINEAR,
    val exposure: Float = 0f,
)

/**
 * How a RAW original is rendered for this recipe. A recipe that has not been opened as a RAW yet
 * decides by the process it was written in.
 */
fun EditRecipe.rawRendering(): Int =
    rawDevelopment?.rendering ?: if (processVersion >= PROFILED_RAW_PROCESS_VERSION) CURRENT_RAW_RENDERING else RAW_RENDERING_LINEAR

/** The engine that develops this recipe's original: a development not made yet gets the current one. */
fun EditRecipe.rawEngine(): String =
    rawDevelopment?.engine ?: if (rawRendering() >= RAW_RENDERING_HEADROOM) CURRENT_RAW_ENGINE else RAW_ENGINE_1

/** LibRaw's `highlight` for [rawEngine]: 1 for engine 1, as it was, and 2 from engine 2. */
fun EditRecipe.rawHighlightMode(): Int = if (rawEngine() == RAW_ENGINE_1) 1 else 2

/** Whether the original is developed at its DNG BaselineExposure, which the decoder has to know. */
fun EditRecipe.appliesBaselineExposure(): Boolean = rawRendering() >= RAW_RENDERING_PROFILED

/** A rendering or a profile this app does not know: the recipe was written by a newer one. */
fun EditRecipe.needsNewerRawRendering(): Boolean =
    (rawDevelopment?.rendering ?: RAW_RENDERING_LINEAR) > CURRENT_RAW_RENDERING || profile !in RAW_PROFILES ||
        (rawDevelopment?.engine ?: RAW_ENGINE_1) !in KNOWN_RAW_ENGINES

/**
 * The recipe as the editor holds it for a RAW original: at the current process, its development kept.
 * [rendering] is what the original was decoded for, decided before the process was stamped;
 * [exposure] what matched the camera's JPEG, for a development made now.
 */
fun EditRecipe.developedAsRaw(rendering: Int = rawRendering(), exposure: Float = 0f): EditRecipe = copy(
    processVersion = CURRENT_PROCESS_VERSION,
    detail = if (rawDevelopment == null && rendering >= RAW_RENDERING_HEADROOM && detail == Detail()) {
        detail.copy(sharpenAmount = RAW_INITIAL_SHARPEN_AMOUNT)
    } else {
        detail
    },
    rawDevelopment = rawDevelopment ?: RawDevelopment(
        engine = if (rendering >= RAW_RENDERING_HEADROOM) CURRENT_RAW_ENGINE else RAW_ENGINE_1,
        rendering = rendering,
        exposure = if (rendering >= RAW_RENDERING_PROFILED) exposure else 0f,
    ),
)

/** The development's own exposure, in stops, under the Exposure slider. */
fun EditRecipe.rawExposure(): Float =
    if (rawRendering() >= RAW_RENDERING_PROFILED) rawDevelopment?.exposure ?: 0f else 0f

@Serializable
data class EditRecipe(
    val schemaVersion: Int = 1,
    val assetId: String,
    val originalChecksum: String,
    val processVersion: Int = CURRENT_PROCESS_VERSION,
    val createdAt: String,
    val updatedAt: String,
    val derivedAssetId: String? = null,
    val whiteBalance: WhiteBalance = WhiteBalance(),
    val tone: Tone = Tone(),
    val presence: Presence = Presence(),
    val toneCurve: ToneCurve = ToneCurve(),
    val hsl: Hsl = Hsl(),
    val colorGrading: ColorGrading = ColorGrading(),
    val detail: Detail = Detail(),
    val effects: Effects = Effects(),
    val geometry: Geometry = Geometry(),
    val masks: List<LocalMask> = emptyList(),
    val retouches: List<Retouch> = emptyList(),
    val rawDevelopment: RawDevelopment? = null,
    /** One of [RAW_PROFILES]; part of the look, so it travels with the recipe. Only a RAW reads it. */
    val profile: String = RAW_PROFILE_COLOR,
)

/**
 * Lightroom's three presets for a JPEG are read from this: As Shot is both sliders at zero, Auto is
 * [auto], and anything else is Custom.
 *
 * [auto] is what the person chose, not how the values were found: [temp] and [tint] already hold what
 * Auto found on this photo, and render as they are. It matters when the recipe goes to another photo,
 * where Auto is found again for that photo, as Lightroom does when it syncs an Auto white balance.
 * Touching either slider or picking with the selector makes it Custom.
 */
@Serializable
data class WhiteBalance(val temp: Float = 0f, val tint: Float = 0f, val auto: Boolean = false) {
    val isAsShot: Boolean get() = !auto && temp == 0f && tint == 0f
}

@Serializable
data class Tone(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
)

@Serializable
data class Presence(
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
)

@Serializable
data class CurvePoint(val x: Int, val y: Int)

/** The point curves. A channel curve that is null is the identity, which is how every recipe starts. */
@Serializable
data class ToneCurve(
    val rgb: List<CurvePoint> = listOf(CurvePoint(0, 0), CurvePoint(255, 255)),
    val red: List<CurvePoint>? = null,
    val green: List<CurvePoint>? = null,
    val blue: List<CurvePoint>? = null,
)

@Serializable
data class HslBand(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

@Serializable
data class Hsl(
    val red: HslBand = HslBand(),
    val orange: HslBand = HslBand(),
    val yellow: HslBand = HslBand(),
    val green: HslBand = HslBand(),
    val aqua: HslBand = HslBand(),
    val blue: HslBand = HslBand(),
    val purple: HslBand = HslBand(),
    val magenta: HslBand = HslBand(),
)

/**
 * A colour grading wheel: the angle, the radius and the slider beside it.
 *
 * @param hue 0..360 degrees. No effect while [saturation] is zero — which is why neutral is red and
 *   not an absence of colour: a wheel with zero radius has no angle at all.
 * @param saturation 0..100, how far the handle is from the centre
 * @param luminance -100..100
 */
@Serializable
data class ColorWheel(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

/**
 * Step 12: four wheels and the two controls that decide how the three tonal ones are shared out.
 *
 * [global] ignores [blending] and [balance] on purpose — it has no tonal zone to blend or to
 * balance, and that is what sets it apart from the other three.
 *
 * @param blending 0..100, how much the three colours bleed into each other
 * @param balance -100..100, negative gives the range to the dark tones, positive to the light ones
 */
@Serializable
data class ColorGrading(
    val shadows: ColorWheel = ColorWheel(),
    val midtones: ColorWheel = ColorWheel(),
    val highlights: ColorWheel = ColorWheel(),
    val global: ColorWheel = ColorWheel(),
    val blending: Float = 50f,
    val balance: Float = 0f,
)

@Serializable
data class Detail(
    /** 0 to 150. Sharpening is read from process 21 ([SHARPENING_PROCESS_VERSION]); before it the field was dormant. */
    val sharpenAmount: Float = 0f,
    /** In pixels, 0.5 to 3. */
    val sharpenRadius: Float = 1f,
    /** 0 to 100: low keeps the halos of strong edges in check, high sharpens everything the radius sees. */
    val sharpenDetail: Float = 25f,
    /** 0 to 100: 0 sharpens everywhere, 100 only on edges. */
    val sharpenMasking: Float = 0f,
    /** 0 to 100. Read from process 21 ([NOISE_REDUCTION_PROCESS_VERSION]); before it stored and ignored. */
    val noiseReductionLuminance: Float = 0f,
    /** 0 to 100: how much of the fine detail the luminance reduction keeps. */
    val noiseReductionLuminanceDetail: Float = 50f,
    /** 0 to 100: how much of the contrast of what is left it keeps. */
    val noiseReductionContrast: Float = 0f,
    /** 0 to 100. A new recipe has 25, in RAW and JPEG, as decided on 27/10/2026. */
    val noiseReductionColor: Float = 25f,
    /** 0 to 100: how much of the colour edges the colour reduction keeps. */
    val noiseReductionColorDetail: Float = 50f,
    /** 0 to 100: how wide a blotch of colour is smoothed. */
    val noiseReductionColorSmoothness: Float = 50f,
)

@Serializable
data class Effects(
    val vignetteAmount: Float = 0f,
    val vignetteMidpoint: Float = 50f,
    val vignetteRoundness: Float = 0f,
    val vignetteFeather: Float = 50f,
    val grainAmount: Float = 0f,
    val grainSize: Float = 25f,
    val grainRoughness: Float = 50f,
)

@Serializable
data class CropRect(val x: Float = 0f, val y: Float = 0f, val w: Float = 1f, val h: Float = 1f)

@Serializable
data class Geometry(
    val cropRect: CropRect = CropRect(),
    val straighten: Float = 0f,
    val rotation: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    val aspectLock: String? = null,
    val perspective: Perspective = Perspective(),
)

/**
 * The manual perspective: the photo as a plane seen by a virtual camera that tilts and turns.
 *
 * [vertical] and [horizontal] go from -100 to 100; negative widens the top and the left, which is
 * what straightens a building shot from below. [aspect] stretches across (positive) or down
 * (negative), [scale] goes from -50 to 100, and the offsets from -100 to 100 move the photo in the
 * frame. With [constrainCrop] the frame never shows anything beyond the photo; without it, what the
 * correction leaves uncovered shows white and [scale] may go below zero.
 *
 * [upright] is Lightroom's Upright mode. What a mode found is kept in [uprightRoll], [uprightPitch]
 * and [uprightYaw], in degrees, and the render only ever reads those: it never detects anything
 * again, so the same recipe renders the same on every device, and a better detector one day does not
 * change a photo already edited. The sliders add on top of them, as in Lightroom.
 */
@Serializable
data class Perspective(
    val vertical: Float = 0f,
    val horizontal: Float = 0f,
    val aspect: Float = 0f,
    val scale: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val constrainCrop: Boolean = true,
    val upright: String = UPRIGHT_OFF,
    val uprightRoll: Float = 0f,
    val uprightPitch: Float = 0f,
    val uprightYaw: Float = 0f,
    /** The guided mode's lines, in the original image's normalised coordinates, as masks are. */
    val guides: List<UprightGuide> = emptyList(),
    /**
     * Lightroom's Distortion, −100..100: positive straightens the lines a barrel distortion bows
     * outwards, negative those a pincushion bows inwards. Radial about the photo's centre, applied to
     * the photo as the lens drew it, before the camera turns.
     */
    val distortion: Float = 0f,
) {
    /** Whether the camera tilts, the photo stretches or the lens bends: what Immich's own edits cannot express. */
    val isProjective: Boolean
        get() = vertical != 0f || horizontal != 0f || aspect != 0f || distortion != 0f ||
            uprightRoll != 0f || uprightPitch != 0f || uprightYaw != 0f
}

/**
 * A line the person drew along something that should be vertical or level. Which of the two is read
 * from its angle in the photo: nearer upright is a vertical.
 */
@Serializable
data class UprightGuide(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

const val UPRIGHT_OFF = "off"
const val UPRIGHT_AUTO = "auto"
const val UPRIGHT_LEVEL = "level"
const val UPRIGHT_VERTICAL = "vertical"
const val UPRIGHT_FULL = "full"
const val UPRIGHT_GUIDED = "guided"

/** The automatic modes, in Lightroom's order: they detect the lines themselves. */
val AUTOMATIC_UPRIGHT_MODES = listOf(UPRIGHT_AUTO, UPRIGHT_LEVEL, UPRIGHT_VERTICAL, UPRIGHT_FULL)

/** Lightroom's limits: the guided mode corrects from two guides and takes up to four. */
const val MIN_UPRIGHT_GUIDES = 2
const val MAX_UPRIGHT_GUIDES = 4
