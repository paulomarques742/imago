package eu.studio742.imago

import android.net.Uri
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.transformations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Authenticated image caches are account-specific; revoked local grants cannot use cached pixels. */
class LibraryImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
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
            return chain.withRequest(request.newBuilder()
                .httpHeaders(request.httpHeaders.newBuilder().set(API_KEY, emptyList()).build())
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build())
                .proceed()
        }
        val credential = request.httpHeaders[API_KEY] ?: return chain.proceed()
        val partition = MessageDigest.getInstance("SHA-256").digest(credential.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val key = "${request.data}:$partition"
        return chain.withRequest(request.newBuilder().diskCacheKey(key)
            .memoryCacheKey("$key:${request.transformations.joinToString { it.cacheKey }}").build())
            .proceed()
    }

    private companion object {
        const val API_KEY = "x-api-key"
    }
}
