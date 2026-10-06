package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.CompositionProject
import java.io.File

/** The format of an exported page. */
enum class StaticExportFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
}

/**
 * What was saved.
 *
 * @param locations where each page went — the gallery URI on Android, the path on the computer.
 */
data class CompositionExportOutcome(
    val locations: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val immichUploadFailures: Int = 0,
)

/**
 * A composition's export, on each platform.
 *
 * On Android it runs in a background job, with a notification, to survive leaving the app; on desktop
 * it runs in the app itself, which nobody interrupts. Both draw the pages with the same model, and
 * both return the same result.
 */
interface CompositionExports {
    suspend fun export(
        project: CompositionProject,
        format: StaticExportFormat,
        pageIndex: Int?,
        targetLibraryId: String?,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): CompositionExportOutcome

    fun cancel(projectId: String)

    /** A preview of a page with video, in a temporary file. */
    suspend fun previewPage(project: CompositionProject, pageIndex: Int): File
}
