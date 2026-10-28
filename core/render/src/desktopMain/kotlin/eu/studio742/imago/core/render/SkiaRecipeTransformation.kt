package eu.studio742.imago.core.render

import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import eu.studio742.imago.core.model.EditRecipe

/**
 * The recipe applied to the Skia Bitmap Coil delivers on desktop.
 *
 * The same CPU engine as the preview and the export: the thumbnail shows what the export produces.
 * The key includes the instant of the last save, as on Android, and the result goes to
 * [RecipeRenderCache] in the same way.
 */
class SkiaRecipeTransformation(private val recipe: EditRecipe) : Transformation() {
    override val cacheKey: String = "recipe:${recipe.assetId}:${recipe.processVersion}:${recipe.updatedAt}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        if (DevelopedRawBitmaps.contains(input, cacheKey)) return@withContext input
        val key = RecipeRenderCache.key(cacheKey, input.width, input.height, input::getColor)
        RecipeRenderCache.read(key)?.let(::decodeCachedRender)?.let { return@withContext it }
        RecipePixels.render(input.toPixelBuffer(), recipe).toSkiaBitmap().also { rendered ->
            RecipeRenderCache.write(key) {
                val image = Image.makeFromBitmap(rendered)
                // JPEG only where this Skia was built without the WebP encoder.
                (image.encodeToData(EncodedImageFormat.WEBP, RENDER_CACHE_QUALITY)
                    ?: image.encodeToData(EncodedImageFormat.JPEG, RENDER_CACHE_QUALITY))?.bytes
            }
        }
    }
}

/** A render read back from [RecipeRenderCache]; null when the bytes do not decode. */
private fun decodeCachedRender(bytes: ByteArray): Bitmap? = runCatching {
    Bitmap.makeFromImage(Image.makeFromEncoded(bytes)).apply { setImmutable() }
}.getOrNull()

private const val RENDER_CACHE_QUALITY = 90

internal actual fun decodeRenderCacheBytes(bytes: ByteArray): Bitmap? = decodeCachedRender(bytes)

internal actual fun Bitmap.encodeForRenderCache(): ByteArray? {
    val image = Image.makeFromBitmap(this)
    // JPEG only where this Skia was built without the WebP encoder.
    return (image.encodeToData(EncodedImageFormat.WEBP, RENDER_CACHE_QUALITY)
        ?: image.encodeToData(EncodedImageFormat.JPEG, RENDER_CACHE_QUALITY))?.bytes
}

actual fun recipeTransformation(recipe: EditRecipe): Transformation = SkiaRecipeTransformation(recipe)

private val Bitmap.unpremultipliedInfo get() = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)

/** A Skia Bitmap's pixels as ARGB, without premultiplication — the processor's form. */
actual fun coil3.Bitmap.toPixelBuffer(): PixelBuffer {
    val bgra = checkNotNull(readPixels(unpremultipliedInfo, width * 4, 0, 0)) { "Could not read the image." }
    return PixelBuffer(width, height, IntArray(width * height) { i ->
        val o = i * 4
        ((bgra[o + 3].toInt() and 255) shl 24) or ((bgra[o + 2].toInt() and 255) shl 16) or
            ((bgra[o + 1].toInt() and 255) shl 8) or (bgra[o].toInt() and 255)
    })
}

fun PixelBuffer.toSkiaBitmap(): Bitmap {
    val bytes = ByteArray(width * height * 4)
    for (i in pixels.indices) {
        val p = pixels[i]; val o = i * 4
        bytes[o] = p.toByte(); bytes[o + 1] = (p ushr 8).toByte(); bytes[o + 2] = (p ushr 16).toByte(); bytes[o + 3] = (p ushr 24).toByte()
    }
    return Bitmap().apply {
        val info = ImageInfo(this@toSkiaBitmap.width, this@toSkiaBitmap.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        check(installPixels(info, bytes, this@toSkiaBitmap.width * 4)) { "Could not create the image." }
        setImmutable()
    }
}
