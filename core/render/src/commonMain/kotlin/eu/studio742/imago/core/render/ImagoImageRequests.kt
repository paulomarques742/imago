package eu.studio742.imago.core.render

import coil3.Extras
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.transformations
import coil3.size.Dimension
import coil3.transform.Transformation
import eu.studio742.imago.core.immich.IMMICH_API_KEY_HEADER
import eu.studio742.imago.core.model.EditRecipe

/**
 * The Immich API key in the request, when the library has one. Device photos carry no header: there
 * is no server to send it to.
 */
fun ImageRequest.Builder.libraryAuth(apiKey: String?): ImageRequest.Builder = apply {
    apiKey?.takeIf(String::isNotBlank)?.let { httpHeaders(NetworkHeaders.Builder().set(IMMICH_API_KEY_HEADER, it).build()) }
}

/**
 * The local recipe applied to the image Coil loads. Coil treats the transformation as part of the
 * memory cache key; on disk, the processed version is kept by [RecipeRenderCache].
 */
fun ImageRequest.Builder.withRecipe(recipe: EditRecipe?): ImageRequest.Builder = apply {
    extras[rawRecipeKey] = recipe
    recipe?.let { transformations(recipeTransformation(it)) }
}

/**
 * Set on a request that asks for the quick look of a RAW — what the phone already keeps of it — and
 * not for the developed photo. A fetcher that can answer it quickly does; one that cannot fails the
 * request, because the alternative is developing the RAW to show a placeholder.
 */
val RawQuickPreview = Extras.Key(default = false)

/** Marks a request as the tile of a RAW from the phone, whose developed render is kept in [RecipeRenderCache]. */
val DevelopedRawTile = Extras.Key(default = false)

fun ImageRequest.Builder.developedRawTile(): ImageRequest.Builder = apply { extras[DevelopedRawTile] = true }

/** A RAW of a server library, named by its id there and the checksum of its original. */
class LibraryRaw(val assetId: String, val checksum: String)

/** Set by [developedLibraryRaw]; read by [LibraryRawFetcher], [CachedRawTileFetcher] and the decoder. */
val LibraryRawOriginal = Extras.Key<LibraryRaw?>(default = null)

/** Whether a [developedLibraryRaw] request may download the original when its develop is not kept. */
val LibraryRawDownload = Extras.Key(default = false)

/**
 * Marks the image of an edited RAW from a server library as one whose develop is shown when it is kept.
 * What the server serves for it is a JPEG it made itself, already contrasted, and a recipe made over the
 * development and laid over that JPEG is only an approximation of the edit.
 *
 * The develop is kept when the editor is left ([LibraryRawEdits]) or when the original is downloaded,
 * which only a request with [download] does: an original is tens of megabytes, and a grid that fetched
 * one for every edited RAW it showed spent hundreds of them without being asked. Without a kept develop
 * and without [download], the request is answered with the approximation. [kept] is
 * [LibraryRawEdits.kept], collected by the screen so that it asks again when an edit is kept.
 */
fun ImageRequest.Builder.developedLibraryRaw(
    assetId: String,
    checksum: String,
    download: Boolean = false,
    kept: Int = 0,
): ImageRequest.Builder = apply {
    extras[DevelopedRawTile] = true
    extras[LibraryRawOriginal] = LibraryRaw(assetId, checksum)
    // [LibraryRawEdits.kept] as the screen last saw it: a new value is a new request, which looks for a
    // develop kept since.
    memoryCacheKeyExtra(KEPT_EXTRA, kept.toString())
    if (download) {
        extras[LibraryRawDownload] = true
        // The memory cache may hold the approximation under the same key: this one is asked for to replace it.
        memoryCachePolicy(CachePolicy.WRITE_ONLY)
    }
}

private const val KEPT_EXTRA = "imago.libraryRawKept"

/** The side a request's develop is made at, from the size Coil resolved for it. */
internal fun rawTileSide(options: Options): Int = rawTileSide(
    maxOf((options.size.width as? Dimension.Pixels)?.px ?: 0, (options.size.height as? Dimension.Pixels)?.px ?: 0)
        .takeIf { it > 0 } ?: RAW_TILE_SIDES.last(),
)

/**
 * The two sides a server library's RAW is kept at — the grid's and the viewer's — instead of
 * [rawTileSide]'s six, so that the editor, which has the development in hand, can keep both.
 */
const val LIBRARY_RAW_TILE_SIDE = 512
const val LIBRARY_RAW_FULL_SIDE = 2048

internal fun libraryRawSide(options: Options): Int = libraryRawSide(options.size)

internal fun libraryRawSide(size: coil3.size.Size): Int {
    val requested = maxOf((size.width as? Dimension.Pixels)?.px ?: 0, (size.height as? Dimension.Pixels)?.px ?: 0)
    return if (rawTileSide(requested.takeIf { it > 0 } ?: RAW_TILE_SIDES.last()) <= LIBRARY_RAW_TILE_SIDE) {
        LIBRARY_RAW_TILE_SIDE
    } else {
        LIBRARY_RAW_FULL_SIDE
    }
}

/**
 * Where the develop of [raw] with [recipe] at [side] is kept: named by the photo and its original's
 * checksum, so that it is found before anything is downloaded.
 */
internal fun libraryRawKey(raw: LibraryRaw, recipe: EditRecipe?, side: Int): String {
    val transformationKey = recipe?.let { recipeTransformation(it).cacheKey } ?: RAW_DEFAULT_DEVELOPMENT_KEY
    return RecipeRenderCache.identityKey(transformationKey, "library:${raw.assetId}:${raw.checksum}", side)
}

/** [libraryRawKey] for a [developedLibraryRaw] request; null for any other request. */
internal fun libraryRawKey(options: Options): String? {
    val raw = options.extras[LibraryRawOriginal] ?: return null
    return libraryRawKey(raw, options.extras[rawRecipeKey], libraryRawSide(options))
}

/**
 * Whether [image] is an edit developed from its RAW — kept, or developed now — and not the server's
 * image with [recipe] laid over it: what the viewer tells apart to say that it shows an approximation.
 */
fun isDevelopedRawEdit(image: coil3.Image?, recipe: EditRecipe): Boolean {
    val bitmap = (image as? coil3.BitmapImage)?.bitmap ?: return false
    return DevelopedRawBitmaps.contains(bitmap, recipeTransformation(recipe).cacheKey)
}

/**
 * The side a developed RAW is made and kept at for a request of [requested] pixels. A handful of sizes
 * and not every size: the grid, the viewer's placeholder and its strip all ask for something a little
 * different, and each of those would otherwise be a RAW developed again. None goes under [RAW_TILE_MIN_SIDE],
 * since one render serves them all and the first one is the slow one.
 */
fun rawTileSide(requested: Int): Int =
    RAW_TILE_SIDES.firstOrNull { it >= requested } ?: RAW_TILE_SIDES.last()

const val RAW_TILE_MIN_SIDE = 384
private val RAW_TILE_SIDES = listOf(RAW_TILE_MIN_SIDE, 512, 768, 1024, 1536, 2048)

/**
 * Where the developed render of this tile is kept, made by whoever can name the photo without opening
 * it — the phone's interceptor, from MediaStore. The decoder writes under it and [CachedRawTileFetcher] reads it.
 */
val RawTileCacheKey = Extras.Key<String?>(default = null)

/**
 * The quick look of a RAW, for a tile to show while the developed one is still being made. It has its
 * own memory cache key, so that it is never taken for the developed photo of the same data, and no
 * recipe: it is the photo as the camera left it.
 */
fun ImageRequest.Builder.rawQuickPreview(source: String): ImageRequest.Builder = apply {
    extras[RawQuickPreview] = true
    data(source)
    memoryCacheKey("raw-quick:$source")
}

/** The transformation that applies [recipe]: Android's Bitmap on one side, Skia's on the other. */
expect fun recipeTransformation(recipe: EditRecipe): Transformation
