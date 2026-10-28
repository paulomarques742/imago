package eu.studio742.imago.feature.editor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.editor_cancel
import eu.studio742.imago.feature.editor.resources.reel_failed
import eu.studio742.imago.feature.editor.resources.reel_notification_channel
import eu.studio742.imago.feature.editor.resources.reel_notification_done
import eu.studio742.imago.feature.editor.resources.reel_notification_title
import eu.studio742.imago.feature.editor.resources.reel_share
import eu.studio742.imago.feature.editor.resources.reel_share_title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps the "Show your edit" video being made after the app is left, with a notification that shows
 * how far it is and can cancel it. The work itself is [ReelExports]'; this only holds the process
 * alive and tells the person, and stops when the export does.
 */
class ReelExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var texts: Texts? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ReelExports.cancel()
            return START_NOT_STICKY
        }
        // A foreground service must put its notification up within seconds of starting: the texts are a
        // handful of small reads, resolved here instead of after a hop to another thread.
        val texts = texts ?: runBlocking(Dispatchers.Default) { loadTexts() }.also { texts = it }
        createChannel(texts)
        ServiceCompat.startForeground(
            this,
            PROGRESS_ID,
            progressNotification(texts, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (watching == null) {
            watching = scope.launch {
                var lastPercent = -1
                ReelExports.state.takeWhile { it.progress != null }.collect { state ->
                    val percent = ((state.progress ?: 0f) * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        getSystemService(NotificationManager::class.java)
                            .notify(PROGRESS_ID, progressNotification(texts, state.progress))
                    }
                }
                finish(texts, ReelExports.state.value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun finish(texts: Texts, state: ReelExportState) {
        val manager = getSystemService(NotificationManager::class.java)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val saved = state.saved
        when {
            saved != null -> manager.notify(DONE_ID, doneNotification(texts, saved, saved.message.resolveNow()))
            state.error != null -> manager.notify(DONE_ID, failedNotification(texts, state.error.resolveNow()))
            // Cancelled: nothing to say.
        }
        watching = null
        stopSelf()
    }

    private fun progressNotification(texts: Texts, progress: Float?): Notification {
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, ReelExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(texts.title)
            .apply { if (progress != null) setContentText("${(progress * 100).toInt()}%") }
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, texts.cancel, cancel)
            .build()
    }

    private fun doneNotification(texts: Texts, saved: SavedReel, message: String): Notification {
        val location = saved.location.toUri()
        val view = PendingIntent.getActivity(
            this,
            1,
            Intent(Intent.ACTION_VIEW).setDataAndType(location, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val share = PendingIntent.getActivity(
            this,
            2,
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, location)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                texts.shareTitle,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(texts.done)
            .setContentText(message)
            .setContentIntent(view)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_share, texts.share, share)
            .build()
    }

    private fun failedNotification(texts: Texts, message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(texts.failed)
            .setContentText(message)
            .setContentIntent(launchApp())
            .setAutoCancel(true)
            .build()

    private fun launchApp(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun createChannel(texts: Texts) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, texts.channel, NotificationManager.IMPORTANCE_LOW))
    }

    private class Texts(
        val channel: String,
        val title: String,
        val done: String,
        val failed: String,
        val cancel: String,
        val share: String,
        val shareTitle: String,
    )

    private suspend fun loadTexts() = Texts(
        channel = appString(Res.string.reel_notification_channel),
        title = appString(Res.string.reel_notification_title),
        done = appString(Res.string.reel_notification_done),
        failed = appString(Res.string.reel_failed),
        cancel = appString(Res.string.editor_cancel),
        share = appString(Res.string.reel_share),
        shareTitle = appString(Res.string.reel_share_title),
    )

    companion object {
        private const val ACTION_CANCEL = "eu.studio742.imago.feature.editor.CANCEL_REEL"
        private const val CHANNEL_ID = "reel_exports"
        private const val PROGRESS_ID = 7427
        private const val DONE_ID = 7428

        /** Starts the service from the app, while it is on screen. */
        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ReelExportService::class.java))
        }
    }
}
