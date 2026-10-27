package eu.studio742.imago.core.render

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.model.EditRecipe

/**
 * The recipe on the large images the library shows — the detail's preview — drawn by the editor's GPU
 * pipeline instead of the CPU engine.
 *
 * On the CPU a 1440 px preview took long enough for the blurry thumbnail under it to be what one looked
 * at; the GPU draws the same recipe the editor shows in a frame. Thumbnails stay on the CPU: they are
 * small, and a context for each would cost more than the render. The export never comes here.
 *
 * One context serves every preview, one at a time, and is let go once the previews stop coming, so it
 * does not hold the GPU's memory while the library is only being scrolled. A device that gives no
 * OpenGL ES 3 context is remembered, and everything goes back to the CPU.
 */
internal object GpuRecipePreviews {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var renderer: OffscreenRecipeRenderer? = null
    private var release: Job? = null
    @Volatile private var unavailable = false

    /** Whether [source] is large enough for the GPU to be worth it. */
    fun suits(source: Bitmap): Boolean =
        !unavailable && source.config != Bitmap.Config.HARDWARE && source.width.toLong() * source.height >= MIN_PIXELS

    /**
     * [recipe] over [source], framed and cropped as [renderRecipePreview] returns it; null when the GPU
     * could not draw it, and the caller renders on the CPU.
     */
    suspend fun render(source: Bitmap, recipe: EditRecipe): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            release?.cancel()
            try {
                val gpu = renderer ?: OffscreenRecipeRenderer(source).also { renderer = it }
                gpu.render(source, recipe.toRenderParameters(), maxOf(source.width, source.height))
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                // A context that failed once is closed and not tried again in this process: a driver
                // that cannot do it would fail on every preview, after making each one wait for it.
                renderer?.close()
                renderer = null
                unavailable = true
                null
            } finally {
                scheduleRelease()
            }
        }
    }

    private fun scheduleRelease() {
        release = scope.launch {
            delay(IDLE_RELEASE_MS)
            lock.withLock {
                renderer?.close()
                renderer = null
            }
        }
    }

    /** About a megapixel: the detail's previews are over it, the grid's thumbnails far under. */
    private const val MIN_PIXELS = 1_000_000L
    private const val IDLE_RELEASE_MS = 15_000L
}
