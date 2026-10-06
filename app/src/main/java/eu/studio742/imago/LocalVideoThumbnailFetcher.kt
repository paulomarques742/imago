package eu.studio742.imago

import android.content.Context
import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalVideoThumbnailFetcher(private val context: Context, private val uri: Uri) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
        ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }
    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "content") return null
            val uri = Uri.parse(data.toString())
            return if (context.contentResolver.getType(uri)?.startsWith("video/") == true) LocalVideoThumbnailFetcher(context, uri) else null
        }
    }
}
