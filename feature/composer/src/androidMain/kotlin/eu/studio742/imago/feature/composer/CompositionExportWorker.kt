package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.getPluralString
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.feature.composer.resources.*
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import eu.studio742.imago.core.data.CompositionRepository

class CompositionExportWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val failed = appString(Res.string.composer_export_failed)
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure(errorData(failed))
        val format = runCatching { StaticExportFormat.valueOf(inputData.getString(KEY_FORMAT).orEmpty()) }
            .getOrElse { return Result.failure(errorData(failed)) }
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ComposerWorkerEntryPoint::class.java)
        val snapshotId = inputData.getString(KEY_SNAPSHOT_ID)
        val project = runCatching {
            if (snapshotId != null) entryPoint.snapshots().read(snapshotId) else entryPoint.projects().get(projectId)
        }.getOrElse { return Result.failure(errorData(it.toUiText(Res.string.composer_export_failed).resolveNow())) }
            ?: return Result.failure(errorData(appString(Res.string.composer_project_missing)))
        val page = inputData.getInt(KEY_PAGE_INDEX, -1).takeIf { it >= 0 }
        val total = if (page == null) project.pages.size else 1
        // The notification updates outside coroutines; the texts are resolved here, once.
        texts = NotificationTexts(
            channel = appString(Res.string.composer_export_channel),
            title = appString(Res.string.composer_exporting),
            cancel = appString(Res.string.composer_cancel),
            progress = (0..total).map { getPluralString(Res.plurals.composer_export_progress, total, it, total) },
        )
        setForeground(foregroundInfo(0, total))
        return try {
            val result = entryPoint.exporter().export(
                project = project,
                staticFormat = format,
                pageIndex = page,
                targetLibraryId = inputData.getString(KEY_TARGET_LIBRARY),
            ) { completed, total ->
                setProgressAsync(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to total))
                setForegroundAsync(foregroundInfo(completed, total))
            }
            Result.success(
                workDataOf(
                    KEY_URIS to result.uris.map { it.toString() }.toTypedArray(),
                    KEY_MIME_TYPES to result.mimeTypes.toTypedArray(),
                    KEY_UPLOAD_FAILURES to result.immichUploadFailures,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(errorData(error.toUiText(Res.string.composer_export_failed).resolveNow()))
        } finally {
            if (snapshotId != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { entryPoint.snapshots().delete(snapshotId) }
        }
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    private class NotificationTexts(val channel: String, val title: String, val cancel: String, val progress: List<String>)

    private lateinit var texts: NotificationTexts

    private fun foregroundInfo(completed: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, texts.channel, NotificationManager.IMPORTANCE_LOW),
            )
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(texts.title)
            .setContentText(texts.progress.getOrNull(completed).takeIf { texts.progress.size == total + 1 } ?: "$completed / $total")
            .setProgress(total, completed, completed == 0)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, texts.cancel, cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_SNAPSHOT_ID = "snapshot_id"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_FORMAT = "format"
        const val KEY_PAGE_INDEX = "page_index"
        const val KEY_TARGET_LIBRARY = "target_library"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_URIS = "uris"
        const val KEY_MIME_TYPES = "mime_types"
        const val KEY_UPLOAD_FAILURES = "upload_failures"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "composition_exports"
        private const val NOTIFICATION_ID = 7426
        fun uniqueName(projectId: String) = "composition-export-$projectId"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ComposerWorkerEntryPoint {
    fun snapshots(): eu.studio742.imago.core.data.CompositionExportSnapshots
    fun projects(): CompositionRepository
    fun exporter(): CompositionProjectExporter
}
