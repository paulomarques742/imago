package eu.studio742.imago.feature.editor

import java.util.UUID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LinearMask
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.RadialMask

/**
 * The mask mutations on the recipe.
 *
 * They live apart from [EditorViewModel] for the same reason crop's live in [GeometryCrop]: they are
 * pure functions on the recipe, and this way they are tested without a ViewModel, Android or GL.
 */

/**
 * The thirteen adjustments that make sense inside a mask.
 *
 * The curve and HSL stay out because they are not scalars and there is no defensible way to blend two
 * of them where two masks overlap; the vignette and grain stay out because they are relative to the
 * framing and not to a region of it.
 */
fun Adjustment.isLocal(): Boolean = when (this) {
    Adjustment.EXPOSURE, Adjustment.CONTRAST, Adjustment.HIGHLIGHTS, Adjustment.SHADOWS,
    Adjustment.WHITES, Adjustment.BLACKS, Adjustment.TEMPERATURE, Adjustment.TINT,
    Adjustment.VIBRANCE, Adjustment.SATURATION, Adjustment.TEXTURE, Adjustment.CLARITY,
    Adjustment.DEHAZE,
    -> true
    Adjustment.VIGNETTE_AMOUNT, Adjustment.VIGNETTE_MIDPOINT, Adjustment.VIGNETTE_ROUNDNESS,
    Adjustment.VIGNETTE_FEATHER, Adjustment.GRAIN_AMOUNT, Adjustment.GRAIN_SIZE,
    Adjustment.GRAIN_ROUGHNESS, Adjustment.GRADE_BLENDING, Adjustment.GRADE_BALANCE,
    Adjustment.PERSPECTIVE_VERTICAL, Adjustment.PERSPECTIVE_HORIZONTAL, Adjustment.PERSPECTIVE_ASPECT,
    Adjustment.PERSPECTIVE_SCALE, Adjustment.PERSPECTIVE_OFFSET_X, Adjustment.PERSPECTIVE_OFFSET_Y,
    -> false
}

/**
 * The `when` has an `else` that throws, and not an exhaustive list, because [Adjustment] is a single
 * enumeration for the twenty adjustments. `MaskEditingTest` walks all of it and makes sure the
 * thirteen [isLocal] promises are exactly the thirteen this `when` can handle — without that, a new
 * adjustment would silently fall into the throwing branch, and only in production.
 */
private fun LocalAdjustments.withAdjustment(adjustment: Adjustment, value: Float): LocalAdjustments =
    when (adjustment) {
        Adjustment.EXPOSURE -> copy(exposure = value)
        Adjustment.CONTRAST -> copy(contrast = value)
        Adjustment.HIGHLIGHTS -> copy(highlights = value)
        Adjustment.SHADOWS -> copy(shadows = value)
        Adjustment.WHITES -> copy(whites = value)
        Adjustment.BLACKS -> copy(blacks = value)
        Adjustment.TEMPERATURE -> copy(temp = value)
        Adjustment.TINT -> copy(tint = value)
        Adjustment.VIBRANCE -> copy(vibrance = value)
        Adjustment.SATURATION -> copy(saturation = value)
        Adjustment.TEXTURE -> copy(texture = value)
        Adjustment.CLARITY -> copy(clarity = value)
        Adjustment.DEHAZE -> copy(dehaze = value)
        else -> error("$adjustment is not a local adjustment; see Adjustment.isLocal()")
    }

/** The value of an adjustment inside a mask. The counterpart of [withAdjustment], with the same rule. */
internal fun LocalAdjustments.value(adjustment: Adjustment): Float = when (adjustment) {
    Adjustment.EXPOSURE -> exposure
    Adjustment.CONTRAST -> contrast
    Adjustment.HIGHLIGHTS -> highlights
    Adjustment.SHADOWS -> shadows
    Adjustment.WHITES -> whites
    Adjustment.BLACKS -> blacks
    Adjustment.TEMPERATURE -> temp
    Adjustment.TINT -> tint
    Adjustment.VIBRANCE -> vibrance
    Adjustment.SATURATION -> saturation
    Adjustment.TEXTURE -> texture
    Adjustment.CLARITY -> clarity
    Adjustment.DEHAZE -> dehaze
    else -> error("$adjustment is not a local adjustment; see Adjustment.isLocal()")
}

fun EditRecipe.mask(id: String): LocalMask? = masks.firstOrNull { it.id == id }

fun EditRecipe.localAdjustmentValue(maskId: String, adjustment: Adjustment): Float =
    mask(maskId)?.adjustments?.value(adjustment) ?: adjustment.neutral

fun EditRecipe.withLocalAdjustment(maskId: String, adjustment: Adjustment, value: Float): EditRecipe =
    withMask(maskId) { it.copy(adjustments = it.adjustments.withAdjustment(adjustment, value)) }

/** Replaces a mask with the transformed version. An unknown id changes nothing. */
fun EditRecipe.withMask(maskId: String, transform: (LocalMask) -> LocalMask): EditRecipe {
    if (masks.none { it.id == maskId }) return this
    return copy(masks = masks.map { if (it.id == maskId) transform(it) else it })
}

fun EditRecipe.withoutMask(maskId: String): EditRecipe = copy(masks = masks.filterNot { it.id == maskId })

/**
 * Adds a new mask, centred and still without adjustments.
 *
 * It returns the recipe intact when the ceiling has already been reached — four is the number of
 * channels of the weight field, and not a preference the interface can negotiate.
 */
fun EditRecipe.withNewMask(shape: MaskShape, name: String): Pair<EditRecipe, String?> {
    if (masks.size >= MAX_LOCAL_MASKS) return this to null
    val id = UUID.randomUUID().toString()
    val component = when (shape) {
        MaskShape.LINEAR -> MaskComponent(shape = MaskShape.LINEAR, linear = LinearMask())
        MaskShape.RADIAL -> MaskComponent(shape = MaskShape.RADIAL, radial = RadialMask())
    }
    val mask = LocalMask(id = id, name = name, components = listOf(component))
    return copy(masks = masks + mask) to id
}

/**
 * Moves a mask's first component, in normalised **image** coordinates.
 *
 * The centre is allowed to leave the framing a little on purpose: a gradient whose midpoint is outside
 * the photo is perfectly useful — that is how a transition showing only one of its ends is made.
 */
fun EditRecipe.withMaskMoved(maskId: String, deltaX: Float, deltaY: Float): EditRecipe =
    withMaskComponent(maskId) { component ->
        when (component.shape) {
            MaskShape.LINEAR -> component.linear?.let {
                component.copy(
                    linear = it.copy(
                        x = (it.x + deltaX).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                        y = (it.y + deltaY).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                    ),
                )
            } ?: component
            MaskShape.RADIAL -> component.radial?.let {
                component.copy(
                    radial = it.copy(
                        x = (it.x + deltaX).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                        y = (it.y + deltaY).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                    ),
                )
            } ?: component
        }
    }

/**
 * The radius of one of the radial's axes, absolute and in units of image height.
 *
 * Absolute and not incremental on purpose: the handle follows the finger, and a radius accumulated
 * from deltas drifts away from it with every gesture — a little at a time, enough to notice.
 */
fun EditRecipe.withMaskRadius(maskId: String, radiusX: Float? = null, radiusY: Float? = null): EditRecipe =
    withMaskComponent(maskId) { component ->
        component.radial?.let {
            component.copy(
                radial = it.copy(
                    radiusX = (radiusX ?: it.radiusX).coerceIn(MIN_MASK_RADIUS, MAX_MASK_RADIUS),
                    radiusY = (radiusY ?: it.radiusY).coerceIn(MIN_MASK_RADIUS, MAX_MASK_RADIUS),
                ),
            )
        } ?: component
    }

/** The transition width of a linear gradient, absolute and in units of image height. */
fun EditRecipe.withMaskWidth(maskId: String, width: Float): EditRecipe =
    withMaskComponent(maskId) { component ->
        component.linear?.let {
            component.copy(linear = it.copy(width = width.coerceIn(MIN_MASK_WIDTH, MAX_MASK_WIDTH)))
        } ?: component
    }

/** The radii of the selected radial, or null if the mask is something else. */
fun EditRecipe.maskRadii(maskId: String): Pair<Float, Float>? =
    mask(maskId)?.components?.firstOrNull()?.radial?.let { it.radiusX to it.radiusY }

/** The transition width of the selected linear, or null if the mask is something else. */
fun EditRecipe.maskWidth(maskId: String): Float? =
    mask(maskId)?.components?.firstOrNull()?.linear?.width

/** The shape of the first component, which is the mask's shape while there is only one. */
fun EditRecipe.maskShape(maskId: String): MaskShape? = mask(maskId)?.components?.firstOrNull()?.shape

/** Rotates the mask. The angle is absolute, in degrees, normalised to [0, 360). */
fun EditRecipe.withMaskRotation(maskId: String, degrees: Float): EditRecipe =
    withMaskComponent(maskId) { component ->
        val normalised = ((degrees % 360f) + 360f) % 360f
        when (component.shape) {
            MaskShape.LINEAR -> component.linear?.let { component.copy(linear = it.copy(angle = normalised)) }
                ?: component
            MaskShape.RADIAL -> component.radial?.let { component.copy(radial = it.copy(angle = normalised)) }
                ?: component
        }
    }

/** The angle of the first component, or zero if the mask does not exist. */
fun EditRecipe.maskRotation(maskId: String): Float = mask(maskId)?.components?.firstOrNull()?.let {
    it.linear?.angle ?: it.radial?.angle
} ?: 0f

/**
 * The centre of the first component, in normalised image coordinates. It is what the overlay needs to
 * draw itself and what the hit test needs to know what the finger grabbed.
 */
fun EditRecipe.maskCentre(maskId: String): Pair<Float, Float>? =
    mask(maskId)?.components?.firstOrNull()?.let { component ->
        component.linear?.let { it.x to it.y } ?: component.radial?.let { it.x to it.y }
    }

private fun EditRecipe.withMaskComponent(
    maskId: String,
    transform: (MaskComponent) -> MaskComponent,
): EditRecipe = withMask(maskId) { mask ->
    if (mask.components.isEmpty()) {
        mask
    } else {
        mask.copy(components = mask.components.mapIndexed { index, c -> if (index == 0) transform(c) else c })
    }
}

/**
 * A centre can go half a frame outside, and the mask is still useful: that is how only one end of the
 * gradient is put inside the photo.
 */
const val MIN_MASK_CENTRE = -0.5f
const val MAX_MASK_CENTRE = 1.5f
const val MIN_MASK_RADIUS = 0.02f
const val MAX_MASK_RADIUS = 2f

/** Below the minimum the transition is narrower than the field's grid and gets steps. */
const val MIN_MASK_WIDTH = 0.01f
const val MAX_MASK_WIDTH = 2f

/**
 * The default name of a new mask: "Radial 2", "Linear 1".
 *
 * It counts by shape and not by total, so that deleting a linear does not rename the radials.
 */
fun defaultMaskName(shape: MaskShape, existing: List<LocalMask>): String {
    val prefix = when (shape) {
        MaskShape.LINEAR -> "Linear"
        MaskShape.RADIAL -> "Radial"
    }
    val used = existing.count { it.name.startsWith(prefix) }
    return "$prefix ${used + 1}"
}
