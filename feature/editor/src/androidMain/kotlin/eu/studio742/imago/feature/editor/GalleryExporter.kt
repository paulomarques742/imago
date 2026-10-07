package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.feature.editor.resources.*
import eu.studio742.imago.core.model.DEVICE_ALBUM_NAME
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject

class GalleryExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun saveJpeg(source: File, fileName: String, createdAt: String): Uri =
        save(source, fileName, "image/jpeg", isVideo = false, createdAt)

    /**
     * An original as it came from the library, photo or video, in the same album as the exports.
     *
     * The type comes from the name's extension: MediaStore rewrites the extension to match the type it
     * is given, and a HEIC declared as JPEG would land as `.heic.jpg`.
     */
    suspend fun saveOriginal(source: File, fileName: String, isVideo: Boolean, createdAt: String): Uri {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?.takeIf { it.startsWith(if (isVideo) "video/" else "image/") }
            ?: if (isVideo) "video/mp4" else "image/jpeg"
        return save(source, fileName, mimeType, isVideo, createdAt)
    }

    private suspend fun save(
        source: File,
        fileName: String,
        mimeType: String,
        isVideo: Boolean,
        createdAt: String,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            parseEpochMillis(createdAt)?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            // Videos too go under Pictures, which MediaStore accepts for them: one album in the
            // gallery with everything that came out of the app, instead of half of it in Movies.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$DEVICE_ALBUM_NAME")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    DEVICE_ALBUM_NAME,
                ).apply { mkdirs() }
                put(MediaStore.MediaColumns.DATA, File(directory, fileName).absolutePath)
            }
        }
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw LocalizedException(uiText(Res.string.editor_gallery_refused))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun parseEpochMillis(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        }
}
