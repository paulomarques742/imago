package eu.studio742.imago.core.render

import android.graphics.Bitmap

/** A `Bitmap` as seen by the shared CPU processor. The signatures are its own. */
class BitmapSurface(val bitmap: Bitmap) : PixelSurface {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun getPixels(target: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) =
        bitmap.getPixels(target, offset, stride, x, y, w, h)

    override fun setPixels(source: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) =
        bitmap.setPixels(source, offset, stride, x, y, w, h)
}

/** The recipe applied to a mutable ARGB_8888 copy of [source]. */
fun BitmapPhotoProcessor.render(
    source: Bitmap,
    parameters: RenderParameters,
    geometry: FrameGeometry? = null,
): Bitmap = renderInPlace(source.copy(Bitmap.Config.ARGB_8888, true), parameters, geometry)

/**
 * @param geometry the bridge between this bitmap and the original image, needed because the masks
 *   live in image coordinates and this bitmap already comes cropped and rotated. The caller has to
 *   build it **before** applying the geometry, which is when the original dimensions still exist.
 */
fun BitmapPhotoProcessor.renderInPlace(
    bitmap: Bitmap,
    parameters: RenderParameters,
    geometry: FrameGeometry? = null,
): Bitmap {
    require(bitmap.isMutable) { "The export image has to be mutable." }
    renderInPlace(BitmapSurface(bitmap), parameters, geometry)
    return bitmap
}
