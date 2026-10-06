package eu.studio742.imago.core.render

fun RenderParameters.normalizedQuarterTurns(): Int = ((rotation % 360) + 360) % 360 / 90

internal data class PhotoViewport(
    val left: Int,
    val bottom: Int,
    val width: Int,
    val height: Int,
)

/**
 * The drawing viewport, sharing the `fit` and the pan limits with the gesture handler
 * ([photoFitSize], [maxPhotoPan]). The dimensions arrive here already swapped when there is rotation,
 * so `quarterTurns` stays at zero.
 */
internal fun calculatePhotoViewport(
    surfaceWidth: Int,
    surfaceHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    minZoom: Float = MIN_PHOTO_ZOOM,
): PhotoViewport {
    val safeSurfaceWidth = surfaceWidth.coerceAtLeast(1)
    val safeSurfaceHeight = surfaceHeight.coerceAtLeast(1)
    val safeZoom = zoom.coerceIn(minZoom, MAX_PHOTO_ZOOM)
    // The size comes from [photoScaledSize] and not from a local multiplication: it is the truncation
    // that has to be the same the crop frame uses, not the formula.
    val (scaledWidth, scaledHeight) = photoScaledSize(
        surfaceWidth = safeSurfaceWidth,
        surfaceHeight = safeSurfaceHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        zoom = safeZoom,
        minZoom = minZoom,
    )
    val (maxPanX, maxPanY) = maxPhotoPan(
        surfaceWidth = safeSurfaceWidth,
        surfaceHeight = safeSurfaceHeight,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        zoom = safeZoom,
        minZoom = minZoom,
    )
    return PhotoViewport(
        left = ((safeSurfaceWidth - scaledWidth) / 2f + panX.coerceIn(-maxPanX, maxPanX)).toInt(),
        bottom = ((safeSurfaceHeight - scaledHeight) / 2f - panY.coerceIn(-maxPanY, maxPanY)).toInt(),
        width = scaledWidth,
        height = scaledHeight,
    )
}
