package eu.studio742.imago.core.raw

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

/** Scene-linear sRGB after camera white balance and orientation, including values above white. */
class RawImage(
    val width: Int,
    val height: Int,
    val rgb: ShortArray,
    val cameraMake: String,
    val cameraModel: String,
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

/** No extension or brand allowlist: LibRaw identifies the bytes of the original. */
object RawDecoder {
    val version: String get() = NativeRaw.version()
    val supportedCameras: List<String> get() = NativeRaw.cameras().toList()

    /** Header-only recognition for indexing; never unpacks or demosaics the sensor. */
    fun identify(file: File): RawInfo? {
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        return NativeRaw.identify(file.absolutePath)
    }

    /**
     * Null means that this LibRaw build does not recognize the input. A recognized but damaged
     * image throws [RawDecodeException]. Run on a worker thread; interrupt it to cancel decoding.
     * Native file I/O keeps large originals out of the JVM heap.
     */
    fun decode(file: File, halfSize: Boolean = false): RawImage? {
        return RawDecodeQueue.run { NativeRaw.decode(file.absolutePath, halfSize, 0) }
    }

    /** Bounds the JVM buffer before allocation, retaining linear precision and highlight headroom. */
    fun decodePreview(file: File, maxSide: Int): RawImage? {
        require(maxSide > 0)
        return RawDecodeQueue.run { NativeRaw.decode(file.absolutePath, true, maxSide) }
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
    external fun decode(path: String, halfSize: Boolean, maxSide: Int): RawImage?
}

internal expect fun loadRawLibrary()

