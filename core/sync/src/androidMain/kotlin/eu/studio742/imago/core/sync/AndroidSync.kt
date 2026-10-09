package eu.studio742.imago.core.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * What belongs to Android in the sync triggers: coming back to the foreground — when the last run
 * is not recent — and, without a network, a WorkManager job that runs when it returns, even with the
 * app closed.
 */
object AndroidSync {
    private const val WORK = "imago-sync"

    fun start(context: Context, engine: SyncEngine) {
        engine.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = engine.syncIfStale()
        })
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            engine.status
                .map { it.signedIn && it.offline && it.pending > 0 }
                .distinctUntilChanged()
                .filter { it }
                .collect { enqueue(appContext) }
        }
    }

    private fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun syncEngine(): SyncEngine
}

/** A run when the network returns. Without an account, or after several attempts, it gives up. */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val engine = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java).syncEngine()
        return when {
            engine.syncNow() -> Result.success()
            !engine.status.value.signedIn || runAttemptCount >= MAX_ATTEMPTS -> Result.success()
            else -> Result.retry()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
