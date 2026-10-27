package eu.studio742.imago.core.render

import coil3.Extras
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.CURRENT_RAW_RENDERING
import eu.studio742.imago.core.model.RAW_RENDERING_PROFILED
import eu.studio742.imago.core.model.appliesBaselineExposure
import eu.studio742.imago.core.model.rawRendering
import eu.studio742.imago.core.raw.RawDecoder
import java.io.File
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val rawRecipeKey = Extras.Key<EditRecipe?>(null)

/** Small markers let Coil keep its usual transformation cache key without developing RAW twice. */
internal object DevelopedRawBitmaps {
    private val recipes = WeakHashMap<coil3.Bitmap, String>()
    @Synchronized fun mark(bitmap: coil3.Bitmap, key: String) { recipes[bitmap] = key }
    @Synchronized fun contains(bitmap: coil3.Bitmap, key: String): Boolean = recipes[bitmap] == key
}

/** Library thumbnails and detail images use the same development and recipe as the editor. */
class RawPhotoDecoder private constructor(private val file: File, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult? {
        val side = maxOf((options.size.width as? Dimension.Pixels)?.px ?: 0,
            (options.size.height as? Dimension.Pixels)?.px ?: 0).takeIf { it > 0 }?.coerceAtMost(2048) ?: 2048
        val recipe = options.extras[rawRecipeKey]
        val raw = decodeRawPreview(file, side, recipe?.appliesBaselineExposure() ?: true) ?: return null
        // A photo the editor has not developed yet is matched to its camera's JPEG here, as the editor
        // will when it opens it; a developed one keeps the exposure its recipe recorded.
        val matched = if (recipe?.rawDevelopment == null && (recipe?.rawRendering() ?: CURRENT_RAW_RENDERING) >= RAW_RENDERING_PROFILED) {
            CameraExposureMatch.of(file, raw)
        } else {
            0f
        }
        val bitmap = withContext(Dispatchers.Default) {
            if (recipe != null) requireRawEngine(recipe)
            val parameters = recipe?.toRenderParameters(rawExposure = recipe.rawDevelopment?.exposure ?: matched)
                ?: RenderParameters(rawProfile = RawProfile.COLOR, rawExposure = matched)
            val rendered = RecipePixels.renderFloat(raw, parameters, consumeSource = true)
            rendered.toPixelBuffer().toPlatformBitmap().also { image ->
                if (recipe != null) DevelopedRawBitmaps.mark(image, recipeTransformation(recipe).cacheKey)
            }
        }
        return DecodeResult(bitmap.asImage(), isSampled = true)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val peek = result.source.source().peek()
            if (!peek.request(12)) return null
            val head = peek.readByteArray(12)
            fun byte(i: Int) = head[i].toInt() and 255
            fun ascii(from: Int, until: Int) = head.copyOfRange(from, until).toString(Charsets.US_ASCII)
            // Skip known display encodings. Every other format is identified by LibRaw's contents.
            if ((byte(0) == 255 && byte(1) == 216) || (byte(0) == 137 && ascii(1, 4) == "PNG") ||
                ascii(0, 3) == "GIF" || ascii(0, 2) == "BM" || ascii(0, 4) == "RIFF" ||
                (ascii(4, 8) == "ftyp" && ascii(8, 11) != "crx")) return null
            val file = result.source.file().toFile()
            if (RawDecoder.identify(file) == null) return null
            return RawPhotoDecoder(file, options)
        }
    }
}
