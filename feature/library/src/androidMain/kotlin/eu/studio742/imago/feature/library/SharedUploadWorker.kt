package eu.studio742.imago.feature.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.SharedMediaUploader
import eu.studio742.imago.core.data.SharedUploadQueue
import eu.studio742.imago.core.data.SharedUploadRun
import eu.studio742.imago.core.data.SharedUploadSummary
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import eu.studio742.imago.feature.library.resources.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.jetbrains.compose.resources.getPluralString

/**
 * Sends to Immich what another app shared with IMAGO, with the app closed.
 *
 * It waits for a network before starting. With no network halfway, or with the server down, it tries
 * again later from the file where it stopped; after [MAX_ATTEMPTS] it gives up on what is left and
 * says how many were not sent.
 */
class SharedUploadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val directory = inputData.getString(KEY_DIRECTORY)?.let(::File)
        val queue = directory?.let(SharedUploadQueue::open) ?: run {
            // An unreadable manifest cannot be recovered, and the cleanup of abandoned copies does not touch it.
            directory?.deleteRecursively()
            return Result.failure(errorData(appString(Res.string.library_share_failed)))
        }
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SharedUploadEntryPoint::class.java)
        val libraryName = entryPoint.configuration().source(queue.libraryId).name
        val total = queue.batch.items.size
        texts = NotificationTexts(
            channel = appString(Res.string.library_share_channel),
            title = appString(Res.string.library_share_sending, libraryName),
            progress = (0..total).map { getPluralString(Res.plurals.library_share_progress, total, it, total) },
        )
        startForeground(total - queue.pending.size, total)
        val run = entryPoint.uploader().upload(queue) { completed, all ->
            setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to all))
            startForeground(completed, all)
        }
        return when (run) {
            is SharedUploadRun.Finished -> finish(queue, run.summary, libraryName)
            is SharedUploadRun.RetryLater ->
                if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry()
                else {
                    queue.giveUp()
                    finish(queue, queue.summary, libraryName)
                }
            is SharedUploadRun.Stopped -> {
                // A refused key or a disconnected library are not solved by trying again; what was
                // missing stays unsent, and the notification says why.
                val message = run.cause.toUiText(Res.string.library_share_failed).resolveNow()
                notifyResult(appString(Res.string.library_share_failed), message)
                queue.discard()
                Result.failure(errorData(message))
            }
        }
    }

    private suspend fun finish(queue: SharedUploadQueue, summary: SharedUploadSummary, libraryName: String): Result {
        val text = summary.describe(libraryName).map { it.resolveNow() }.joinToString(SUMMARY_SEPARATOR)
        notifyResult(appString(if (summary.failed == 0) Res.string.library_share_finished else Res.string.library_share_failed), text)
        queue.discard()
        return Result.success(
            workDataOf(
                KEY_UPLOADED to summary.uploaded,
                KEY_DUPLICATES to summary.duplicates,
                KEY_FAILED to summary.failed,
            ),
        )
    }

    /**
     * In an upload that started with the app open this puts the work in the foreground. On a retry,
     * with the app in the background, Android 12+ may refuse; the upload goes on anyway, just without
     * the progress notification.
     */
    private suspend fun startForeground(completed: Int, total: Int) {
        try {
            setForeground(foregroundInfo(completed, total))
        } catch (refused: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is an IllegalStateException.
        }
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    private class NotificationTexts(val channel: String, val title: String, val progress: List<String>)

    private lateinit var texts: NotificationTexts

    /** The notification manager, with the channel created — creating it again changes nothing. */
    private fun notifications(): NotificationManager =
        applicationContext.getSystemService(NotificationManager::class.java).also {
            it.createNotificationChannel(NotificationChannel(CHANNEL_ID, texts.channel, NotificationManager.IMPORTANCE_LOW))
        }

    private fun foregroundInfo(completed: Int, total: Int): ForegroundInfo {
        notifications()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(texts.title)
            .setContentText(texts.progress.getOrNull(completed) ?: "$completed / $total")
            .setProgress(total, completed, completed == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(progressNotificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyResult(title: String, text: String) {
        val manager = notifications()
        // Without the notification permission the system discards it anyway; the upload sheet, if it
        // is still open, shows the same summary.
        if (!manager.areNotificationsEnabled()) return
        val open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(progressNotificationId() + 1, notification)
    }

    /** Each upload with its own notification: two uploads in a row do not write over each other. */
    private fun progressNotificationId(): Int = NOTIFICATION_BASE + (id.hashCode() and 0xFFFF) * 2

    companion object {
        const val KEY_DIRECTORY = "directory"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_DUPLICATES = "duplicates"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"
        const val SUMMARY_SEPARATOR = " · "
        private const val CHANNEL_ID = "shared_uploads"
        private const val NOTIFICATION_BASE = 0x7420000
        private const val MAX_ATTEMPTS = 12

        /** Hands an upload's folder to WorkManager; from here on it is its owner. */
        fun enqueue(context: Context, directory: File): UUID {
            val request = OneTimeWorkRequestBuilder<SharedUploadWorker>()
                .setInputData(workDataOf(KEY_DIRECTORY to directory.absolutePath))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}

/** The lines of an upload's summary: how many went up, how many were already there, how many failed. */
internal fun SharedUploadSummary.describe(libraryName: String): List<UiText> = buildList {
    if (uploaded > 0 || total == 0) add(uiPlural(Res.plurals.library_share_result_sent, uploaded, uploaded, libraryName))
    if (duplicates > 0) add(uiPlural(Res.plurals.library_share_result_duplicates, duplicates, duplicates))
    if (failed > 0) add(uiPlural(Res.plurals.library_share_result_failed, failed, failed))
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SharedUploadEntryPoint {
    fun configuration(): ConfigurationRepository
    fun uploader(): SharedMediaUploader
}
