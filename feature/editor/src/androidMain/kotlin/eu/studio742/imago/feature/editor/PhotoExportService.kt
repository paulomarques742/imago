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
import coil3.PlatformContext
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.editor_cancel
import eu.studio742.imago.feature.editor.resources.editor_export_failed
import eu.studio742.imago.feature.editor.resources.photo_export_notification_channel
import eu.studio742.imago.feature.editor.resources.photo_export_notification_done
import eu.studio742.imago.feature.editor.resources.photo_export_notification_title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal actual fun photoExportStarted(context: PlatformContext) = PhotoExportService.start(context)

/**
 * Keeps a photo export going after the app is left, with a notification that says which phase it is in
 * and can cancel it. The work is [PhotoExports]'; this holds the process alive, as [ReelExportService]
 * does for the video, and stops when the export does.
 */
class PhotoExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var texts: Texts? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            PhotoExports.cancel()
            return START_NOT_STICKY
        }
        // The notification has to be up within seconds of starting: a handful of small reads, done here.
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
                var lastPhase: String? = null
                PhotoExports.state.takeWhile { it.running }.collect { state ->
                    val phase = state.phase?.resolveNow()
                    if (phase != lastPhase) {
                        lastPhase = phase
                        getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification(texts, phase))
                    }
                }
                finish(texts, PhotoExports.state.value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun finish(texts: Texts, state: PhotoExportState) {
        val manager = getSystemService(NotificationManager::class.java)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        when {
            state.error != null -> manager.notify(DONE_ID, resultNotification(texts.failed, state.error.resolveNow(), android.R.drawable.stat_notify_error))
            state.message != null -> manager.notify(DONE_ID, resultNotification(texts.done, state.message.resolveNow(), android.R.drawable.stat_sys_upload_done))
            // Cancelled, or a share that said nothing: nothing to tell.
        }
        watching = null
        stopSelf()
    }

    private fun progressNotification(texts: Texts, phase: String?): Notification {
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, PhotoExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(texts.title)
            .apply { if (phase != null) setContentText(phase) }
            // A photo's phases have no fraction to show, only what is being done.
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, texts.cancel, cancel)
            .build()
    }

    private fun resultNotification(title: String, message: String, icon: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
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

    private class Texts(val channel: String, val title: String, val done: String, val failed: String, val cancel: String)

    private suspend fun loadTexts() = Texts(
        channel = appString(Res.string.photo_export_notification_channel),
        title = appString(Res.string.photo_export_notification_title),
        done = appString(Res.string.photo_export_notification_done),
        failed = appString(Res.string.editor_export_failed),
        cancel = appString(Res.string.editor_cancel),
    )

    companion object {
        private const val ACTION_CANCEL = "eu.studio742.imago.feature.editor.CANCEL_PHOTO_EXPORT"
        private const val CHANNEL_ID = "photo_exports"
        private const val PROGRESS_ID = 7437
        private const val DONE_ID = 7438

        /** Starts the service from the app, while it is on screen. */
        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, PhotoExportService::class.java))
        }
    }
}
