package eu.studio742.imago

import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import eu.studio742.imago.core.render.DevelopedRawTile
import eu.studio742.imago.core.render.RAW_DEFAULT_DEVELOPMENT_KEY
import eu.studio742.imago.core.render.RawTileCacheKey
import eu.studio742.imago.core.render.RecipeRenderCache
import eu.studio742.imago.core.render.EditRecipeTransformation
import eu.studio742.imago.core.render.rawTileSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Authenticated image caches are account-specific; revoked local grants cannot use cached pixels. */
class LibraryImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = try { interceptLibrary(chain) } catch (error: Exception) {
            eu.studio742.imago.core.model.diagnostics.LocalDiagnostics.failure(
                eu.studio742.imago.core.model.diagnostics.DiagnosticArea.IMAGE, error,
                eu.studio742.imago.core.model.diagnostics.DiagnosticOperation.THUMBNAIL)
            throw error
        }
        if (result is ErrorResult) eu.studio742.imago.core.model.diagnostics.LocalDiagnostics.failure(
            eu.studio742.imago.core.model.diagnostics.DiagnosticArea.IMAGE, result.throwable,
            eu.studio742.imago.core.model.diagnostics.DiagnosticOperation.THUMBNAIL)
        return result
    }

    private suspend fun interceptLibrary(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val uri = when (val data = request.data) {
            is Uri -> data
            is String -> Uri.parse(data)
            is coil3.Uri -> Uri.parse(data.toString())
            else -> null
        }
        if (uri?.scheme == "content") {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(request.context.contentResolver.openAssetFileDescriptor(uri, "r")) {
                        "The content is no longer available."
                    }.close()
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                return ErrorResult(null, request, error)
            }
            val rawTileKey = developedRawTileKey(request, uri, chain.size)
            // The grant was checked just above, before any cache is read, so the memory cache cannot
            // show a photo whose access was taken away. Without it, every tile that came back into view
            // was decoded again. Nothing of it is written to disk, by Coil or by the recipe.
            return chain.withRequest(request.newBuilder()
                .httpHeaders(request.httpHeaders.newBuilder().set(API_KEY, emptyList()).build())
                .diskCachePolicy(CachePolicy.DISABLED)
                // A developed RAW stays on the phone, in the app's own cache, and is named from MediaStore.
                .apply { rawTileKey?.let { extras[RawTileCacheKey] = it } }
                .apply {
                    val current = request.transformations
                    if (current.any { it is EditRecipeTransformation }) {
                        transformations(current.map { (it as? EditRecipeTransformation)?.withoutDiskCopy() ?: it })
                    }
                }
                .build())
                .proceed()
        }
        val credential = request.httpHeaders[API_KEY] ?: return chain.proceed()
        val key = "${request.data}:${partitionOf(credential)}"
        return chain.withRequest(request.newBuilder().diskCacheKey(key)
            .memoryCacheKey("$key:${request.transformations.joinToString { it.cacheKey }}").build())
            .proceed()
    }

    /**
     * The key of a RAW tile's developed render, made from what MediaStore says of the photo — its size and
     * when it last changed — so that nothing has to be opened to find the render. Null for any other request.
     */
    private suspend fun developedRawTileKey(request: coil3.request.ImageRequest, uri: Uri, size: Size): String? {
        if (request.extras[DevelopedRawTile] != true) return null
        val identity = withContext(Dispatchers.IO) {
            runCatching {
                request.context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) "$uri:${cursor.getLong(0)}:${cursor.getLong(1)}" else null }
            }.getOrNull()
        } ?: return null
        val side = rawTileSide(maxOf(
            (size.width as? Dimension.Pixels)?.px ?: 0,
            (size.height as? Dimension.Pixels)?.px ?: 0,
        ).takeIf { it > 0 } ?: RAW_TILE_MAX_SIDE)
        val recipe = request.transformations.firstNotNullOfOrNull { it as? EditRecipeTransformation }?.cacheKey
        return RecipeRenderCache.identityKey(recipe ?: RAW_DEFAULT_DEVELOPMENT_KEY, identity, side)
    }

    /**
     * Each credential's hash, made once: it is asked for by every tile, on the thread that draws them.
     * There are as many entries as servers in the library.
     */
    private val partitions = ConcurrentHashMap<String, String>()

    private fun partitionOf(credential: String): String = partitions.getOrPut(credential) {
        MessageDigest.getInstance("SHA-256").digest(credential.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val API_KEY = "x-api-key"

        /** What the RAW decoder caps a request at, so that the key names what it will make. */
        const val RAW_TILE_MAX_SIDE = 2048
    }
}
