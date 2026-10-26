package eu.studio742.imago

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import dagger.hilt.android.HiltAndroidApp
import okio.Path.Companion.toOkioPath
import eu.studio742.imago.core.sync.AndroidSync
import eu.studio742.imago.core.sync.SyncEngine
import javax.inject.Inject
import eu.studio742.imago.core.data.observeDiagnosticVersions
import kotlinx.coroutines.launch

@HiltAndroidApp
class ImmichRoomApplication : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var syncEngine: SyncEngine
    @Inject lateinit var configuration: eu.studio742.imago.core.data.ConfigurationRepository

    override fun onCreate() {
        super.onCreate()
        eu.studio742.imago.feature.library.AndroidDiagnostics.start(this, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // Without an account the engine waits; with one, it sends and receives while the app is
        // alive, and WorkManager finishes the upload when the network comes back.
        AndroidSync.start(this, syncEngine)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            configuration.observeDiagnosticVersions()
        }
    }

    // Coil 3's default cache strategy ignores the server's headers, as `respectCacheHeaders(false)`
    // did in Coil 2: Immich thumbnails stay on disk.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(eu.studio742.imago.core.render.RawPhotoDecoder.Factory())
            add(LibraryImageInterceptor())
            // The shared client, not Coil's own: what the connection needs (headers, certificates)
            // is configured on it.
            add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { eu.studio742.imago.core.immich.ImmichHttp.client }))
            add(LocalVideoThumbnailFetcher.Factory(this@ImmichRoomApplication))
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(500L * 1024L * 1024L)
                .build()
        }
        .build()
}
