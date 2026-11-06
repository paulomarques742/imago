package eu.studio742.imago.core.render

import coil3.BitmapImage
import coil3.Extras
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.transformations
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lays [CameraToneCurve] under every image a server serves of a RAW, so that the grid, the viewer, the
 * composer and the rest show the photo as the editor will. It works on the request, whichever screen
 * made it, so a new screen needs nothing done for it.
 *
 * The curve costs one short read of the original the first time a photo is seen, and is then kept by the
 * original's checksum. A photo with no small JPEG near its start, an unreachable server or a key that
 * may not download keep the server's image as it is: this improves an image, it never stands in its way.
 *
 * It goes after [LibraryRawInterceptor], for the memory cache key they both add to, and a develop that
 * is kept already is left alone: its tones are the editor's own.
 *
 * @param rawAt the RAW that a server image's URL shows, null for anything else.
 * @param head the first bytes of an original.
 * @param serverPixels the server's thumbnail of the photo a request shows.
 */
class ServerRawToneInterceptor(
    private val rawAt: suspend (url: String) -> LibraryRaw?,
    private val head: suspend (assetId: String, bytes: Int) -> ByteArray,
    private val serverPixels: suspend (ImageRequest) -> PixelBuffer? = ::serverThumbnail,
) : Interceptor {
    private val known = LruMap<String, LibraryRaw?>(KNOWN_URLS)
    private val curves = LruMap<String, CameraToneCurve?>(KNOWN_CURVES)
    private val resumeAt = AtomicLong(0)

    // Each lock is one read at a time: a grid coming into view must not ask for all its photos at once,
    // and two screens asking for one photo meet at the same lock.
    private val locks = Array(CONCURRENT_READS) { Mutex() }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http") || request.extras[ToneProbe] == true || isDeveloped(request, chain)) return chain.proceed()
        val raw = rawOf(url) ?: return chain.proceed()
        val curve = curveOf(raw, request) ?: return chain.proceed()
        return chain.withRequest(
            request.newBuilder()
                .transformations(listOf(CameraToneTransformation(curve)) + request.transformations)
                .memoryCacheKeyExtra(MEMORY_EXTRA, curve.id)
                .build(),
        ).proceed()
    }

    private suspend fun isDeveloped(request: ImageRequest, chain: Interceptor.Chain): Boolean {
        val raw = request.extras[LibraryRawOriginal] ?: return false
        return request.extras[LibraryRawDownload] == true || withContext(Dispatchers.IO) {
            RecipeRenderCache.contains(libraryRawKey(raw, request.extras[rawRecipeKey], libraryRawSide(chain.size)))
        }
    }

    private suspend fun rawOf(url: String): LibraryRaw? {
        if (known.containsKey(url)) return known[url]
        return runCatching { rawAt(url) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            .also { known[url] = it }
    }

    private suspend fun curveOf(raw: LibraryRaw, request: ImageRequest): CameraToneCurve? {
        val identity = "library:${raw.assetId}:${raw.checksum}"
        if (curves.containsKey(identity)) return curves[identity]
        if (System.currentTimeMillis() < resumeAt.get()) return null
        val found = withTimeoutOrNull(WAIT_MILLIS) {
            locks[identity.hashCode().mod(CONCURRENT_READS)].withLock {
                if (curves.containsKey(identity)) return@withLock Answer(curves[identity])
                val key = RecipeRenderCache.identityKey(CURVE_KEY, identity, 0)
                val stored = withContext(Dispatchers.IO) { RecipeRenderCache.read(key) }
                val answer = if (stored != null) Answer(CameraToneCurve.of(stored)) else measure(raw, request)
                if (stored == null && answer != null) {
                    // Nothing to say is an answer too: without it, a photo with no small JPEG is read every time.
                    RecipeRenderCache.write(key) { answer.curve?.table ?: NO_CURVE }
                }
                answer?.also { curves[identity] = it.curve }
            }
        }
        // A slow link: the next photos should not each wait this long before showing.
        if (found == null) resumeAt.set(System.currentTimeMillis() + PAUSE_MILLIS)
        return found?.curve
    }

    private class Answer(val curve: CameraToneCurve?)

    /** Null when it could not be asked, which is not the same as the photo having nothing to say. */
    private suspend fun measure(raw: LibraryRaw, request: ImageRequest): Answer? {
        val preview = try {
            filePreview(raw)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The library has recorded why. Every other photo of that server would fail the same way.
            resumeAt.set(System.currentTimeMillis() + PAUSE_MILLIS)
            return null
        } ?: return Answer(null)
        val server = serverPixels(request) ?: return null
        return Answer(withContext(Dispatchers.Default) { decodePixels(preview)?.let { CameraToneCurve.between(server, it) } })
    }

    private suspend fun filePreview(raw: LibraryRaw): ByteArray? {
        // Most files need only some of this, and it is paid once for every RAW in the library.
        for (bytes in READS) {
            val read = head(raw.assetId, bytes)
            EmbeddedPreview.largestWhole(read)?.let { return it }
            if (read.size < bytes) return null
        }
        return null
    }

    /** Least recently used out first; a key can stand for null. */
    private class LruMap<K, V>(private val capacity: Int) {
        private val entries = object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
        }

        @Synchronized fun containsKey(key: K): Boolean = entries.containsKey(key)
        @Synchronized operator fun get(key: K): V? = entries[key]
        @Synchronized operator fun set(key: K, value: V) { entries[key] = value }
    }

    private companion object {
        const val MEMORY_EXTRA = "imago.cameraTone"
        const val CURVE_KEY = "camera-tone-2"
        val NO_CURVE = ByteArray(1)

        /** Measured: a RAF's small JPEG ends by 13 KB, a CR3's, ARW's or Samsung DNG's by 58 KB, a drone's at 76 KB. */
        val READS = listOf(16 * 1024, 64 * 1024, 128 * 1024)

        const val CONCURRENT_READS = 6
        const val WAIT_MILLIS = 2_000L
        const val PAUSE_MILLIS = 30_000L
        const val KNOWN_URLS = 4_096
        const val KNOWN_CURVES = 2_048
    }
}

/** Marks the request made to measure, which has no curve to wait for. */
private val ToneProbe = Extras.Key(default = false)

/** The curve is measured on the thumbnail whichever size is being shown: it is small, and cached. */
private suspend fun serverThumbnail(request: ImageRequest): PixelBuffer? {
    val url = (request.data as String).replace("size=preview", "size=thumbnail")
    val result = SingletonImageLoader.get(request.context).execute(
        ImageRequest.Builder(request.context)
            .data(url)
            .httpHeaders(request.httpHeaders)
            .softwareBitmap()
            .memoryCachePolicy(CachePolicy.DISABLED)
            .apply { extras[ToneProbe] = true }
            .build(),
    )
    val bitmap = (result.image as? BitmapImage)?.bitmap ?: return null
    return withContext(Dispatchers.Default) { bitmap.toPixelBuffer() }
}
