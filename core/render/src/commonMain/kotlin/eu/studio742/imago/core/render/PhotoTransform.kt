package eu.studio742.imago.core.render

import kotlin.math.abs

/**
 * The preview's zoom and pan state, in screen coordinates.
 *
 * `panX`/`panY` are the offset of the photo's centre in surface pixels, with the Y axis growing
 * downwards as in Compose. The renderer flips Y when setting up the viewport.
 *
 * The maths lives here — and not in the screen — for two reasons. It is testable without a device,
 * and it shares the `fit` calculation with [calculatePhotoViewport]: as long as the gesture's limits
 * and the drawing's limits are computed by the same function, they cannot diverge again.
 */
data class PhotoTransform(
    val zoom: Float = MIN_PHOTO_ZOOM,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val isFit: Boolean get() = zoom <= FIT_ZOOM_TOLERANCE
}

const val MIN_PHOTO_ZOOM = 1f
const val MAX_PHOTO_ZOOM = 6f

/**
 * The crop mode's minimum: the photo can zoom out beyond the fit.
 *
 * Seeing less than the fit makes no sense for viewing — it would be black canvas around for no
 * reason — but for cropping it is the tool itself. Zooming out is what puts a margin around the
 * photo, and that margin gives the frame room to grow and the finger somewhere to grab a handle
 * that, fitted, would be stuck to the edge of the screen.
 */
const val MIN_CROP_ZOOM = 0.4f

/** The level a double tap zooms to when the photo is fitted to the screen. */
const val DOUBLE_TAP_PHOTO_ZOOM = 2.5f

/**
 * Below this zoom the photo is treated as fitted: the pager accepts the horizontal swipe again and
 * the pan is reset. It is a rounding tolerance, not a gesture threshold — it must never be used to
 * decide whether a pinch *frame* counts.
 */
const val FIT_ZOOM_TOLERANCE = 1.01f

/** How far the pinch can go past the limits during the gesture, before springing back on release. */
private const val ELASTIC_OVERSHOOT = 0.15f

/** The photo's dimensions after `fit`, with the rotation already applied. */
fun photoFitSize(
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int = 0,
): Pair<Int, Int> {
    val safeSurfaceWidth = surfaceWidth.coerceAtLeast(1)
    val safeSurfaceHeight = surfaceHeight.coerceAtLeast(1)
    val swaps = ((quarterTurns % 4) + 4) % 4 % 2 == 1
    val safeImageWidth = (if (swaps) imageHeight else imageWidth).coerceAtLeast(1)
    val safeImageHeight = (if (swaps) imageWidth else imageHeight).coerceAtLeast(1)
    val surfaceRatio = safeSurfaceWidth.toFloat() / safeSurfaceHeight
    val imageRatio = safeImageWidth.toFloat() / safeImageHeight
    return if (imageRatio > surfaceRatio) {
        safeSurfaceWidth to (safeSurfaceWidth / imageRatio).toInt().coerceAtLeast(1)
    } else {
        (safeSurfaceHeight * imageRatio).toInt().coerceAtLeast(1) to safeSurfaceHeight
    }
}

/**
 * The size the photo is drawn at, already truncated to whole pixels.
 *
 * The truncation lives here and not in each caller because it — and not the formula — decides
 * whether the crop frame sits on the photo or one pixel beside it. `toInt()` truncates towards zero
 * instead of rounding, and two copies of that decision drift apart on the first odd case.
 */
internal fun photoScaledSize(
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    zoom: Float,
    quarterTurns: Int = 0,
    minZoom: Float = MIN_PHOTO_ZOOM,
): Pair<Int, Int> {
    val (fitWidth, fitHeight) = photoFitSize(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        quarterTurns = quarterTurns,
    )
    val safeZoom = zoom.coerceAtLeast(minZoom)
    return (fitWidth * safeZoom).toInt().coerceAtLeast(1) to
        (fitHeight * safeZoom).toInt().coerceAtLeast(1)
}

/**
 * Where the photo is drawn within the surface, in pixels and with Y growing downwards as in Compose.
 *
 * It is the same calculation [calculatePhotoViewport] does for GL, without flipping the axis.
 * Existing in two forms — and not in two implementations — is what lets the crop frame sit exactly
 * on the photo: whoever draws the frame and whoever draws the photo read from here.
 *
 * **Straightening and perspective do not enter this calculation.** They live in the shader's
 * `uImageFromView`, which resamples the photo inside the same rectangle — enlarging it until the
 * corners stop being empty, when the crop is constrained; the drawn rectangle is the same with and
 * without them. It is counter-intuitive and someone will come along wanting to fix it.
 */
data class PhotoBounds(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

fun photoBounds(
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    transform: PhotoTransform,
    quarterTurns: Int = 0,
    minZoom: Float = MIN_PHOTO_ZOOM,
): PhotoBounds {
    val safeSurfaceWidth = surfaceWidth.coerceAtLeast(1)
    val safeSurfaceHeight = surfaceHeight.coerceAtLeast(1)
    val safeZoom = transform.zoom.coerceIn(minZoom, MAX_PHOTO_ZOOM)
    val (width, height) = photoScaledSize(
        surfaceWidth = safeSurfaceWidth,
        surfaceHeight = safeSurfaceHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        zoom = safeZoom,
        quarterTurns = quarterTurns,
        minZoom = minZoom,
    )
    val (maxPanX, maxPanY) = maxPhotoPan(
        surfaceWidth = safeSurfaceWidth,
        surfaceHeight = safeSurfaceHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        zoom = safeZoom,
        quarterTurns = quarterTurns,
        minZoom = minZoom,
    )
    return PhotoBounds(
        left = (safeSurfaceWidth - width) / 2f + transform.panX.coerceIn(-maxPanX, maxPanX),
        top = (safeSurfaceHeight - height) / 2f + transform.panY.coerceIn(-maxPanY, maxPanY),
        width = width.toFloat(),
        height = height.toFloat(),
    )
}

/**
 * The maximum offset on each axis, that is, how much of the zoomed photo spills outside the surface.
 * For a landscape inside a phone screen, the vertical axis only opens once the zoomed height exceeds
 * the screen's — before that the limit is zero and dragging moves nothing, which is exactly what the
 * renderer does.
 */
fun maxPhotoPan(
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    zoom: Float,
    quarterTurns: Int = 0,
    minZoom: Float = MIN_PHOTO_ZOOM,
): Pair<Float, Float> {
    // Below the fit the surface stops being the limit: the photo fits inside it whole and the crop
    // frame, which this module does not know, takes over. Returning infinity — and not zero — is what
    // stops the renderer from cancelling an offset the gesture already validated; whoever asked for a
    // lower minimum knows where the frame is and knows how to clamp it.
    if (minZoom < MIN_PHOTO_ZOOM) return Float.POSITIVE_INFINITY to Float.POSITIVE_INFINITY
    val (scaledWidth, scaledHeight) = photoScaledSize(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        zoom = zoom,
        quarterTurns = quarterTurns,
        minZoom = minZoom,
    )
    return ((scaledWidth - surfaceWidth.coerceAtLeast(1)) / 2f).coerceAtLeast(0f) to
        ((scaledHeight - surfaceHeight.coerceAtLeast(1)) / 2f).coerceAtLeast(0f)
}

/**
 * Applies a pinch frame.
 *
 * `zoomChange` and `panChange` are what `PointerEvent.calculateZoom()`/`calculatePan()` return: the
 * change in **that frame**, not the accumulated one. A normal pinch produces ratios of the order of
 * 1.005 per frame, so the zoom always has to multiply — any threshold applied to the accumulated
 * value on each frame keeps the gesture from leaving 1×.
 *
 * `centroid` is the midpoint of the fingers, in surface coordinates, and stays fixed: it is what
 * gives the gesture the feel of grabbing the photo instead of enlarging it from the middle.
 */
fun PhotoTransform.pinch(
    zoomChange: Float,
    panChangeX: Float,
    panChangeY: Float,
    centroidX: Float,
    centroidY: Float,
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int = 0,
    elastic: Boolean = true,
    minZoom: Float = MIN_PHOTO_ZOOM,
): PhotoTransform {
    val limit = if (elastic) MAX_PHOTO_ZOOM * (1f + ELASTIC_OVERSHOOT) else MAX_PHOTO_ZOOM
    // The elasticity is only at the top. At the bottom the minimum is already the caller's choice, and
    // letting it go past there would give a photo shrinking beyond what the mode accepts to show.
    val nextZoom = (zoom * zoomChange).coerceIn(minZoom, limit)
    val ratio = nextZoom / zoom.coerceAtLeast(minZoom)
    val centreX = surfaceWidth / 2f
    val centreY = surfaceHeight / 2f
    return PhotoTransform(
        zoom = nextZoom,
        panX = panX + panChangeX + (centroidX - centreX) * (1f - ratio),
        panY = panY + panChangeY + (centroidY - centreY) * (1f - ratio),
    ).clampPan(surfaceWidth, surfaceHeight, imageWidth, imageHeight, quarterTurns, minZoom)
}

/** One-finger drag while the photo is zoomed in. */
fun PhotoTransform.drag(
    deltaX: Float,
    deltaY: Float,
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int = 0,
    minZoom: Float = MIN_PHOTO_ZOOM,
): PhotoTransform = copy(panX = panX + deltaX, panY = panY + deltaY)
    .clampPan(surfaceWidth, surfaceHeight, imageWidth, imageHeight, quarterTurns, minZoom)

/**
 * The state the gesture settles in when the user lets go: resolves the elasticity and, if it ended
 * practically fitted, actually fits instead of leaving a residual zoom that would block the swipe
 * between photos.
 */
fun PhotoTransform.settle(
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int = 0,
    minZoom: Float = MIN_PHOTO_ZOOM,
): PhotoTransform {
    val settled = zoom.coerceIn(minZoom, MAX_PHOTO_ZOOM)
    // Resetting fully only makes sense when the fit is the minimum: there a residual zoom is gesture
    // debris and would block the swipe. In a mode that accepts seeing less than the fit, zooming out
    // is the requested state and not a residue — dragging it back would undo the framing choice.
    if (minZoom >= MIN_PHOTO_ZOOM && settled <= FIT_ZOOM_TOLERANCE) return PhotoTransform()
    return copy(zoom = settled)
        .clampPan(surfaceWidth, surfaceHeight, imageWidth, imageHeight, quarterTurns, minZoom)
}

/**
 * Duplo toque: alterna entre encaixado e ampliado, mantendo debaixo do dedo o ponto tocado.
 */
fun PhotoTransform.toggleDoubleTap(
    tapX: Float,
    tapY: Float,
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int = 0,
): PhotoTransform {
    if (!isFit) return PhotoTransform()
    val ratio = DOUBLE_TAP_PHOTO_ZOOM / zoom.coerceAtLeast(MIN_PHOTO_ZOOM)
    return PhotoTransform(
        zoom = DOUBLE_TAP_PHOTO_ZOOM,
        panX = panX + (tapX - surfaceWidth / 2f) * (1f - ratio),
        panY = panY + (tapY - surfaceHeight / 2f) * (1f - ratio),
    ).clampPan(surfaceWidth, surfaceHeight, imageWidth, imageHeight, quarterTurns)
}

private fun PhotoTransform.clampPan(
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int,
    minZoom: Float = MIN_PHOTO_ZOOM,
): PhotoTransform {
    val (maxX, maxY) = maxPhotoPan(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        zoom = zoom,
        quarterTurns = quarterTurns,
        minZoom = minZoom,
    )
    val clampedX = panX.coerceIn(-maxX, maxX)
    val clampedY = panY.coerceIn(-maxY, maxY)
    return if (abs(clampedX - panX) < 0.001f && abs(clampedY - panY) < 0.001f) {
        this
    } else {
        copy(panX = clampedX, panY = clampedY)
    }
}
