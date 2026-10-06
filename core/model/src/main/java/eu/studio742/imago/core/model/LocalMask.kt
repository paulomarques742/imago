package eu.studio742.imago.core.model

import kotlinx.serialization.Serializable

/**
 * How many masks a recipe can have.
 *
 * It is not a number chosen by taste: the weight field the renderer builds is an RGBA texture, and
 * each mask takes one channel. That is what allows applying all four at once with a dot product per
 * parameter, instead of a dynamically indexed loop — which on several GLSL ES compilers falls back
 * to private memory and eats the 16 ms budget on its own. Going up to eight means a second render
 * target and twice the uniforms in the tonal pass.
 */
const val MAX_LOCAL_MASKS = 4

/**
 * How many components fit in total, across all masks.
 *
 * It is the size of the uniform block the field shader declares. Four single-component masks —
 * what the interface can create today — use half; the rest is headroom for when subtract and
 * intersect reach the interface.
 */
const val MAX_MASK_COMPONENTS = 8

/**
 * The shape of a mask component.
 *
 * Adding values is backward compatible: nothing writes them until there is code that produces them,
 * and an old recipe never contains them.
 */
@Serializable
enum class MaskShape { LINEAR, RADIAL }

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
)

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
}
