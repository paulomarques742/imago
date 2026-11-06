package eu.studio742.imago.core.composition

import kotlin.math.*

data class VideoTextureSampling(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float)

/** GL textures count y from the bottom; PlacementCrop and the stage count it from the top. */
fun videoTextureSampling(sourceWidth: Float, sourceHeight: Float, width: Float, height: Float, crop: PlacementCrop): VideoTextureSampling {
    val image = placementImageRect(sourceWidth, sourceHeight, width, height, crop)
    val scaleY = height / image.height
    return VideoTextureSampling(width / image.width, scaleY, -image.left / image.width, 1f - scaleY + image.top / image.height)
}

fun FrameStyle.hasVideoDecoration(): Boolean = shadowRadius > 0f || borderWidth > 0f && (borderArgb ushr 24 and 255) > 0 ||
    decoration.kind != MediaFrameKind.NONE && decoration.width > 0f && decoration.opacity > 0f && (decoration.colorArgb ushr 24 and 255) > 0

/**
 * Flattening paper behind a translucent clip must apply the element opacity once.
 * With video coverage A and opacity O, the underlay needs O(1-A)/(1-OA), not a second O.
 */
fun videoDecorationAlphaFactor(maskCoverage: Float, elementOpacity: Float): Float {
    val opacity = elementOpacity.coerceIn(0f, 1f)
    if (opacity >= 1f) return 1f
    val coverage = maskCoverage.coerceIn(0f, 1f)
    return (opacity * (1f - coverage) / (1f - opacity * coverage)).coerceIn(0f, 1f)
}

data class VideoFrameProjection(val left: Int, val top: Int, val width: Int, val height: Int,
    val centreX: Float, val centreY: Float, val frameWidth: Float, val frameHeight: Float, val visible: Boolean = true)

/** The shader renders only the visible page rectangle; Media3 never has to anchor outside its range. */
fun CompositionElement.Video.projectedFrame(pageIndex: Int, pageWidth: Int, pageHeight: Int): VideoFrameProjection {
    val coverage = visualBounds(aspect = pageWidth.toFloat() / pageHeight)
    val x1 = (coverage.x - pageIndex) * pageWidth; val x2 = (coverage.right - pageIndex) * pageWidth
    val y1 = coverage.y * pageHeight; val y2 = coverage.bottom * pageHeight
    if (x2 <= 0f || x1 >= pageWidth || y2 <= 0f || y1 >= pageHeight)
        return VideoFrameProjection(0, 0, 2, 2, 0f, 0f, 1f, 1f, visible = false)
    val left = floor(x1.coerceAtLeast(0f)).toInt().coerceIn(0, pageWidth - 2)
    val top = floor(y1.coerceAtLeast(0f)).toInt().coerceIn(0, pageHeight - 2)
    val right = ceil(x2.coerceAtMost(pageWidth.toFloat())).toInt().coerceIn(left + 2, pageWidth)
    val bottom = ceil(y2.coerceAtMost(pageHeight.toFloat())).toInt().coerceIn(top + 2, pageHeight)
    val b = transform.bounds
    return VideoFrameProjection(left, top, right - left, bottom - top,
        (b.x - pageIndex + b.width / 2f) * pageWidth, (b.y + b.height / 2f) * pageHeight, b.width * pageWidth, b.height * pageHeight)
}
