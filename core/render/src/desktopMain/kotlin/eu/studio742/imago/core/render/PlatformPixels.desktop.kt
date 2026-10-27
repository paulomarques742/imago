package eu.studio742.imago.core.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.request.ImageRequest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File

actual val coil3.Bitmap.pixelWidth: Int get() = width
actual val coil3.Bitmap.pixelHeight: Int get() = height

actual fun PixelBuffer.toImageBitmap(): ImageBitmap = toSkiaBitmap().asComposeImageBitmap()
actual fun PixelBuffer.toPlatformBitmap(): coil3.Bitmap = toSkiaBitmap()
actual fun createPhotoTempFile(context: coil3.PlatformContext): File = File.createTempFile("imago-original-", ".asset")

actual fun coil3.Bitmap.asComposeImage(): ImageBitmap = asComposeImageBitmap()

/** Skia applies the EXIF orientation when decoding. */
actual fun decodePixels(bytes: ByteArray): PixelBuffer? = runCatching {
    Image.makeFromEncoded(bytes).use { image -> Bitmap.makeFromImage(image).toPixelBuffer() }
}.getOrNull()

/** ImageIO subsamples a JPEG while reading it; anything else goes through Skia whole. */
actual fun decodePixelsReduced(bytes: ByteArray, minSide: Int): PixelBuffer? = runCatching {
    javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
        val reader = javax.imageio.ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
        try {
            reader.input = input
            val longSide = maxOf(reader.getWidth(0), reader.getHeight(0))
            var sample = 1
            while (longSide / (sample * 2) >= minSide) sample *= 2
            val image = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
            PixelBuffer(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        } finally {
            reader.dispose()
        }
    }
}.getOrNull() ?: decodePixels(bytes)

actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = this
