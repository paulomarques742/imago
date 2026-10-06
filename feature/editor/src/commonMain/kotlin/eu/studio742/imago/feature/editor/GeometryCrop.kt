package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.render.MAX_PHOTO_ZOOM
import eu.studio742.imago.core.render.MIN_CROP_ZOOM
import eu.studio742.imago.core.render.PhotoBounds
import eu.studio742.imago.core.render.PhotoTransform
import eu.studio742.imago.core.render.photoBounds
import eu.studio742.imago.core.render.photoFitSize
import kotlin.math.abs

internal const val ORIGINAL_CROP_ASPECT = "original"
internal const val MIN_CROP_SIZE = 0.05f

internal data class CropAspect(
    val id: String,
    val ratio: Float,
)

/** The most used photographic, print and screen aspect ratios. */
internal val commonCropAspects = listOf(
    CropAspect("1:1", 1f),
    CropAspect("3:2", 3f / 2f),
    CropAspect("2:3", 2f / 3f),
    CropAspect("4:3", 4f / 3f),
    CropAspect("3:4", 3f / 4f),
    CropAspect("5:4", 5f / 4f),
    CropAspect("4:5", 4f / 5f),
    CropAspect("16:9", 16f / 9f),
    CropAspect("9:16", 9f / 16f),
)

internal fun cropAspectById(id: String): CropAspect? = commonCropAspects.firstOrNull { it.id == id }

internal fun reciprocalCropAspectId(id: String?): String? {
    if (id == null || id == ORIGINAL_CROP_ASPECT) return id
    val ratio = cropAspectById(id)?.ratio ?: return id
    return commonCropAspects.minByOrNull { abs(it.ratio - 1f / ratio) }
        ?.takeIf { abs(it.ratio - 1f / ratio) < 0.0001f }
        ?.id
        ?: id
}

internal fun CropRect.rotatedClockwise(): CropRect = CropRect(
    x = 1f - y - h,
    y = x,
    w = h,
    h = w,
)

internal fun orientedImageRatio(imageWidth: Int, imageHeight: Int, rotation: Int): Float {
    val quarterTurns = ((rotation % 360) + 360) % 360 / 90
    val orientedWidth = if (quarterTurns % 2 == 1) imageHeight else imageWidth
    val orientedHeight = if (quarterTurns % 2 == 1) imageWidth else imageHeight
    return orientedWidth.coerceAtLeast(1).toFloat() / orientedHeight.coerceAtLeast(1)
}

internal fun lockedCropRatio(
    aspectLock: String?,
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int,
): Float? = when (aspectLock) {
    null -> null
    ORIGINAL_CROP_ASPECT -> orientedImageRatio(imageWidth, imageHeight, rotation)
    else -> cropAspectById(aspectLock)?.ratio
}

/** The largest centred crop with the requested aspect ratio, in coordinates of the already rotated image. */
internal fun centeredCropRect(
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int,
    aspectRatio: Float,
): CropRect {
    require(aspectRatio > 0f)
    val sourceRatio = orientedImageRatio(imageWidth, imageHeight, rotation)
    return if (sourceRatio > aspectRatio) {
        val width = aspectRatio / sourceRatio
        CropRect(x = (1f - width) / 2f, w = width)
    } else {
        val height = sourceRatio / aspectRatio
        CropRect(y = (1f - height) / 2f, h = height)
    }
}

internal enum class CropHandle {
    MOVE,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
}

internal fun CropRect.dragged(
    handle: CropHandle,
    deltaX: Float,
    deltaY: Float,
    lockedPixelRatio: Float?,
    imagePixelRatio: Float,
): CropRect {
    val current = normalized()
    if (handle == CropHandle.MOVE) {
        return current.copy(
            x = (current.x + deltaX).coerceIn(0f, 1f - current.w),
            y = (current.y + deltaY).coerceIn(0f, 1f - current.h),
        )
    }
    val normalizedRatio = lockedPixelRatio?.div(imagePixelRatio)?.takeIf { it > 0f }
    return if (normalizedRatio == null) {
        current.draggedFree(handle, deltaX, deltaY)
    } else {
        current.draggedLocked(handle, deltaX, deltaY, normalizedRatio)
    }.normalized()
}

private fun CropRect.draggedFree(handle: CropHandle, dx: Float, dy: Float): CropRect {
    var left = x
    var top = y
    var right = x + w
    var bottom = y + h
    if (handle.movesLeft) left = (left + dx).coerceIn(0f, right - MIN_CROP_SIZE)
    if (handle.movesRight) right = (right + dx).coerceIn(left + MIN_CROP_SIZE, 1f)
    if (handle.movesTop) top = (top + dy).coerceIn(0f, bottom - MIN_CROP_SIZE)
    if (handle.movesBottom) bottom = (bottom + dy).coerceIn(top + MIN_CROP_SIZE, 1f)
    return CropRect(left, top, right - left, bottom - top)
}

private fun CropRect.draggedLocked(handle: CropHandle, dx: Float, dy: Float, ratio: Float): CropRect {
    val centreX = x + w / 2f
    val centreY = y + h / 2f
    if (handle in listOf(CropHandle.LEFT, CropHandle.RIGHT)) {
        val anchor = if (handle == CropHandle.LEFT) x + w else x
        val moving = if (handle == CropHandle.LEFT) x + dx else x + w + dx
        return lockedAroundHorizontalAnchor(anchor, moving, centreY, handle == CropHandle.LEFT, ratio)
    }
    if (handle in listOf(CropHandle.TOP, CropHandle.BOTTOM)) {
        val anchor = if (handle == CropHandle.TOP) y + h else y
        val moving = if (handle == CropHandle.TOP) y + dy else y + h + dy
        return lockedAroundVerticalAnchor(anchor, moving, centreX, handle == CropHandle.TOP, ratio)
    }

    val anchorX = if (handle.movesLeft) x + w else x
    val anchorY = if (handle.movesTop) y + h else y
    val movingX = (if (handle.movesLeft) x else x + w) + dx
    val movingY = (if (handle.movesTop) y else y + h) + dy
    val widthFromPointer = abs(movingX - anchorX)
    val heightFromPointer = abs(movingY - anchorY)
    var width = if (abs(dx) >= abs(dy * ratio)) widthFromPointer else heightFromPointer * ratio
    var height = width / ratio
    val minimumWidth = maxOf(MIN_CROP_SIZE, MIN_CROP_SIZE * ratio)
    val maxWidth = if (handle.movesLeft) anchorX else 1f - anchorX
    val maxHeight = if (handle.movesTop) anchorY else 1f - anchorY
    val scale = minOf(1f, maxWidth / width.coerceAtLeast(minimumWidth), maxHeight / height.coerceAtLeast(MIN_CROP_SIZE))
    width = (width * scale).coerceAtLeast(minimumWidth)
    height = width / ratio
    if (height > maxHeight) {
        height = maxHeight
        width = height * ratio
    }
    if (width > maxWidth) {
        width = maxWidth
        height = width / ratio
    }
    return CropRect(
        x = if (handle.movesLeft) anchorX - width else anchorX,
        y = if (handle.movesTop) anchorY - height else anchorY,
        w = width,
        h = height,
    )
}

private fun lockedAroundHorizontalAnchor(
    anchorX: Float,
    movingX: Float,
    centreY: Float,
    movesLeft: Boolean,
    ratio: Float,
): CropRect {
    val minimumWidth = maxOf(MIN_CROP_SIZE, MIN_CROP_SIZE * ratio)
    var width = abs(movingX - anchorX).coerceAtLeast(minimumWidth)
    var height = width / ratio
    val maxHeight = 2f * minOf(centreY, 1f - centreY)
    if (height > maxHeight) {
        height = maxHeight
        width = height * ratio
    }
    width = width.coerceAtMost(if (movesLeft) anchorX else 1f - anchorX)
    height = width / ratio
    return CropRect(
        x = if (movesLeft) anchorX - width else anchorX,
        y = centreY - height / 2f,
        w = width,
        h = height,
    )
}

private fun lockedAroundVerticalAnchor(
    anchorY: Float,
    movingY: Float,
    centreX: Float,
    movesTop: Boolean,
    ratio: Float,
): CropRect {
    val minimumHeight = maxOf(MIN_CROP_SIZE, MIN_CROP_SIZE / ratio)
    var height = abs(movingY - anchorY).coerceAtLeast(minimumHeight)
    var width = height * ratio
    val maxWidth = 2f * minOf(centreX, 1f - centreX)
    if (width > maxWidth) {
        width = maxWidth
        height = width / ratio
    }
    height = height.coerceAtMost(if (movesTop) anchorY else 1f - anchorY)
    width = height * ratio
    return CropRect(
        x = centreX - width / 2f,
        y = if (movesTop) anchorY - height else anchorY,
        w = width,
        h = height,
    )
}

private val CropHandle.movesLeft: Boolean
    get() = this == CropHandle.LEFT || this == CropHandle.TOP_LEFT || this == CropHandle.BOTTOM_LEFT
private val CropHandle.movesRight: Boolean
    get() = this == CropHandle.RIGHT || this == CropHandle.TOP_RIGHT || this == CropHandle.BOTTOM_RIGHT
private val CropHandle.movesTop: Boolean
    get() = this == CropHandle.TOP || this == CropHandle.TOP_LEFT || this == CropHandle.TOP_RIGHT
private val CropHandle.movesBottom: Boolean
    get() = this == CropHandle.BOTTOM || this == CropHandle.BOTTOM_LEFT || this == CropHandle.BOTTOM_RIGHT

private fun CropRect.normalized(): CropRect {
    val left = x.coerceIn(0f, 1f - MIN_CROP_SIZE)
    val top = y.coerceIn(0f, 1f - MIN_CROP_SIZE)
    return CropRect(
        x = left,
        y = top,
        w = w.coerceIn(MIN_CROP_SIZE, 1f - left),
        h = h.coerceIn(MIN_CROP_SIZE, 1f - top),
    )
}

// --- the frame on screen -----------------------------------------------------------------------

/**
 * The crop frame in stage pixels.
 *
 * It exists in parallel with [CropRect] — which is normalised to the image — because the gesture needs
 * both readings at the same time: while the photo is dragged, it is the frame in pixels that stays
 * still and the normalised rectangle that is recalculated.
 */
internal data class CropFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal fun cropFrameOf(bounds: PhotoBounds, crop: CropRect): CropFrame = CropFrame(
    left = bounds.left + crop.x * bounds.width,
    top = bounds.top + crop.y * bounds.height,
    right = bounds.left + (crop.x + crop.w) * bounds.width,
    bottom = bounds.top + (crop.y + crop.h) * bounds.height,
)

/**
 * The inverse reading: a screen frame back in image coordinates.
 *
 * It is this function that makes the photo move under a still frame. The gesture does not touch the
 * crop directly — it freezes the frame in pixels, moves the photo, and asks here what crop that now
 * describes.
 */
internal fun cropRectOf(bounds: PhotoBounds, frame: CropFrame): CropRect {
    val width = bounds.width.coerceAtLeast(1f)
    val height = bounds.height.coerceAtLeast(1f)
    return CropRect(
        x = (frame.left - bounds.left) / width,
        y = (frame.top - bounds.top) / height,
        w = frame.width / width,
        h = frame.height / height,
    ).normalized()
}

/**
 * The offset and the zoom-out that keep the frame inside the photo.
 *
 * Below the fit the surface stops limiting the pan — [maxPhotoPan] returns infinity on purpose — and
 * this is the limit that takes its place. Note which of the two gives way: when zooming out further
 * would make the frame leave the photo, it is the **zoom** that stops, not the offset. That is the
 * bottom of the zoom-out gesture, and it is what keeps anyone from choosing a crop the photo does not
 * have to give.
 */
internal fun PhotoTransform.clampedToCropFrame(
    stageWidth: Int,
    stageHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int,
    frame: CropFrame,
    minZoom: Float = MIN_CROP_ZOOM,
): PhotoTransform {
    val (fitWidth, fitHeight) = photoFitSize(
        surfaceWidth = stageWidth,
        surfaceHeight = stageHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        quarterTurns = quarterTurns,
    )
    val cover = maxOf(
        frame.width / fitWidth.coerceAtLeast(1),
        frame.height / fitHeight.coerceAtLeast(1),
    )
    val settledZoom = zoom.coerceIn(maxOf(minZoom, cover), MAX_PHOTO_ZOOM)
    val bounds = photoBounds(
        surfaceWidth = stageWidth,
        surfaceHeight = stageHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        transform = PhotoTransform(zoom = settledZoom, panX = panX, panY = panY),
        quarterTurns = quarterTurns,
        minZoom = minZoom,
    )
    // With the zoom already covering the frame, at most one of the two sides of each axis is outside.
    val shiftX = maxOf(frame.right - bounds.right, 0f) + minOf(frame.left - bounds.left, 0f)
    val shiftY = maxOf(frame.bottom - bounds.bottom, 0f) + minOf(frame.top - bounds.top, 0f)
    return PhotoTransform(zoom = settledZoom, panX = panX + shiftX, panY = panY + shiftY)
}

// --- o enquadramento -----------------------------------------------------------------------

/**
 * How much of the stage the photo takes when the framing settles.
 *
 * What is left is margin: it serves so the corner handles do not stick to the screen's edge, where
 * the thumb does not reach them. It serves nothing else — the photo has to fit whole inside what is
 * left.
 */
internal const val CROP_FRAME_FILL = 0.92f

/**
 * The framing that puts the frame comfortably on the stage: centred, taking [fill] of the tightest
 * side.
 *
 * In closed form, without search or iteration — the zoom comes from the ratio between the target and
 * the size the frame has on the fitted photo, and the offset from working out where its centre has to
 * land. That is what guarantees applying it twice gives the same, and so that the view does not
 * oscillate.
 */
internal fun cropFramingTransform(
    stageWidth: Int,
    stageHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int,
    crop: CropRect,
    fill: Float = CROP_FRAME_FILL,
    minZoom: Float = MIN_CROP_ZOOM,
): PhotoTransform {
    val (fitWidth, fitHeight) = photoFitSize(
        surfaceWidth = stageWidth,
        surfaceHeight = stageHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        quarterTurns = quarterTurns,
    )
    val safeCrop = crop.normalized()
    val zoom = minOf(
        stageWidth * fill / (safeCrop.w * fitWidth.coerceAtLeast(1)),
        stageHeight * fill / (safeCrop.h * fitHeight.coerceAtLeast(1)),
    ).coerceIn(minZoom, MAX_PHOTO_ZOOM)
    return PhotoTransform(
        zoom = zoom,
        panX = fitWidth * zoom * (0.5f - (safeCrop.x + safeCrop.w / 2f)),
        panY = fitHeight * zoom * (0.5f - (safeCrop.y + safeCrop.h / 2f)),
    )
}

/**
 * The crop mode's view at rest: **the whole photo**, with a margin around it.
 *
 * Framing the frame — which is what used to be here — put off screen everything left out of the
 * crop. And that is precisely what needs to be seen: cropping is deciding what to drop, and one does
 * not decide what to drop without having it in front of one. The frame is always inside the photo,
 * so showing the whole photo shows it too.
 *
 * It is still [cropFramingTransform] underneath, with the whole rectangle: it is the same calculation,
 * and the special case stays visible instead of hidden in a second formula.
 */
internal fun cropRestingTransform(
    stageWidth: Int,
    stageHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    quarterTurns: Int,
    fill: Float = CROP_FRAME_FILL,
): PhotoTransform = cropFramingTransform(
    stageWidth = stageWidth,
    stageHeight = stageHeight,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    quarterTurns = quarterTurns,
    crop = CropRect(),
    fill = fill,
)

/**
 * True when reframing is worth it.
 *
 * Without this dead zone, every two-pixel touch-up of the frame shook the whole view, and framing
 * stopped looking like a decision of whoever edits and started looking like a tic.
 */
internal fun PhotoTransform.needsReframing(
    target: PhotoTransform,
    zoomTolerance: Float = 0.06f,
    panTolerance: Float = 24f,
): Boolean = abs(target.zoom - zoom) > zoomTolerance * zoom.coerceAtLeast(0.0001f) ||
    abs(target.panX - panX) > panTolerance ||
    abs(target.panY - panY) > panTolerance
