package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

/**
 * The pipeline's semantics, not the JSON's shape.
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
const val CURRENT_PROCESS_VERSION = 12

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
)

@Serializable
data class WhiteBalance(val temp: Float = 0f, val tint: Float = 0f)

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
    val sharpenAmount: Float = 0f,
    val sharpenRadius: Float = 1f,
    val noiseReductionLuminance: Float = 0f,
    val noiseReductionColor: Float = 25f,
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
) {
    /** Whether the camera tilts or the photo stretches: what Immich's own edits cannot express. */
    val isProjective: Boolean
        get() = vertical != 0f || horizontal != 0f || aspect != 0f ||
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
