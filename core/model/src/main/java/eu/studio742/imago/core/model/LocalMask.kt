package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

/**
 * How many masks a recipe can have.
 *
 * It is not a number chosen by taste: the weight field the renderer builds is made of RGBA textures,
 * and each mask takes one channel. That is what allows applying four masks at once with a dot product
 * per parameter, instead of a dynamically indexed loop — which on several GLSL ES compilers falls back
 * to private memory and eats the 16 ms budget on its own. Eight is two textures: the second is only
 * built and sampled when a fifth mask exists.
 *
 * Process 14 and earlier stopped at four; [maxLocalMasks] keeps their recipes drawing as they did.
 */
const val MAX_LOCAL_MASKS = 8

/** The masks a texture of the weight field holds: one per RGBA channel. */
const val MASKS_PER_FIELD = 4

/** The process from which a recipe can have more than four masks, and [LocalMask.amount] counts. */
const val COMBINED_MASKS_PROCESS_VERSION = 15

/** How many masks a recipe of this process draws. */
fun maxLocalMasks(processVersion: Int): Int =
    if (processVersion >= COMBINED_MASKS_PROCESS_VERSION) MAX_LOCAL_MASKS else MASKS_PER_FIELD

/**
 * How many components fit in total, across all masks.
 *
 * It is the size of the uniform arrays the field shader declares. A component costs three vectors —
 * two of geometry and one packed integer — so 32 take 96 of the 224 that OpenGL ES 3.0 guarantees to a
 * fragment shader, which leaves the field pass plenty of room.
 */
const val MAX_MASK_COMPONENTS = 32

/**
 * How many brush and range components fit across all masks: they share the limit.
 *
 * Each one is a 2048-pixel weight image — a layer of a texture array on the GPU and a raster in memory
 * on export — and not a handful of uniforms like a gradient.
 */
const val MAX_BRUSH_COMPONENTS = 8

/** The process from which a mask component can be a [MaskShape.BRUSH]. */
const val BRUSH_MASKS_PROCESS_VERSION = 16

/** The process from which a mask component can be a [MaskShape.LUMINANCE] or a [MaskShape.COLOR]. */
const val RANGE_MASKS_PROCESS_VERSION = 17

/** Lightroom's limit on a Color Range: five samples, added with Shift. */
const val MAX_COLOR_SAMPLES = 5

/** The default of a Color Range's Refine, and of a Luminance Range's Smoothness. */
const val RANGE_REFINE_DEFAULT = 50f

/** The neutral [LocalMask.amount]: the adjustments as they were set. */
const val MASK_AMOUNT_NEUTRAL = 100f
const val MASK_AMOUNT_MAX = 200f

/**
 * The shape of a mask component.
 *
 * Adding values is backward compatible for old recipes, which never contain them. It is not for old
 * apps: one that does not know a value cannot decode the recipe at all, and sync skips it — which is
 * why every new shape also raises the process version, so that the app that can decode it but not draw
 * it asks to be updated.
 */
@Serializable
enum class MaskShape {
    LINEAR,
    RADIAL,
    BRUSH,

    /** Lightroom's Luminance Range: the pixels whose brightness falls in a range. */
    LUMINANCE,

    /** Lightroom's Color Range: the pixels close to the colours sampled on the photo. */
    COLOR,
    ;

    /** A range selects by what the photo holds, not by where: it has no outline on the stage. */
    val isRange: Boolean get() = this == LUMINANCE || this == COLOR
}

/** How a component combines with the ones before it in the same mask. */
@Serializable
enum class MaskOp { ADD, SUBTRACT, INTERSECT }

/**
 * A local mask: a region of the photo and the adjustments that only apply inside it.
 *
 * The coordinates of every component are normalised to the **original image**, before geometry.
 * That is the opposite of the vignette, on purpose: a vignette is relative to the framing, but a
 * mask is a selection of content. A radial over a face has to stay over the face when the user
 * crops or rotates; in frame coordinates it would slide off it at the first framing adjustment.
 */
@Serializable
data class LocalMask(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    val components: List<MaskComponent> = emptyList(),
    val adjustments: LocalAdjustments = LocalAdjustments(),
    /**
     * Lightroom's mask Amount, in percent: it scales every adjustment of the mask at once, from 0 (the
     * mask does nothing) to 200 (each adjustment doubled). Scaling the parameters and not the weight is
     * what lets it go past 100 — a weight cannot go above one.
     */
    val amount: Float = MASK_AMOUNT_NEUTRAL,
) {
    /** The adjustments the renderer applies: the sliders times the Amount. */
    val effectiveAdjustments: LocalAdjustments
        get() = if (amount == MASK_AMOUNT_NEUTRAL) adjustments else adjustments.scaled(amount / MASK_AMOUNT_NEUTRAL)
}

/**
 * A shape inside a mask.
 *
 * The component list has existed since day one, even while the interface can only create
 * single-component masks. Moving from a single shape to a list later would be a change to the
 * **shape** of the stored JSON, and that is exactly what `ignoreUnknownKeys` cannot save — unlike
 * adding fields, which is free.
 *
 * The payload is an optional field per shape instead of a sealed hierarchy because the rest of the
 * model is plain data without polymorphism, and a sealed hierarchy would require registering a
 * serializers module just for this.
 */
@Serializable
data class MaskComponent(
    val shape: MaskShape,
    val op: MaskOp = MaskOp.ADD,
    val inverted: Boolean = false,
    val linear: LinearMask? = null,
    val radial: RadialMask? = null,
    val brush: BrushMask? = null,
    val luminance: LuminanceRange? = null,
    val color: ColorRange? = null,
)

/**
 * A linear gradient.
 *
 * [x] and [y] are the midpoint of the transition, where the weight is 0.5. [angle] is the direction
 * in degrees, and [width] the width of the transition — both in units of the image's **height**, so
 * that the mask does not distort with the aspect ratio.
 */
@Serializable
data class LinearMask(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val angle: Float = 0f,
    val width: Float = 0.5f,
)

/**
 * A radial mask.
 *
 * [roundness] interpolates between the ellipse (0) and the rounded rectangle (100), by the same
 * Minkowski norm the vignette uses. [feather] is the fraction of the radius taken by the transition.
 */
@Serializable
data class RadialMask(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val radiusX: Float = 0.3f,
    val radiusY: Float = 0.3f,
    val angle: Float = 0f,
    val roundness: Float = 0f,
    val feather: Float = 50f,
)

/**
 * Painted strokes, applied in order: painting raises the weight towards each stroke's density, the
 * eraser lowers it, and both only inside this component.
 */
@Serializable
data class BrushMask(
    val strokes: List<BrushStroke> = emptyList(),
)

/**
 * One stroke: the finger's path and the brush it was painted with.
 *
 * [points] are `x0, y0, x1, y1, …` in normalised coordinates of the original image, like every other
 * mask, flat so the JSON stays a list of numbers. [radius] is in units of the image's height, as a
 * radial's radii are: the brush is measured on screen while painting, and what is kept is how much of
 * the photo it covered.
 *
 * [feather], [flow] and [density] go from 0 to 100, as in Lightroom. Feather is the fraction of the
 * radius taken by the soft edge; flow is how much each dab lays down, so slow strokes build up; density
 * is the most the stroke can reach however many times it passes. [autoMask] confines the stroke to the
 * colours under the brush's centre.
 */
@Serializable
data class BrushStroke(
    val points: List<Float>,
    val radius: Float,
    val feather: Float = 50f,
    val flow: Float = 100f,
    val density: Float = 100f,
    val erase: Boolean = false,
    val autoMask: Boolean = false,
)

/**
 * The pixels whose brightness falls between [low] and [high], from 0 (black) to 100 (white).
 *
 * The brightness is the perceptual lightness of the **original** photo, as the Auto Mask reads it: a
 * selection that moved every time an exposure did would be a mask that moved under the person's feet.
 * [smoothness] is Lightroom's: how gradual the falloff is at either end of the range, from a hard edge
 * at 0 to the widest at 100.
 */
@Serializable
data class LuminanceRange(
    val low: Float = 0f,
    val high: Float = 100f,
    val smoothness: Float = RANGE_REFINE_DEFAULT,
)

/**
 * The pixels close to the colours sampled on the photo, up to [MAX_COLOR_SAMPLES] of them.
 *
 * [refine] is Lightroom's Refine, from 0 to 100: how far from a sample a colour can be and still be
 * selected.
 */
@Serializable
data class ColorRange(
    val samples: List<ColorSample> = emptyList(),
    val refine: Float = RANGE_REFINE_DEFAULT,
)

/**
 * One sample of a Color Range: a point tapped, or an area dragged, on the original photo.
 *
 * [x] and [y] are its centre and [width] and [height] its size, in normalised coordinates of the
 * original image like every mask — zero for a point. They are kept so the stage can show where it was
 * taken; what the render reads is the colour, stored here and not looked up again, so that the preview,
 * which samples a reduced photo, and the export draw the same selection.
 *
 * [l], [a] and [b] are the sample's mean colour in Oklab, and [spread] how far the colours of an area
 * stray from that mean — zero for a point. An area of a blue sky is a range of blues, not one.
 */
@Serializable
data class ColorSample(
    val x: Float,
    val y: Float,
    val width: Float = 0f,
    val height: Float = 0f,
    val l: Float,
    val a: Float,
    val b: Float,
    val spread: Float = 0f,
)

/**
 * The adjustments that can vary by region.
 *
 * There are thirteen, not twenty. Left out are the tone curve and per-band HSL — 256 samples or
 * eight triads per mask in uniforms, and no defensible semantics for blending two curves where two
 * masks overlap — and the vignette and grain, which are relative to the framing and not to a region
 * of it.
 */
@Serializable
data class LocalAdjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temp: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
) {
    /** A mask without adjustments has no work to give the renderer, however well drawn it is. */
    val isNeutral: Boolean
        get() = this == LocalAdjustments()

    /**
     * Every adjustment times [factor]. The `+ 0f` is not noise: a negative value times zero is −0.0,
     * which a data class compares as different from 0.0, and a mask at 0% would stop being neutral.
     */
    fun scaled(factor: Float): LocalAdjustments = LocalAdjustments(
        exposure = exposure * factor + 0f,
        contrast = contrast * factor + 0f,
        highlights = highlights * factor + 0f,
        shadows = shadows * factor + 0f,
        whites = whites * factor + 0f,
        blacks = blacks * factor + 0f,
        temp = temp * factor + 0f,
        tint = tint * factor + 0f,
        vibrance = vibrance * factor + 0f,
        saturation = saturation * factor + 0f,
        texture = texture * factor + 0f,
        clarity = clarity * factor + 0f,
        dehaze = dehaze * factor + 0f,
    )
}
