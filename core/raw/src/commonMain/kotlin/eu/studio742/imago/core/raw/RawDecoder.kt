package eu.studio742.imago.core.raw

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Scene-linear sRGB after camera white balance and orientation, including values above white.
 * [baselineExposure] is the DNG's BaselineExposure in stops, 0 when the file has none, whether or not
 * the decode applied it. [xyzToDeveloped] (row-major 3×3, up to a scale) takes a light's CIE XYZ to the
 * colour it has in this development, as-shot white balance included; null when the camera's colour
 * matrix is unknown or the sensor is monochrome.
 */
class RawImage(
    val width: Int,
    val height: Int,
    val rgb: ShortArray,
    val cameraMake: String,
    val cameraModel: String,
    val baselineExposure: Float = 0f,
    val xyzToDeveloped: FloatArray? = null,
) {
    init {
        require(width > 0 && height > 0)
        require(rgb.size == Math.multiplyExact(Math.multiplyExact(width, height), 3))
    }

    fun sample(index: Int): Float = decodeHalf(rgb[index])
}

/** A technical failure; callers translate it at the UI boundary. */
class RawDecodeException(val code: Int) : RuntimeException("LibRaw decode failed ($code).")

data class RawInfo(val width: Int, val height: Int, val cameraMake: String, val cameraModel: String)

/** LibRaw's `highlight` of engine 1: unclipped, gains normalised by their maximum. */
const val LEGACY_HIGHLIGHT = 1

/** No extension or brand allowlist: LibRaw identifies the bytes of the original. */
object RawDecoder {
    val version: String get() = NativeRaw.version()
    val supportedCameras: List<String> get() = NativeRaw.cameras().toList()

    /** Header-only recognition for indexing; never unpacks or demosaics the sensor. */
    fun identify(file: File): RawInfo? = RawThreads.read { NativeRaw.identify(file.absolutePath) }

    /**
     * Null means that this LibRaw build does not recognize the input. A recognized but damaged
     * image throws [RawDecodeException]. Run on a worker thread; interrupt it to cancel decoding.
     * Native file I/O keeps large originals out of the JVM heap.
     *
     * [baselineExposure] scales the developed values by the DNG's BaselineExposure, which LibRaw only
     * reads; whether to is the recipe's choice, not the decoder's.
     */
    fun decode(file: File, halfSize: Boolean = false, baselineExposure: Boolean = false, highlight: Int = LEGACY_HIGHLIGHT): RawImage? {
        return RawDecodeQueue.run { RawThreads.decode { NativeRaw.decode(file.absolutePath, halfSize, 0, baselineExposure, highlight) } }
    }

    /**
     * The JPEG the camera rendered and stored inside the original, or null when the file has none
     * (or keeps it as a bitmap). Reads the header and the preview only, never the sensor.
     */
    fun embeddedJpeg(file: File): ByteArray? = RawThreads.read { NativeRaw.embeddedJpeg(file.absolutePath) }

    /** Bounds the JVM buffer before allocation, retaining linear precision and highlight headroom. */
    fun decodePreview(file: File, maxSide: Int, baselineExposure: Boolean = false, highlight: Int = LEGACY_HIGHLIGHT): RawImage? {
        require(maxSide > 0)
        return RawDecodeQueue.run { RawThreads.decode { NativeRaw.decode(file.absolutePath, true, maxSide, baselineExposure, highlight) } }
    }
}

/**
 * The only threads LibRaw runs on. Built with OpenMP, its runtime adopts every thread that calls it and
 * keeps a team of workers for each; the app's own threads come and go, and on Android a call from a
 * fresh one crashed inside the runtime (SIGSEGV in `__kmpc_critical_with_hint`, under LibRaw's
 * allocator). Threads that live as long as the process are all it ever sees. Reading headers has its
 * own, so the grid is not held behind a development that takes seconds.
 *
 * Interrupting the caller interrupts the work on its thread, which is what the native side checks.
 */
internal object RawThreads {
    private val decoding = permanent("imago-raw-decode", 1)
    private val reading = permanent("imago-raw-read", 2)

    fun <T> decode(block: () -> T): T = call(decoding, block)

    fun <T> read(block: () -> T): T = call(reading, block)

    private fun <T> call(executor: ExecutorService, block: () -> T): T {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        val task = executor.submit(Callable(block))
        try {
            return task.get()
        } catch (interrupted: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw CancellationException("RAW work interrupted.").apply { initCause(interrupted) }
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private fun permanent(name: String, threads: Int): ExecutorService {
        val count = AtomicInteger()
        // Core threads of a fixed pool never time out: none of them ends while the process runs.
        return Executors.newFixedThreadPool(threads) { work ->
            Thread(work, "$name-${count.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}

/** LibRaw's sensor and demosaic buffers must not accumulate across concurrent image requests. */
internal object RawDecodeQueue {
    private val lock = ReentrantLock(true)

    fun <T> run(block: () -> T): T {
        try {
            lock.lockInterruptibly()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("RAW decoding interrupted while waiting.").apply { initCause(interrupted) }
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

internal object NativeRaw {
    init { loadRawLibrary() }
    external fun version(): String
    external fun cameras(): Array<String>
    external fun identify(path: String): RawInfo?
    external fun embeddedJpeg(path: String): ByteArray?
    external fun decode(path: String, halfSize: Boolean, maxSide: Int, baselineExposure: Boolean, highlight: Int): RawImage?
}

internal expect fun loadRawLibrary()

