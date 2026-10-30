package eu.studio742.imago

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
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
    @Inject lateinit var library: eu.studio742.imago.core.data.LibraryRepository

    override fun onCreate() {
        super.onCreate()
        // Before anything is drawn: the grid's first edited tiles already look for their render here.
        eu.studio742.imago.core.render.RecipeRenderCache.install(
            cacheDir.resolve("recipe_renders").toOkioPath(), RECIPE_RENDER_CACHE_BYTES,
        )
        // Emptied here, at every launch: a session's originals and exports are not kept beyond it.
        eu.studio742.imago.core.data.WorkingCopies.install(cacheDir.resolve("originals"), WORKING_COPIES_BYTES)
        eu.studio742.imago.feature.library.AndroidDiagnostics.start(this, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        // Without an account the engine waits; with one, it sends and receives while the app is
        // alive, and WorkManager finishes the upload when the network comes back.
        AndroidSync.start(this, syncEngine)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            configuration.observeDiagnosticVersions()
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            CacheSweeper(cacheDir).sweep()
        }
    }

    // Coil 3's default cache strategy ignores the server's headers, as `respectCacheHeaders(false)`
    // did in Coil 2: Immich thumbnails stay on disk.
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(eu.studio742.imago.core.render.RawPhotoDecoder.Factory())
            add(LibraryImageInterceptor())
            // After it: it sets the memory cache key that this one adds to.
            add(eu.studio742.imago.core.render.LibraryRawInterceptor())
            // Before the network and the one that reads the RAW: a tile that was developed already is
            // answered from the cache. Then an edited RAW of the server comes from its original.
            add(eu.studio742.imago.core.render.CachedRawTileFetcher.Factory())
            add(eu.studio742.imago.core.render.LibraryRawFetcher.Factory { id, file -> library.downloadOriginal(id, file) })
            // The shared client, not Coil's own: what the connection needs (headers, certificates)
            // is configured on it.
            add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { eu.studio742.imago.core.immich.ImmichHttp.client }))
            add(LocalThumbnailFetcher.Factory(this@ImmichRoomApplication))
        }
        // Coil's default share was too small to outlive a visit to the detail: a few full-screen
        // previews pushed every grid tile out, and coming back to the grid loaded it all again. Bitmaps
        // live outside the Java heap, and Coil trims the cache when the system asks for memory.
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, MEMORY_CACHE_SHARE)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(500L * 1024L * 1024L)
                .build()
        }
        .build()

    private companion object {
        const val MEMORY_CACHE_SHARE = 0.4
        const val RECIPE_RENDER_CACHE_BYTES = 100L * 1024L * 1024L
        const val WORKING_COPIES_BYTES = 500L * 1024L * 1024L
    }
}
