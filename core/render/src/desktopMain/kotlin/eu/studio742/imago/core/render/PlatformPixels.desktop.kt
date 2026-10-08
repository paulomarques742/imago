package eu.studio742.imago.core.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.request.ImageRequest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

actual val coil3.Bitmap.pixelWidth: Int get() = width
actual val coil3.Bitmap.pixelHeight: Int get() = height

actual fun PixelBuffer.toImageBitmap(): ImageBitmap = toSkiaBitmap().asComposeImageBitmap()

actual fun coil3.Bitmap.asComposeImage(): ImageBitmap = asComposeImageBitmap()

/** Skia applies the EXIF orientation when decoding. */
actual fun decodePixels(bytes: ByteArray): PixelBuffer? = runCatching {
    Image.makeFromEncoded(bytes).use { image -> Bitmap.makeFromImage(image).toPixelBuffer() }
}.getOrNull()

actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = this
