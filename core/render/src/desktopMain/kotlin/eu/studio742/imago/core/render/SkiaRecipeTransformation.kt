package eu.studio742.imago.core.render

import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import eu.studio742.imago.core.model.EditRecipe

/**
 * The recipe applied to the Skia Bitmap Coil delivers on desktop.
 *
 * The same CPU engine as the preview and the export: the thumbnail shows what the export produces.
 * The key includes the instant of the last save, as on Android.
 */
class SkiaRecipeTransformation(private val recipe: EditRecipe) : Transformation() {
    override val cacheKey: String = "recipe:${recipe.assetId}:${recipe.processVersion}:${recipe.updatedAt}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) {
        RecipePixels.render(input.toPixelBuffer(), recipe).toSkiaBitmap()
    }
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
