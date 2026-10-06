package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.feature.editor.resources.*
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    suspend fun saveJpeg(source: File, fileName: String, createdAt: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            parseEpochMillis(createdAt)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ImmichRoom")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "ImmichRoom",
                ).apply { mkdirs() }
                put(MediaStore.Images.Media.DATA, File(directory, fileName).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw LocalizedException(uiText(Res.string.editor_gallery_refused))
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                source.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
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
