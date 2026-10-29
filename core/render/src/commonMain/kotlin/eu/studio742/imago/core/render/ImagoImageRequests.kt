package eu.studio742.imago.core.render

import coil3.Extras
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
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

/**
 * Marks the image of an edited RAW from a server library as one to develop from its original, as the
 * editor and the export do. What the server serves for it is a JPEG it made itself, already contrasted,
 * and a recipe made over the development and laid over that JPEG looked like another edit. The render
 * is kept in [RecipeRenderCache] and the original is only fetched when it is not there.
 */
fun ImageRequest.Builder.developedLibraryRaw(assetId: String, checksum: String): ImageRequest.Builder = apply {
    extras[DevelopedRawTile] = true
    extras[LibraryRawOriginal] = LibraryRaw(assetId, checksum)
}

/** The side a request's develop is made at, from the size Coil resolved for it. */
internal fun rawTileSide(options: Options): Int = rawTileSide(
    maxOf((options.size.width as? Dimension.Pixels)?.px ?: 0, (options.size.height as? Dimension.Pixels)?.px ?: 0)
        .takeIf { it > 0 } ?: RAW_TILE_SIDES.last(),
)

/**
 * Where the develop of a [developedLibraryRaw] request is kept: named by the photo and its original's
 * checksum, so that it is found before anything is downloaded. Null for any other request.
 */
internal fun libraryRawKey(options: Options): String? {
    val raw = options.extras[LibraryRawOriginal] ?: return null
    val recipe = options.extras[rawRecipeKey]
    val transformationKey = recipe?.let { recipeTransformation(it).cacheKey } ?: RAW_DEFAULT_DEVELOPMENT_KEY
    return RecipeRenderCache.identityKey(transformationKey, "library:${raw.assetId}:${raw.checksum}", rawTileSide(options))
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
