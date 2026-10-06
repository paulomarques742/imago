package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import eu.studio742.imago.core.render.PhotoBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.render.FrameGeometry

/** The radius, in dp, within which a touch counts as having grabbed a handle. */
val MASK_HANDLE_HIT_RADIUS: Dp = 28.dp

enum class MaskHandle { MOVE, RADIUS_X, RADIUS_Y, WIDTH, ROTATE }

/**
 * A mask's handles, already in screen pixels.
 *
 * The overlay draws them and the gesture block tests them, and both start from this same structure —
 * two separate calculations for the same place would be two ways for the handle to end up where the
 * finger is not.
 */
data class MaskHandles(
    val centre: Offset,
    val radiusX: Offset?,
    val radiusY: Offset?,
    val width: Offset?,
    val rotate: Offset,
)

/**
 * The coordinate bridge of the mask editor.
 *
 * A mask lives in normalised **image** coordinates; what is on screen is the **frame**.
 * [FrameGeometry] crosses between the two, and [bounds] puts the frame in pixels. It is the same
 * crossing the weight field makes on the CPU side.
 */
class MaskProjection(
    private val bounds: PhotoBounds,
    private val geometry: FrameGeometry,
) {
    private val scratch = FloatArray(2)

    val imageAspect: Float get() = geometry.imageAspect

    /** From normalised image coordinates to screen pixels. */
    fun toScreen(imageX: Float, imageY: Float): Offset {
        geometry.framedFromImage(imageX, imageY, scratch)
        return Offset(bounds.left + scratch[0] * bounds.width, bounds.top + scratch[1] * bounds.height)
    }

    /** From screen pixels to normalised image coordinates. */
    fun toImage(position: Offset): Offset {
        val u = if (bounds.width != 0f) (position.x - bounds.left) / bounds.width else 0f
        val v = if (bounds.height != 0f) (position.y - bounds.top) / bounds.height else 0f
        geometry.imageFromFramed(u, v, scratch)
        return Offset(scratch[0], scratch[1])
    }
}

/**
 * A handle's point, given in the component's frame of reference: [along] the major axis and [across]
 * the minor one, both in units of image height.
 */
private fun MaskProjection.handleAt(
    centreX: Float,
    centreY: Float,
    angleDegrees: Float,
    along: Float,
    across: Float,
): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    // The inverse of `PhotoEffects.maskLocal*`: from the component's frame of reference to the image's.
    val dx = along * cosine - across * sine
    val dy = along * sine + across * cosine
    return toScreen(centreX + dx / imageAspect, centreY + dy)
}

/** Where this mask's handles are, or null if it has no shape at all. */
fun maskHandlesOf(mask: LocalMask, projection: MaskProjection): MaskHandles? {
    val component = mask.components.firstOrNull() ?: return null
    return when (component.shape) {
        MaskShape.RADIAL -> component.radial?.let { radial ->
            MaskHandles(
                centre = projection.toScreen(radial.x, radial.y),
                radiusX = projection.handleAt(radial.x, radial.y, radial.angle, radial.radiusX, 0f),
                radiusY = projection.handleAt(radial.x, radial.y, radial.angle, 0f, radial.radiusY),
                width = null,
                rotate = projection.handleAt(
                    radial.x,
                    radial.y,
                    radial.angle,
                    -(radial.radiusX + ROTATE_HANDLE_GAP),
                    0f,
                ),
            )
        }
        MaskShape.LINEAR -> component.linear?.let { linear ->
            MaskHandles(
                centre = projection.toScreen(linear.x, linear.y),
                radiusX = null,
                radiusY = null,
                width = projection.handleAt(linear.x, linear.y, linear.angle, 0f, linear.width * 0.5f),
                rotate = projection.handleAt(linear.x, linear.y, linear.angle, ROTATE_HANDLE_GAP, 0f),
            )
        }
    }
}

/**
 * Which of the handles the finger grabbed, if any.
 *
 * The order is not arbitrary: the centre is tested last, because with the mask shrunk the other
 * handles fall almost on top of it and moving would become the only possible thing.
 */
fun hitMaskHandle(handles: MaskHandles, position: Offset, radius: Float): MaskHandle? {
    fun near(point: Offset?) = point != null && hypot(point.x - position.x, point.y - position.y) <= radius
    return when {
        near(handles.rotate) -> MaskHandle.ROTATE
        near(handles.radiusX) -> MaskHandle.RADIUS_X
        near(handles.radiusY) -> MaskHandle.RADIUS_Y
        near(handles.width) -> MaskHandle.WIDTH
        near(handles.centre) -> MaskHandle.MOVE
        else -> null
    }
}

/**
 * The mask's frame over the photo: the shape and the handles.
 *
 * The red **fill** is not drawn here — it comes out of the pipeline itself, in `COMPOSITE`. Drawing
 * it here too would be a second interpretation of the mask, and the two would disagree as soon as
 * there was inversion or composition of components. Only the outline is drawn here, which is the
 * only part the pipeline cannot show.
 */
@Composable
fun MaskOverlay(
    mask: LocalMask,
    bounds: PhotoBounds,
    geometry: FrameGeometry,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val projection = MaskProjection(bounds, geometry)
        val component = mask.components.firstOrNull() ?: return@Canvas
        when (component.shape) {
            MaskShape.RADIAL -> component.radial?.let { radial ->
                // The ellipse is drawn point by point and projected, and not with `drawOval`: under a
                // crop with fine rotation the frame is no longer aligned with the screen, and an oval
                // drawn in screen coordinates was crooked compared to what the pipeline applies.
                drawProjectedEllipse(projection, radial.x, radial.y, radial.radiusX, radial.radiusY, radial.angle)
            }
            MaskShape.LINEAR -> component.linear?.let { linear ->
                val half = linear.width * 0.5f
                listOf(-half to 0.35f, 0f to 0.9f, half to 0.35f).forEach { (offset, alpha) ->
                    drawProjectedLine(projection, linear.x, linear.y, linear.angle, offset, alpha)
                }
            }
        }
        maskHandlesOf(mask, projection)?.let { handles ->
            listOfNotNull(handles.radiusX, handles.radiusY, handles.width).forEach { drawHandle(it, filled = false) }
            drawHandle(handles.rotate, filled = false)
            drawHandle(handles.centre, filled = true)
        }
    }
}

private fun DrawScope.drawProjectedEllipse(
    projection: MaskProjection,
    centreX: Float,
    centreY: Float,
    radiusX: Float,
    radiusY: Float,
    angleDegrees: Float,
) {
    val path = Path()
    for (step in 0..ELLIPSE_STEPS) {
        val theta = 2.0 * Math.PI * step / ELLIPSE_STEPS
        val point = projection.handleAt(
            centreX,
            centreY,
            angleDegrees,
            (radiusX * cos(theta)).toFloat(),
            (radiusY * sin(theta)).toFloat(),
        )
        if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path, color = OUTLINE_COLOR.copy(alpha = 0.9f), style = Stroke(width = OUTLINE_WIDTH.toPx()))
}

private fun DrawScope.drawProjectedLine(
    projection: MaskProjection,
    centreX: Float,
    centreY: Float,
    angleDegrees: Float,
    across: Float,
    alpha: Float,
) {
    // Long enough to cross any framing; the canvas takes care of clipping.
    val start = projection.handleAt(centreX, centreY, angleDegrees, -LINE_REACH, across)
    val end = projection.handleAt(centreX, centreY, angleDegrees, LINE_REACH, across)
    drawLine(
        color = OUTLINE_COLOR.copy(alpha = alpha),
        start = start,
        end = end,
        strokeWidth = OUTLINE_WIDTH.toPx(),
    )
}

private fun DrawScope.drawHandle(position: Offset, filled: Boolean) {
    val radius = (if (filled) HANDLE_RADIUS else HANDLE_RADIUS_SECONDARY).toPx()
    val stroke = HANDLE_STROKE.toPx()
    // The dark ring underneath is not decoration: over a light sky, a white circle without an outline
    // disappears, and the handle stops showing precisely where a gradient is usually placed.
    drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = radius + stroke, center = position)
    if (filled) {
        drawCircle(color = OUTLINE_COLOR, radius = radius, center = position)
    } else {
        drawCircle(color = OUTLINE_COLOR, radius = radius, center = position, style = Stroke(width = stroke))
    }
}

/**
 * The finger's position in the component's frame of reference, in units of image height.
 *
 * It is the same calculation `PhotoEffects.maskLocal*` does per texel — centre, correct the aspect
 * ratio, counter-rotate. It has to be the same, or the handle pulls the radius to one place and the
 * field draws it in another.
 */
fun maskLocalOffset(
    centreX: Float,
    centreY: Float,
    pointX: Float,
    pointY: Float,
    angleDegrees: Float,
    aspect: Float,
): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val dx = (pointX - centreX) * aspect
    val dy = pointY - centreY
    return Offset(dx * cosine + dy * sine, -dx * sine + dy * cosine)
}

/**
 * The angle that puts the rotation handle under the finger.
 *
 * The handle is at 180° from the major axis in the radial and at 0° in the linear, and that
 * difference is what [handleOffset] brings — without it the shape jumped half a turn when grabbed.
 */
fun maskAngleFrom(
    centreX: Float,
    centreY: Float,
    pointX: Float,
    pointY: Float,
    aspect: Float,
    handleOffset: Float,
): Float {
    val dx = (pointX - centreX) * aspect
    val dy = pointY - centreY
    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    return ((degrees + handleOffset) % 360f + 360f) % 360f
}

/** The angular offset of each shape's rotation handle. */
fun MaskShape.rotateHandleOffset(): Float = when (this) {
    MaskShape.RADIAL -> 180f
    MaskShape.LINEAR -> 0f
}

private const val ELLIPSE_STEPS = 64
private const val LINE_REACH = 4f

/**
 * Handles are measured in dp, not pixels.
 *
 * They used to be in pixels, and on a 3.5x screen a seven-pixel radius gave two dp — visible in a
 * screenshot and practically invisible on the device, which is where this is used. The touch target
 * ([MASK_HANDLE_HIT_RADIUS]) was always larger than the drawing, but a handle that cannot be seen is
 * not grabbed however generous the target is.
 */
private val HANDLE_RADIUS = 10.dp
private val HANDLE_RADIUS_SECONDARY = 9.dp
private val HANDLE_STROKE = 2.dp
private val OUTLINE_WIDTH = 1.5.dp

/** The rotation handle's offset, in units of image height. */
private const val ROTATE_HANDLE_GAP = 0.11f
private val OUTLINE_COLOR = Color(0xFFF2F2F0)

/** The mask's centre in normalised image coordinates, or null if it has no shape. */
fun LocalMask.centreInImage(): Offset? = components.firstOrNull()?.let { component ->
    component.radial?.let { Offset(it.x, it.y) } ?: component.linear?.let { Offset(it.x, it.y) }
}

/** The mask's angle, in degrees. */
fun LocalMask.angleInImage(): Float = components.firstOrNull()?.let { component ->
    component.radial?.angle ?: component.linear?.angle
} ?: 0f

/** The angular offset of this mask's rotation handle. */
fun LocalMask.rotateHandleOffset(): Float =
    components.firstOrNull()?.shape?.rotateHandleOffset() ?: 0f
