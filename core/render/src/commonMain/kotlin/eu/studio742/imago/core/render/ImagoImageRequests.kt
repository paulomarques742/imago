package eu.studio742.imago.core.render

import coil3.Extras
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.transformations
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
