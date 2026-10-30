package eu.studio742.imago.core.render

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tells apart, in the memory cache, a [developedLibraryRaw] image shown as an approximation from the same
 * image once its develop is kept. Both come from the same request; without this, a tile that had shown the
 * approximation kept showing it from memory after the viewer or the editor had kept the develop.
 *
 * It runs before the memory cache is read, and only looks for the develop on disk, without reading it.
 */
class LibraryRawInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val raw = request.extras[LibraryRawOriginal] ?: return chain.proceed()
        val developed = request.extras[LibraryRawDownload] == true || withContext(Dispatchers.IO) {
            RecipeRenderCache.contains(libraryRawKey(raw, request.extras[rawRecipeKey], libraryRawSide(chain.size)))
        }
        return chain.withRequest(
            request.newBuilder().memoryCacheKeyExtra(MEMORY_EXTRA, if (developed) "developed" else "approximate").build(),
        ).proceed()
    }

    private companion object {
        const val MEMORY_EXTRA = "imago.libraryRaw"
    }
}
