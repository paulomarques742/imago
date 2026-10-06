package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware

actual val coil3.Bitmap.pixelWidth: Int get() = width
actual val coil3.Bitmap.pixelHeight: Int get() = height

actual fun coil3.Bitmap.toPixelBuffer(): PixelBuffer {
    val readable = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val buffer = PixelBuffer(readable.width, readable.height)
    readable.getPixels(buffer.pixels, 0, readable.width, 0, 0, readable.width, readable.height)
    return buffer
}

actual fun PixelBuffer.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

actual fun decodePixels(bytes: ByteArray): PixelBuffer? = runCatching {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        ?.toPixelBuffer()
}.getOrNull()

actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = allowHardware(false)
