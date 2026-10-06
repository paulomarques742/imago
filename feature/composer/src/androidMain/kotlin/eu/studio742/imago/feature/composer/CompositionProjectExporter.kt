package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.feature.composer.resources.*
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.data.CompositionMediaRepository

/** Prepares the whole sequence in cache and only then makes any result public. */
class CompositionProjectExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val staticExporter: CompositionStaticExporter,
    private val media3: Media3CompositionAdapter,
    private val media: CompositionMediaRepository,
) {
    suspend fun export(
        project: CompositionProject,
        staticFormat: StaticExportFormat,
        pageIndex: Int? = null,
        targetLibraryId: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): CompositionExportResult = withContext(Dispatchers.IO) {
        val targets = pageIndex?.let { listOf(it.coerceIn(project.pages.indices)) } ?: project.pages.indices.toList()
        val files = mutableListOf<PreparedPage>()
        try {
            targets.forEachIndexed { position, index ->
                ensureActive()
                val hasVideo = project.elements.any { it is CompositionElement.Video && it.visible && it.pageIndex == index }
                val file = if (hasVideo) media3.exportPage(project, index) else staticExporter.renderPageToCache(project, index, staticFormat)
                files += PreparedPage(index, file, if (hasVideo) "video/mp4" else staticFormat.mimeType)
                onProgress(position + 1, targets.size)
            }
            val published = publishAtomically(project.name, appString(Res.string.composer_composition), files)
            if (targetLibraryId == null) published else {
                var failures = 0
                files.zip(published.fileNames).forEach { (page, name) ->
                    runCatching { media.uploadComposition(checkNotNull(targetLibraryId), page.file, name, page.mimeType, project.createdAt) }
                        .onFailure { failures++ }
                }
                published.copy(immichUploadFailures = failures)
            }
        } finally {
            files.forEach { it.file.delete() }
        }
    }

    suspend fun previewPage(project: CompositionProject, pageIndex: Int): File = media3.exportPage(project, pageIndex, proxy = true)

    private fun publishAtomically(projectName: String, untitled: String, pages: List<PreparedPage>): CompositionExportResult {
        val resolver = context.contentResolver
        val safeName = projectName.lowercase().replace(Regex("[^a-z0-9à-ÿ]+"), "-").trim('-').ifBlank { untitled }
        val created = mutableListOf<Uri>()
        val names = pages.map { page ->
            val extension = if (page.mimeType == "video/mp4") "mp4" else if (page.mimeType == "image/png") "png" else "jpg"
            "${safeName}_${(page.index + 1).toString().padStart(2, '0')}.$extension"
        }
        try {
            pages.zip(names).forEach { (page, name) ->
                val isVideo = page.mimeType == "video/mp4"
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, page.mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val root = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/ImmichRoom")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val uri = checkNotNull(resolver.insert(collection, values)) { "The gallery refused to create $name." }
                created += uri
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                    page.file.inputStream().buffered().use { input -> input.copyTo(output) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) created.forEach { uri ->
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            return CompositionExportResult(created, names, pages.map { it.mimeType })
        } catch (error: Exception) {
            created.forEach { resolver.delete(it, null, null) }
            throw error
        }
    }

    private data class PreparedPage(val index: Int, val file: File, val mimeType: String)
}
