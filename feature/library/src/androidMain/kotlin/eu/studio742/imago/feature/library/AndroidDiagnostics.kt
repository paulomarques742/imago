package eu.studio742.imago.feature.library

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.studio742.imago.core.model.diagnostics.*
import java.io.File
import java.util.concurrent.TimeUnit

object AndroidDiagnostics {
    fun start(context: Context, appVersion: String) {
        val application = context.applicationContext
        // Older test builds scheduled forum checks; replies now refresh only in the foreground.
        WorkManager.getInstance(application).cancelUniqueWork("forum-replies")
        WorkManager.getInstance(application).cancelUniqueWork("forum-replies-now")
        application.getSystemService(android.app.NotificationManager::class.java).deleteNotificationChannel("forum_replies")
        LocalDiagnostics.transport = {
            val manager = application.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            when {
                capabilities == null -> DiagnosticTransport.OFFLINE
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> DiagnosticTransport.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DiagnosticTransport.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DiagnosticTransport.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DiagnosticTransport.ETHERNET
                else -> DiagnosticTransport.OTHER
            }
        }
        LocalDiagnostics.start(store(application), mapOf(
            "App" to appVersion, "Platform" to "Android", "OS" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Manufacturer" to Build.MANUFACTURER, "Model" to Build.MODEL,
        ))
        WorkManager.getInstance(application).enqueueUniquePeriodicWork("local-diagnostics-cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DiagnosticCleanupWorker>(1, TimeUnit.HOURS).build())
    }

    internal fun store(context: Context) = DiagnosticStore(File(context.noBackupFilesDir, "diagnostics"),
        reportsDirectory = File(context.cacheDir, "support-reports"))
}

class DiagnosticCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (LocalDiagnostics.store ?: AndroidDiagnostics.store(applicationContext)).cleanup()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
