package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.ImageSize
import eu.studio742.imago.core.model.ImmichEdit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The recipe's geometry as Immich's own edits, for a photo the server sees with [imageSize].
 *
 * It starts from [toRenderParameters] and [FrameGeometry], not from the recipe's fields, so that what
 * the server shows is what the stage renders — `processVersion` gates included.
 *
 * Immich applies the crop first, in pixels of the image already turned by its EXIF orientation, and
 * only then the rotation and the mirrors. The recipe's crop lives in the opposite place, on the frame
 * already turned, so it is carried back to the image through [FrameGeometry.imageFromFramed]. With a
 * straighten the region is a tilted rectangle Immich cannot express; what goes is the largest
 * upright rectangle with the same proportions and centre that fits inside it, so the server's
 * thumbnail never shows what the crop left out.
 *
 * With the perspective tilting or stretching the photo there is nothing Immich can apply, and the
 * list is empty — which removes whatever edits the server held.
 */
fun EditRecipe.immichEdits(imageSize: ImageSize): List<ImmichEdit> {
    val parameters = toRenderParameters()
    // A tilted camera or a stretched photo is no crop: what reaches Immich is the export, stacked on
    // the original, and the original keeps no edits that would frame it in a third way.
    val perspective = parameters.perspective
    val projective = perspective.vertical != 0f || perspective.horizontal != 0f || perspective.aspect != 0f ||
        perspective.uprightRoll != 0f || perspective.uprightPitch != 0f || perspective.uprightYaw != 0f
    if (projective) return emptyList()
    val geometry = parameters.frameGeometry(imageSize.width, imageSize.height)
    val quarterTurns = parameters.normalizedQuarterTurns()

    return buildList {
        immichCrop(geometry, imageSize, parameters.straighten)?.let(::add)
        if (quarterTurns != 0) add(ImmichEdit.Rotate(quarterTurns * 90))
        if (parameters.mirrorH) add(ImmichEdit.Mirror(ImmichEdit.MirrorAxis.VERTICAL))
        if (parameters.mirrorV) add(ImmichEdit.Mirror(ImmichEdit.MirrorAxis.HORIZONTAL))
    }
}

private fun immichCrop(geometry: FrameGeometry, imageSize: ImageSize, straighten: Float): ImmichEdit.Crop? {
    val width = imageSize.width.toFloat()
    val height = imageSize.height.toFloat()
    val point = FloatArray(2)
    fun corner(u: Float, v: Float): Pair<Float, Float> {
        geometry.imageFromFramed(u, v, point)
        return point[0] * width to point[1] * height
    }
    val origin = corner(0f, 0f)
    val acrossTop = corner(1f, 0f)
    val downSide = corner(0f, 1f)
    val opposite = corner(1f, 1f)

    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
    if (straighten == 0f) {
        // Quarter turns and mirrors keep the rectangle upright: its corners land on whole pixels,
        // give or take the float error that rounding absorbs.
        val xs = listOf(origin.first, acrossTop.first, downSide.first, opposite.first)
        val ys = listOf(origin.second, acrossTop.second, downSide.second, opposite.second)
        left = xs.min().roundToInt()
        top = ys.min().roundToInt()
        right = xs.max().roundToInt()
        bottom = ys.max().roundToInt()
    } else {
        val centerX = (origin.first + opposite.first) / 2f
        val centerY = (origin.second + opposite.second) / 2f
        val topLength = hypot(acrossTop.first - origin.first, acrossTop.second - origin.second)
        val sideLength = hypot(downSide.first - origin.first, downSide.second - origin.second)
        // After an odd number of quarter turns the frame's top edge runs down the image.
        val topRunsAcross = abs(acrossTop.first - origin.first) >= abs(acrossTop.second - origin.second)
        val halfAcross = (if (topRunsAcross) topLength else sideLength) / 2f
        val halfDown = (if (topRunsAcross) sideLength else topLength) / 2f
        val radians = Math.toRadians(abs(straighten).toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val scale = minOf(
            halfAcross / (halfAcross * cosine + halfDown * sine),
            halfDown / (halfAcross * sine + halfDown * cosine),
        )
        // Rounded inwards: a pixel outside the tilted region would be one the crop had left out.
        left = ceil(centerX - halfAcross * scale).toInt()
        top = ceil(centerY - halfDown * scale).toInt()
        right = floor(centerX + halfAcross * scale).toInt()
        bottom = floor(centerY + halfDown * scale).toInt()
    }

    val x = left.coerceIn(0, imageSize.width - 1)
    val y = top.coerceIn(0, imageSize.height - 1)
    val cropWidth = (right.coerceAtMost(imageSize.width) - x).coerceAtLeast(1)
    val cropHeight = (bottom.coerceAtMost(imageSize.height) - y).coerceAtLeast(1)
    if (x == 0 && y == 0 && cropWidth == imageSize.width && cropHeight == imageSize.height) return null
    return ImmichEdit.Crop(x, y, cropWidth, cropHeight)
}
