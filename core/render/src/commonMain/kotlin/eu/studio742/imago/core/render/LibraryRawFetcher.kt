package eu.studio742.imago.core.render

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The original of a [developedLibraryRaw] request, for the RAW decoder to develop when
 * [CachedRawTileFetcher] has no render of it yet.
 *
 * [download] is the library's own: it knows the server, the key, and keeps the originals of the
 * session, so a photo the editor has just opened is not downloaded again. When the original cannot be
 * had — offline, a server that refuses it — this declines, and the request falls back to the server's
 * own image with the recipe over it, which is better than an empty tile. The library records the failure.
 */
class LibraryRawFetcher private constructor(
    private val raw: LibraryRaw,
    private val options: Options,
    private val download: suspend (assetId: String, destination: File) -> Unit,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val file = createPhotoTempFile(options.context)
        try {
            downloads.withPermit { download(raw.assetId, file) }
        } catch (cancelled: CancellationException) {
            file.delete()
            throw cancelled
        } catch (failure: Exception) {
            file.delete()
            return null
        }
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM, closeable = AutoCloseable { file.delete() }),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val download: suspend (assetId: String, destination: File) -> Unit) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val raw = options.extras[LibraryRawOriginal] ?: return null
            return LibraryRawFetcher(raw, options, download)
        }
    }

    private companion object {
        /**
         * Originals are tens of megabytes: a grid of edited RAWs coming into view must not ask for all of
         * them at once, and they are developed one at a time anyway.
         */
        val downloads = Semaphore(2)
    }
}
