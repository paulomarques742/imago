package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.render.FrameGeometry

/** The radius, in dp, within which a touch counts as having grabbed a handle. */
val MASK_HANDLE_HIT_RADIUS: Dp = 28.dp

/**
 * How far the rotation handle stands off the shape. It clears [MASK_HANDLE_HIT_RADIUS], so on a linear
 * — where it sits on the centre line — a touch on the centre still moves and does not rotate.
 */
val MASK_ROTATE_HANDLE_GAP: Dp = 40.dp

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

    /**
     * A distance on screen, from [at], measured on the photo in units of its height — how much of the
     * photo a brush of [radius] pixels covers at this zoom, crop and rotation.
     */
    fun imageRadius(at: Offset, radius: Float): Float {
        val centre = toImage(at)
        val edge = toImage(at + Offset(radius, 0f))
        return hypot((edge.x - centre.x) * imageAspect, edge.y - centre.y)
    }

    /** From screen pixels to normalised image coordinates. */
    fun toImage(position: Offset): Offset {
        val u = if (bounds.width != 0f) (position.x - bounds.left) / bounds.width else 0f
        val v = if (bounds.height != 0f) (position.y - bounds.top) / bounds.height else 0f
        geometry.imageFromFramed(u, v, scratch)
        return Offset(scratch[0], scratch[1])
    }

    /**
     * Where a new shape goes when [viewport] — screen pixels — is the part of the stage that is not
     * under the chrome: the middle of the photo seen there, sized by its shorter side.
     *
     * What is seen is the frame after crop, zoom and pan, so a shape sized by it keeps its handles on
     * screen however hard the photo was cropped or zoomed.
     */
    fun newShapePlacement(viewport: Rect): MaskPlacement {
        var seen = Rect(
            left = max(viewport.left, bounds.left),
            top = max(viewport.top, bounds.top),
            right = min(viewport.right, bounds.right),
            bottom = min(viewport.bottom, bounds.bottom),
        )
        // Only with the photo panned entirely under the chrome; the whole frame is the next best thing.
        if (seen.width <= 0f || seen.height <= 0f) seen = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val centre = seen.center
        val image = toImage(centre)
        return MaskPlacement(image.x, image.y, imageRadius(centre, min(seen.width, seen.height)))
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

/**
 * Where the handles of one of the mask's components are, or null if it has no such component.
 * [rotateGap] is how far, in screen pixels, the rotation handle stands off the shape.
 */
fun maskHandlesOf(mask: LocalMask, projection: MaskProjection, component: Int, rotateGap: Float): MaskHandles? =
    mask.components.getOrNull(component)?.let { maskHandlesOf(it, projection, rotateGap) }

/** Where a component's handles are, or null if it carries no shape. */
fun maskHandlesOf(component: MaskComponent, projection: MaskProjection, rotateGap: Float): MaskHandles? {
    // The gap is measured on screen and not on the photo: in image heights, a crop of a fifth of the
    // photo or a zoom put the rotation handle off screen while the shape itself still fitted.
    fun gapAt(x: Float, y: Float) = projection.imageRadius(projection.toScreen(x, y), rotateGap)
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
                    -(radial.radiusX + gapAt(radial.x, radial.y)),
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
                rotate = projection.handleAt(linear.x, linear.y, linear.angle, gapAt(linear.x, linear.y), 0f),
            )
        }
        // A brush has no handles: it is painted, not dragged. A range has none either: it is sampled.
        MaskShape.BRUSH, MaskShape.LUMINANCE, MaskShape.COLOR -> null
    }
}

/**
 * Where a component's pin goes: a gradient's centre, the start of a brush's first stroke, or a Color
 * Range's first sample, which is where Lightroom puts them. Null for a brush with nothing painted, a
 * Color Range not sampled yet, and a Luminance Range, which keeps no place on the photo.
 */
fun componentPin(component: MaskComponent, projection: MaskProjection): Offset? =
    maskHandlesOf(component, projection, rotateGap = 0f)?.centre
        ?: component.brush?.strokes?.firstOrNull()?.points?.takeIf { it.size >= 2 }?.let { projection.toScreen(it[0], it[1]) }
        ?: component.color?.samples?.firstOrNull()?.let { projection.toScreen(it.x, it.y) }

/**
 * The brush's circle on the photo: where the finger or the pointer is, how far the brush reaches and
 * where its hard core ends.
 */
data class BrushCursor(val centre: Offset, val radius: Float, val feather: Float)

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
 * The mask's frame over the photo: the shapes, the selected one's handles, and a pin on each of the
 * others — Lightroom's pins, which are how another shape of the same mask is picked on the photo.
 *
 * The red **fill** is not drawn here — it comes out of the pipeline itself, in `COMPOSITE`. Drawing
 * it here too would be a second interpretation of the mask, and the two would disagree as soon as
 * there was inversion or composition of components. Only the outlines are drawn here, which is the
 * only part the pipeline cannot show.
 */
@Composable
fun MaskOverlay(
    mask: LocalMask,
    selectedComponent: Int,
    bounds: PhotoBounds,
    geometry: FrameGeometry,
    modifier: Modifier = Modifier,
    brushCursor: BrushCursor? = null,
    /** The two corners, in screen pixels, of the area a range is being sampled from, while it is dragged. */
    samplingArea: Pair<Offset, Offset>? = null,
) {
    Canvas(modifier = modifier) {
        val projection = MaskProjection(bounds, geometry)
        mask.components.forEachIndexed { index, component ->
            if (index == selectedComponent) return@forEachIndexed
            drawComponentOutline(component, projection, OTHER_COMPONENT_ALPHA)
            componentPin(component, projection)?.let { drawPin(it) }
        }
        mask.components.getOrNull(selectedComponent)?.let { selected ->
            drawComponentOutline(selected, projection)
            val handles = maskHandlesOf(selected, projection, MASK_ROTATE_HANDLE_GAP.toPx())
            if (handles != null) {
                listOfNotNull(handles.radiusX, handles.radiusY, handles.width).forEach { drawHandle(it, filled = false) }
                drawHandle(handles.rotate, filled = false)
                drawHandle(handles.centre, filled = true)
            } else {
                componentPin(selected, projection)?.let { drawHandle(it, filled = true) }
            }
        }
        brushCursor?.let { drawBrushCursor(it) }
        samplingArea?.let { (from, to) -> drawSamplingArea(from, to) }
    }
}

/** The rectangle being dragged on the photo, the way Lightroom draws its sampling area. */
private fun DrawScope.drawSamplingArea(from: Offset, to: Offset) {
    val stroke = OUTLINE_WIDTH.toPx()
    val topLeft = Offset(min(from.x, to.x), min(from.y, to.y))
    val size = androidx.compose.ui.geometry.Size(kotlin.math.abs(to.x - from.x), kotlin.math.abs(to.y - from.y))
    drawRect(Color.Black.copy(alpha = 0.5f), topLeft = topLeft - Offset(stroke, stroke), size = size.copy(width = size.width + stroke * 2, height = size.height + stroke * 2), style = Stroke(width = stroke))
    drawRect(OUTLINE_COLOR, topLeft = topLeft, size = size, style = Stroke(width = stroke))
}

/**
 * Where each sample of a Color Range was taken: a point's marker, or an area's rectangle, projected so
 * that it stays on the colour it read under any crop or rotation.
 */
private fun DrawScope.drawColorSamples(component: MaskComponent, projection: MaskProjection, alpha: Float) {
    val samples = component.color?.samples ?: return
    val stroke = HANDLE_STROKE.toPx()
    samples.forEach { sample ->
        if (sample.width > 0f && sample.height > 0f) {
            val halfW = sample.width / 2f
            val halfH = sample.height / 2f
            val corners = listOf(
                projection.toScreen(sample.x - halfW, sample.y - halfH),
                projection.toScreen(sample.x + halfW, sample.y - halfH),
                projection.toScreen(sample.x + halfW, sample.y + halfH),
                projection.toScreen(sample.x - halfW, sample.y + halfH),
            )
            val path = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, color = OUTLINE_COLOR.copy(alpha = 0.9f * alpha), style = Stroke(width = OUTLINE_WIDTH.toPx()))
        }
        val centre = projection.toScreen(sample.x, sample.y)
        val radius = SAMPLE_RADIUS.toPx()
        drawCircle(color = Color.Black.copy(alpha = 0.5f * alpha), radius = radius + stroke, center = centre)
        drawCircle(color = OUTLINE_COLOR.copy(alpha = alpha), radius = radius, center = centre, style = Stroke(width = stroke))
    }
}

/** Two circles: the brush's reach, and dimmer, where its feather begins. */
private fun DrawScope.drawBrushCursor(cursor: BrushCursor) {
    val stroke = OUTLINE_WIDTH.toPx()
    drawCircle(Color.Black.copy(alpha = 0.5f), radius = cursor.radius + stroke, center = cursor.centre, style = Stroke(width = stroke))
    drawCircle(OUTLINE_COLOR, radius = cursor.radius, center = cursor.centre, style = Stroke(width = stroke))
    val inner = cursor.radius * (1f - (cursor.feather / 100f).coerceIn(0f, 1f))
    if (inner > stroke * 2 && inner < cursor.radius - stroke) {
        drawCircle(OUTLINE_COLOR.copy(alpha = 0.45f), radius = inner, center = cursor.centre, style = Stroke(width = stroke))
    }
}

/**
 * The component whose pin is under the finger, among those not selected; null when none is. The
 * selected one's handles are tested first by the caller, so a pin under a handle never steals it.
 */
fun hitMaskComponentPin(
    mask: LocalMask,
    selectedComponent: Int,
    projection: MaskProjection,
    position: Offset,
    radius: Float,
): Int? = mask.components.indices
    .filter { it != selectedComponent }
    .mapNotNull { index ->
        componentPin(mask.components[index], projection)?.let { centre ->
            index to hypot(centre.x - position.x, centre.y - position.y)
        }
    }
    .filter { (_, distance) -> distance <= radius }
    .minByOrNull { (_, distance) -> distance }
    ?.first

/**
 * The mask's shapes without their handles: the editor draws the handles on top, the "Show your edit"
 * video draws the shapes alone while the mask's sliders rise.
 */
internal fun DrawScope.drawMaskOutline(mask: LocalMask, projection: MaskProjection, alpha: Float = 1f) {
    mask.components.forEach { drawComponentOutline(it, projection, alpha) }
}

private fun DrawScope.drawComponentOutline(component: MaskComponent, projection: MaskProjection, alpha: Float = 1f) {
    when (component.shape) {
        MaskShape.RADIAL -> component.radial?.let { radial ->
            // The ellipse is drawn point by point and projected, and not with `drawOval`: under a
            // crop with fine rotation the frame is no longer aligned with the screen, and an oval
            // drawn in screen coordinates was crooked compared to what the pipeline applies.
            drawProjectedEllipse(projection, radial.x, radial.y, radial.radiusX, radial.radiusY, radial.angle, alpha)
        }
        MaskShape.LINEAR -> component.linear?.let { linear ->
            val half = linear.width * 0.5f
            listOf(-half to 0.35f, 0f to 0.9f, half to 0.35f).forEach { (offset, lineAlpha) ->
                drawProjectedLine(projection, linear.x, linear.y, linear.angle, offset, lineAlpha * alpha)
            }
        }
        // What a brush or a Luminance Range covers is shown by the red, which comes from the pipeline
        // itself; a Color Range also shows where its samples were taken.
        MaskShape.BRUSH, MaskShape.LUMINANCE -> Unit
        MaskShape.COLOR -> drawColorSamples(component, projection, alpha)
    }
}

private fun DrawScope.drawProjectedEllipse(
    projection: MaskProjection,
    centreX: Float,
    centreY: Float,
    radiusX: Float,
    radiusY: Float,
    angleDegrees: Float,
    alpha: Float,
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
    drawPath(path, color = OUTLINE_COLOR.copy(alpha = 0.9f * alpha), style = Stroke(width = OUTLINE_WIDTH.toPx()))
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

/** Another shape's pin: smaller and dimmer than a handle, so it does not read as one. */
private fun DrawScope.drawPin(position: Offset) {
    val radius = PIN_RADIUS.toPx()
    drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = radius + HANDLE_STROKE.toPx(), center = position)
    drawCircle(color = OUTLINE_COLOR.copy(alpha = OTHER_COMPONENT_ALPHA), radius = radius, center = position)
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
    MaskShape.BRUSH, MaskShape.LUMINANCE, MaskShape.COLOR -> 0f
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
private val PIN_RADIUS = 6.dp
private val SAMPLE_RADIUS = 7.dp
private val OUTLINE_WIDTH = 1.5.dp

/** The shapes of the mask that are not being edited: present, but behind the one that is. */
private const val OTHER_COMPONENT_ALPHA = 0.45f

private val OUTLINE_COLOR = Color(0xFFF2F2F0)

/** A component's centre in normalised image coordinates, or null if it has no shape. */
fun LocalMask.centreInImage(component: Int = 0): Offset? = components.getOrNull(component)?.let { shape ->
    shape.radial?.let { Offset(it.x, it.y) } ?: shape.linear?.let { Offset(it.x, it.y) }
}

/** A component's angle, in degrees. */
fun LocalMask.angleInImage(component: Int = 0): Float = components.getOrNull(component)?.let { shape ->
    shape.radial?.angle ?: shape.linear?.angle
} ?: 0f

/** The angular offset of a component's rotation handle. */
fun LocalMask.rotateHandleOffset(component: Int = 0): Float =
    components.getOrNull(component)?.shape?.rotateHandleOffset() ?: 0f
