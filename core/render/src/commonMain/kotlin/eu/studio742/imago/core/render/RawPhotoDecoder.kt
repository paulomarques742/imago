package eu.studio742.imago.core.render

import coil3.Extras
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.CURRENT_RAW_RENDERING
import eu.studio742.imago.core.model.RAW_INITIAL_SHARPEN_AMOUNT
import eu.studio742.imago.core.model.rawHighlightMode
import eu.studio742.imago.core.model.RAW_RENDERING_PROFILED
import eu.studio742.imago.core.model.appliesBaselineExposure
import eu.studio742.imago.core.model.rawRendering
import eu.studio742.imago.core.raw.RawDecoder
import java.io.File
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val rawRecipeKey = Extras.Key<EditRecipe?>(null)

/**
 * The develop of a RAW with no recipe, for [RecipeRenderCache]. Raised whenever what that develop does
 * changes — the engine's highlight mode, the profile, the first sharpening — so that old entries miss.
 */
const val RAW_DEFAULT_DEVELOPMENT_KEY = "default3"

/** A render read back from [RecipeRenderCache]; null when the bytes do not decode. */
internal expect fun decodeRenderCacheBytes(bytes: ByteArray): coil3.Bitmap?

/** The render as the cache keeps it: lossy WebP, since it only goes back on screen at the size it was made. */
internal expect fun coil3.Bitmap.encodeForRenderCache(): ByteArray?

/** Small markers let Coil keep its usual transformation cache key without developing RAW twice. */
internal object DevelopedRawBitmaps {
    private val recipes = WeakHashMap<coil3.Bitmap, String>()
    @Synchronized fun mark(bitmap: coil3.Bitmap, key: String) { recipes[bitmap] = key }
    @Synchronized fun contains(bitmap: coil3.Bitmap, key: String): Boolean = recipes[bitmap] == key
}

/** Library thumbnails and detail images use the same development and recipe as the editor. */
class RawPhotoDecoder private constructor(private val file: File, private val options: Options) : Decoder {
    override suspend fun decode(): DecodeResult? {
        val library = options.extras[LibraryRawOriginal]
        val side = if (library != null) libraryRawSide(options) else rawTileSide(options)
        val recipe = options.extras[rawRecipeKey]
        // What the develop makes is kept on disk, as for any other edited photo; a photo read through a
        // grant that can be revoked has its disk cache switched off by the request, and keeps nothing.
        val transformationKey = recipe?.let { recipeTransformation(it).cacheKey } ?: RAW_DEFAULT_DEVELOPMENT_KEY
        // A tile from the phone arrives with its key already made from MediaStore, and one from a server
        // library is named by its id and checksum: both were looked up before this file was copied or
        // downloaded. The others are named by their own bytes.
        val namedByThePhone = options.extras[RawTileCacheKey] ?: libraryRawKey(options)
        val cacheKey = namedByThePhone ?: if (options.diskCachePolicy.readEnabled || options.diskCachePolicy.writeEnabled) {
            withContext(Dispatchers.IO) { runCatching { RecipeRenderCache.rawKey(transformationKey, file, side) }.getOrNull() }
        } else {
            null
        }
        if (cacheKey != null && namedByThePhone == null && options.diskCachePolicy.readEnabled) {
            RecipeRenderCache.read(cacheKey)?.let(::decodeRenderCacheBytes)?.let { cached ->
                if (recipe != null) DevelopedRawBitmaps.mark(cached, recipeTransformation(recipe).cacheKey)
                return DecodeResult(cached.asImage(), isSampled = true)
            }
        }
        val raw = decodeRawPreview(file, side, recipe?.appliesBaselineExposure() ?: true, recipe?.rawHighlightMode() ?: CURRENT_RAW_HIGHLIGHT) ?: return null
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
                ?: RenderParameters(
                    rawProfile = RawProfile.COLOR,
                    rawExposure = matched,
                    highlightCeiling = RawProfile.HEADROOM_CEILING,
                    // What the editor will give a RAW it develops for the first time, so that the grid and the editor agree.
                    sharpenAmount = RAW_INITIAL_SHARPEN_AMOUNT,
                    wideHighlights = true,
                )
            val rendered = RecipePixels.renderFloat(raw, parameters, consumeSource = true)
            // The viewer's develop of a server RAW is downloaded for; the grid's tile comes from it too.
            if (library != null && recipe != null && side == LIBRARY_RAW_FULL_SIDE) {
                LibraryRawEdits.keepRendered(library, recipe, rendered, sides = listOf(LIBRARY_RAW_TILE_SIDE))
            }
            rendered.toPixelBuffer().toPlatformBitmap().also { image ->
                if (recipe != null) DevelopedRawBitmaps.mark(image, recipeTransformation(recipe).cacheKey)
            }
        }
        if (cacheKey != null && (namedByThePhone != null || options.diskCachePolicy.writeEnabled)) {
            RecipeRenderCache.write(cacheKey) { bitmap.encodeForRenderCache() }
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
