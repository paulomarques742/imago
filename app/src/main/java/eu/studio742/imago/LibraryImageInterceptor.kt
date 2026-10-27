package eu.studio742.imago

import android.net.Uri
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.transformations
import eu.studio742.imago.core.render.EditRecipeTransformation
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
            // The grant was checked just above, before any cache is read, so the memory cache cannot
            // show a photo whose access was taken away. Without it, every tile that came back into view
            // was decoded again. Nothing of it is written to disk, by Coil or by the recipe.
            return chain.withRequest(request.newBuilder()
                .httpHeaders(request.httpHeaders.newBuilder().set(API_KEY, emptyList()).build())
                .diskCachePolicy(CachePolicy.DISABLED)
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
    }
}
