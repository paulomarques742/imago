package eu.studio742.imago.core.render

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A RAW tile from the phone, answered from [RecipeRenderCache] before the RAW is opened.
 *
 * Reading a RAW through a content URI copies it into a temporary file, and developing it is one at a
 * time: both are what a tile that was already developed should not pay again. Whoever made the request
 * named the render in [RawTileCacheKey]; when it is there this answers with it, and when it is not —
 * the first time, or after the recipe was saved again — it declines and the RAW is developed, and the
 * decoder keeps the result under the same key.
 */
class CachedRawTileFetcher private constructor(
    private val key: String,
    private val recipeKey: String?,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val bitmap = withContext(Dispatchers.IO) { RecipeRenderCache.read(key)?.let(::decodeRenderCacheBytes) } ?: return null
        // The transformation that follows the recipe does not render it twice.
        recipeKey?.let { DevelopedRawBitmaps.mark(bitmap, it) }
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val key = options.extras[RawTileCacheKey] ?: return null
            return CachedRawTileFetcher(key, options.extras[rawRecipeKey]?.let { recipeTransformation(it).cacheKey })
        }
    }
}
