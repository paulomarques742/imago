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
import coil3.size.Dimension
import eu.studio742.imago.core.render.RawQuickPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The thumbnail Android already keeps for a photo or video on the phone.
 *
 * A video has no other way to become an image. A photo does — decoding the original — but for a tile
 * that is the whole file read and scaled down every time the tile comes back into view, while
 * MediaStore answers from the thumbnail it keeps. The original is still what a large request gets: the
 * detail, the editor.
 */
class LocalThumbnailFetcher(
    private val context: Context,
    private val uri: Uri,
    private val side: Int,
    private val isVideo: Boolean,
    /** A quick look of a RAW: if the phone has no thumbnail for it, the request fails instead of developing the RAW. */
    private val strict: Boolean = false,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val bitmap = try {
            context.contentResolver.loadThumbnail(uri, Size(side, side), null)
        } catch (error: Exception) {
            if (error is CancellationException || isVideo || strict) throw error
            // A photo whose thumbnail cannot be made still has its original: the next fetcher reads it.
            return@withContext null
        }
        ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "content") return null
            val uri = Uri.parse(data.toString())
            val type = context.contentResolver.getType(uri) ?: return null
            if (type.startsWith("video/")) return LocalThumbnailFetcher(context, uri, VIDEO_SIDE, isVideo = true)
            // A raw file is developed by the decoder with the recipe's own settings; the camera's
            // preview inside it, which is what MediaStore would give, is not that photo. It is only
            // good as the quick look a tile shows while the developed one is made.
            val quick = options.extras[RawQuickPreview] == true
            if (type !in DISPLAY_TYPES && !quick) return null
            // The square of the longer side: the thumbnail fits inside it, so a tile of the photo's own
            // shape is covered without enlarging it.
            val width = (options.size.width as? Dimension.Pixels)?.px ?: return null
            val height = (options.size.height as? Dimension.Pixels)?.px ?: return null
            val side = maxOf(width, height)
            if (quick) return LocalThumbnailFetcher(context, uri, side.coerceAtMost(MAX_THUMBNAIL_SIDE), isVideo = false, strict = true)
            return if (side <= MAX_THUMBNAIL_SIDE) LocalThumbnailFetcher(context, uri, side, isVideo = false) else null
        }
    }

    private companion object {
        const val VIDEO_SIDE = 512
        /** Above this the request is a photo being looked at, not a tile, and gets the original. */
        const val MAX_THUMBNAIL_SIDE = 640
        val DISPLAY_TYPES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/gif", "image/bmp",
        )
    }
}
