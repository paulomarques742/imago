package eu.studio742.imago.core.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The moments when the active address may have stopped being the right one.
 *
 * The network changed (one left home), the app came back to the foreground (maybe hours later) or a
 * request failed because it did not reach the server. None of these signals chooses anything: it
 * only wakes the configuration, which is what knows which addresses there are.
 */
@Singleton
class EndpointTriggers @Inject constructor(@ApplicationContext context: Context) {
    private val signals = MutableSharedFlow<EndpointTrigger>(extraBufferCapacity = 8)
    val events: Flow<EndpointTrigger> = signals.asSharedFlow()

    fun connectionFailed() { signals.tryEmit(EndpointTrigger.CONNECTION_FAILED) }

    init {
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { signals.tryEmit(EndpointTrigger.NETWORK_CHANGED) }
                    override fun onLost(network: Network) { signals.tryEmit(EndpointTrigger.NETWORK_CHANGED) }
                },
            )
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var started = 0
                override fun onActivityStarted(activity: Activity) {
                    if (started++ == 0) signals.tryEmit(EndpointTrigger.FOREGROUND)
                }
                override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}

