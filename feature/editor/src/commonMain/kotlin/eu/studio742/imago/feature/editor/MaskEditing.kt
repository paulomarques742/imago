package eu.studio742.imago.feature.editor

import java.util.UUID
import eu.studio742.imago.core.model.BrushMask
import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.MAX_BRUSH_COMPONENTS
import eu.studio742.imago.core.model.LinearMask
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MASK_AMOUNT_MAX
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MAX_MASK_COMPONENTS
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskOp
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
    Adjustment.PERSPECTIVE_DISTORTION, Adjustment.PERSPECTIVE_VERTICAL, Adjustment.PERSPECTIVE_HORIZONTAL,
    Adjustment.PERSPECTIVE_ASPECT, Adjustment.PERSPECTIVE_SCALE, Adjustment.PERSPECTIVE_OFFSET_X,
    Adjustment.PERSPECTIVE_OFFSET_Y, Adjustment.MASK_AMOUNT,
    -> false
}

/**
 * The sliders of an open mask: the thirteen local adjustments and the Amount. The Amount is not
 * local — it is not an adjustment that varies by region but a property of the mask — and so it stays
 * out of [isLocal], which says what [LocalAdjustments] holds.
 */
fun Adjustment.isMaskSlider(): Boolean = isLocal() || this == Adjustment.MASK_AMOUNT

/** A mask slider's value: the Amount from the mask, the rest from its adjustments. */
fun EditRecipe.maskSliderValue(maskId: String, adjustment: Adjustment): Float =
    if (adjustment == Adjustment.MASK_AMOUNT) {
        mask(maskId)?.amount ?: adjustment.neutral
    } else {
        localAdjustmentValue(maskId, adjustment)
    }

fun EditRecipe.withMaskSlider(maskId: String, adjustment: Adjustment, value: Float): EditRecipe =
    if (adjustment == Adjustment.MASK_AMOUNT) {
        withMaskAmount(maskId, value)
    } else {
        withLocalAdjustment(maskId, adjustment, value)
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

/** How many components the recipe's masks hold together, against [MAX_MASK_COMPONENTS]. */
val EditRecipe.maskComponentCount: Int
    get() = masks.sumOf { it.components.size }

/** Whether one more component fits in the field shader's uniform arrays. */
val EditRecipe.canAddMaskComponent: Boolean
    get() = maskComponentCount < MAX_MASK_COMPONENTS

/** How many brushes the recipe's masks hold, against the layers of the brush texture. */
val EditRecipe.brushComponentCount: Int
    get() = masks.sumOf { mask -> mask.components.count { it.shape == MaskShape.BRUSH } }

/** Whether one more component of [shape] fits: a brush also needs a layer of its own. */
fun EditRecipe.canAddMaskComponent(shape: MaskShape): Boolean =
    canAddMaskComponent && (shape != MaskShape.BRUSH || brushComponentCount < MAX_BRUSH_COMPONENTS)

/** A component of [shape], centred, with the shape's defaults; a brush starts with nothing painted. */
internal fun newMaskComponent(shape: MaskShape, op: MaskOp = MaskOp.ADD): MaskComponent = when (shape) {
    MaskShape.LINEAR -> MaskComponent(shape = MaskShape.LINEAR, op = op, linear = LinearMask())
    MaskShape.RADIAL -> MaskComponent(shape = MaskShape.RADIAL, op = op, radial = RadialMask())
    MaskShape.BRUSH -> MaskComponent(shape = MaskShape.BRUSH, op = op, brush = BrushMask())
}

/**
 * Adds a new mask, centred and still without adjustments.
 *
 * It returns the recipe intact when the ceiling has already been reached — eight is the number of
 * channels of the two weight fields, and not a preference the interface can negotiate.
 */
fun EditRecipe.withNewMask(shape: MaskShape, name: String): Pair<EditRecipe, String?> {
    if (masks.size >= MAX_LOCAL_MASKS || !canAddMaskComponent(shape)) return this to null
    val id = UUID.randomUUID().toString()
    val mask = LocalMask(id = id, name = name, components = listOf(newMaskComponent(shape)))
    return copy(masks = masks + mask) to id
}

/**
 * Adds a component to a mask — Lightroom's Add, Subtract and Intersect — and returns its index.
 *
 * The component goes at the end because the operations are applied in order: a subtraction only
 * takes away from what comes before it. Null when the mask does not exist or the components are at
 * their ceiling.
 */
fun EditRecipe.withNewMaskComponent(maskId: String, shape: MaskShape, op: MaskOp): Pair<EditRecipe, Int?> {
    val mask = mask(maskId) ?: return this to null
    if (!canAddMaskComponent(shape)) return this to null
    return withMask(maskId) { it.copy(components = it.components + newMaskComponent(shape, op)) } to
        mask.components.size
}

/**
 * Removes a component. Removing the last one deletes the mask, as in Lightroom: a mask with no shape
 * selects nothing.
 */
fun EditRecipe.withoutMaskComponent(maskId: String, index: Int): EditRecipe {
    val mask = mask(maskId) ?: return this
    if (index !in mask.components.indices) return this
    if (mask.components.size == 1) return withoutMask(maskId)
    val remaining = mask.components.filterIndexed { position, _ -> position != index }
    // When the first goes, the next one becomes the seed the others combine with. Its operation
    // stops meaning anything there, and it is written as Add so that the list says what is drawn.
    val reseeded = if (index == 0) {
        listOf(remaining.first().copy(op = MaskOp.ADD)) + remaining.drop(1)
    } else {
        remaining
    }
    return withMask(maskId) { it.copy(components = reseeded) }
}

/** Inverts one component, inside the mask, before it combines with the others. */
fun EditRecipe.withMaskComponentInverted(maskId: String, index: Int, inverted: Boolean): EditRecipe =
    withMaskComponent(maskId, index) { it.copy(inverted = inverted) }

/** The mask Amount, in percent, between 0 and 200. */
fun EditRecipe.withMaskAmount(maskId: String, amount: Float): EditRecipe =
    withMask(maskId) { it.copy(amount = amount.coerceIn(0f, MASK_AMOUNT_MAX)) }

/**
 * A copy of the mask, right after it, under a new name — and, for Lightroom's Duplicate and Invert,
 * inverted. Inverting the whole mask gives what Lightroom gets by flipping the first component and
 * swapping Add and Subtract on the rest, and it keeps the copy's components identical to the
 * original's.
 */
fun EditRecipe.withDuplicatedMask(maskId: String, invert: Boolean): Pair<EditRecipe, String?> {
    val index = masks.indexOfFirst { it.id == maskId }
    if (index < 0 || masks.size >= MAX_LOCAL_MASKS) return this to null
    val original = masks[index]
    if (maskComponentCount + original.components.size > MAX_MASK_COMPONENTS) return this to null
    val originalBrushes = original.components.count { it.shape == MaskShape.BRUSH }
    if (brushComponentCount + originalBrushes > MAX_BRUSH_COMPONENTS) return this to null
    val shape = original.components.firstOrNull()?.shape ?: MaskShape.RADIAL
    val duplicate = original.copy(
        id = UUID.randomUUID().toString(),
        name = defaultMaskName(shape, masks),
        inverted = if (invert) !original.inverted else original.inverted,
    )
    val reordered = masks.toMutableList().apply { add(index + 1, duplicate) }
    return copy(masks = reordered) to duplicate.id
}

/**
 * Moves a component, in normalised **image** coordinates.
 *
 * The centre is allowed to leave the framing a little on purpose: a gradient whose midpoint is outside
 * the photo is perfectly useful — that is how a transition showing only one of its ends is made.
 */
fun EditRecipe.withMaskMoved(maskId: String, deltaX: Float, deltaY: Float, component: Int = 0): EditRecipe =
    withMaskComponent(maskId, component) { shape ->
        when (shape.shape) {
            MaskShape.LINEAR -> shape.linear?.let {
                shape.copy(
                    linear = it.copy(
                        x = (it.x + deltaX).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                        y = (it.y + deltaY).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                    ),
                )
            } ?: shape
            MaskShape.RADIAL -> shape.radial?.let {
                shape.copy(
                    radial = it.copy(
                        x = (it.x + deltaX).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                        y = (it.y + deltaY).coerceIn(MIN_MASK_CENTRE, MAX_MASK_CENTRE),
                    ),
                )
            } ?: shape
            // A painted stroke is where it was painted; moving it would be a different stroke.
            MaskShape.BRUSH -> shape
        }
    }

/** Starts a stroke in a brush component: the first point, with the brush it is painted with. */
fun EditRecipe.withBrushStroke(maskId: String, component: Int, stroke: BrushStroke): EditRecipe =
    withMaskComponent(maskId, component) { shape ->
        shape.brush?.let { shape.copy(brush = it.copy(strokes = it.strokes + stroke)) } ?: shape
    }

/** One more point at the end of the brush's last stroke, in normalised image coordinates. */
fun EditRecipe.withBrushStrokeExtended(maskId: String, component: Int, x: Float, y: Float): EditRecipe =
    withMaskComponent(maskId, component) { shape ->
        val brush = shape.brush ?: return@withMaskComponent shape
        val last = brush.strokes.lastOrNull() ?: return@withMaskComponent shape
        val extended = last.copy(points = last.points + roundedBrushCoordinate(x) + roundedBrushCoordinate(y))
        shape.copy(brush = brush.copy(strokes = brush.strokes.dropLast(1) + extended))
    }

/**
 * A brush point kept to four decimals: a tenth of a pixel on a 1000-pixel photo, under half a pixel
 * on a 24 Mpx one, and half the characters in the JSON that goes to sync.
 */
internal fun roundedBrushCoordinate(value: Float): Float = kotlin.math.round(value * 10_000f) / 10_000f

/**
 * The radius of one of the radial's axes, absolute and in units of image height.
 *
 * Absolute and not incremental on purpose: the handle follows the finger, and a radius accumulated
 * from deltas drifts away from it with every gesture — a little at a time, enough to notice.
 */
fun EditRecipe.withMaskRadius(
    maskId: String,
    radiusX: Float? = null,
    radiusY: Float? = null,
    component: Int = 0,
): EditRecipe = withMaskComponent(maskId, component) { shape ->
    shape.radial?.let {
        shape.copy(
            radial = it.copy(
                radiusX = (radiusX ?: it.radiusX).coerceIn(MIN_MASK_RADIUS, MAX_MASK_RADIUS),
                radiusY = (radiusY ?: it.radiusY).coerceIn(MIN_MASK_RADIUS, MAX_MASK_RADIUS),
            ),
        )
    } ?: shape
}

/** The transition width of a linear gradient, absolute and in units of image height. */
fun EditRecipe.withMaskWidth(maskId: String, width: Float, component: Int = 0): EditRecipe =
    withMaskComponent(maskId, component) { shape ->
        shape.linear?.let {
            shape.copy(linear = it.copy(width = width.coerceIn(MIN_MASK_WIDTH, MAX_MASK_WIDTH)))
        } ?: shape
    }

private fun EditRecipe.maskComponent(maskId: String, component: Int): MaskComponent? =
    mask(maskId)?.components?.getOrNull(component)

/** The radii of a radial component, or null if it is something else. */
fun EditRecipe.maskRadii(maskId: String, component: Int = 0): Pair<Float, Float>? =
    maskComponent(maskId, component)?.radial?.let { it.radiusX to it.radiusY }

/** The transition width of a linear component, or null if it is something else. */
fun EditRecipe.maskWidth(maskId: String, component: Int = 0): Float? =
    maskComponent(maskId, component)?.linear?.width

/** The shape of a component. The first one's is the mask's, the one that names it. */
fun EditRecipe.maskShape(maskId: String, component: Int = 0): MaskShape? = maskComponent(maskId, component)?.shape

/** Rotates a component. The angle is absolute, in degrees, normalised to [0, 360). */
fun EditRecipe.withMaskRotation(maskId: String, degrees: Float, component: Int = 0): EditRecipe =
    withMaskComponent(maskId, component) { shape ->
        val normalised = ((degrees % 360f) + 360f) % 360f
        when (shape.shape) {
            MaskShape.LINEAR -> shape.linear?.let { shape.copy(linear = it.copy(angle = normalised)) } ?: shape
            MaskShape.RADIAL -> shape.radial?.let { shape.copy(radial = it.copy(angle = normalised)) } ?: shape
            MaskShape.BRUSH -> shape
        }
    }

/** The angle of a component, or zero if it does not exist. */
fun EditRecipe.maskRotation(maskId: String, component: Int = 0): Float =
    maskComponent(maskId, component)?.let { it.linear?.angle ?: it.radial?.angle } ?: 0f

/**
 * The centre of a component, in normalised image coordinates. It is what the overlay needs to draw
 * itself and what the hit test needs to know what the finger grabbed.
 */
fun EditRecipe.maskCentre(maskId: String, component: Int = 0): Pair<Float, Float>? =
    maskComponent(maskId, component)?.let { shape ->
        shape.linear?.let { it.x to it.y } ?: shape.radial?.let { it.x to it.y }
    }

private fun EditRecipe.withMaskComponent(
    maskId: String,
    component: Int,
    transform: (MaskComponent) -> MaskComponent,
): EditRecipe = withMask(maskId) { mask ->
    if (component !in mask.components.indices) {
        mask
    } else {
        mask.copy(components = mask.components.mapIndexed { index, c -> if (index == component) transform(c) else c })
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
        MaskShape.BRUSH -> "Brush"
    }
    val used = existing.count { it.name.startsWith(prefix) }
    return "$prefix ${used + 1}"
}
