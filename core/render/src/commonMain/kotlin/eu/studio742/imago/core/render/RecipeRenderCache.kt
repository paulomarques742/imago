package eu.studio742.imago.core.render

import coil3.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path

/**
 * Photos with their recipe already applied, kept on disk between launches.
 *
 * Coil keeps the downloaded image on disk, but a transformation's result only in memory. Every time an
 * edited photo dropped out of the memory cache — opening a few previews in the detail was enough — the
 * grid and the detail ran the whole recipe again on the CPU, and in the meantime showed a blank tile or
 * the blurry placeholder. Here the result is decoded instead of rendered again.
 *
 * The key ties the result to the recipe's save and to the pixels it was rendered over: a thumbnail
 * and a preview of the same photo are two entries, and a source that changed under the same recipe
 * misses instead of showing the old one. Nothing is kept until [install] is called.
 */
object RecipeRenderCache {
    @Volatile private var cache: DiskCache? = null

    // One writer: the encodes are what costs, and a burst of tiles must not take every core from the
    // renders the screen is waiting for.
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Keeps the renders in [directory], dropping the least recently used beyond [maxSizeBytes]. */
    fun install(directory: Path, maxSizeBytes: Long) {
        cache = DiskCache.Builder().directory(directory).maxSizeBytes(maxSizeBytes).build()
    }

    /** Back to keeping nothing; for tests, which share the process. */
    internal fun uninstall() {
        cache = null
    }

    /**
     * The entry for [transformationKey] rendered over a [width] × [height] source.
     *
     * [pixel] reads the source's ARGB at a point; a grid of them stands for the content, so that two
     * sources of the same size are not taken for each other.
     */
    fun key(transformationKey: String, width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): String {
        var hash = FNV_OFFSET
        for (row in 0 until FINGERPRINT_GRID) {
            val y = ((2 * row + 1) * height) / (2 * FINGERPRINT_GRID)
            for (column in 0 until FINGERPRINT_GRID) {
                val x = ((2 * column + 1) * width) / (2 * FINGERPRINT_GRID)
                hash = (hash xor (pixel(x, y).toLong() and 0xFFFFFFFFL)) * FNV_PRIME
            }
        }
        return "$FORMAT_VERSION:$transformationKey:${width}x$height:${hash.toULong().toString(16)}"
    }

    /**
     * The entry for a RAW developed with [transformationKey] at [side] pixels.
     *
     * A RAW is developed inside Coil's decoder, before there is a bitmap to sample, and the develop is
     * what costs — so the key has to be known without it. The file stands for its content by its length
     * and three samples of its bytes (start, middle, end), which is enough to tell one original from
     * another and costs three reads.
     */
    fun rawKey(transformationKey: String, file: java.io.File, side: Int): String {
        var hash = FNV_OFFSET
        java.io.RandomAccessFile(file, "r").use { source ->
            val length = source.length()
            val sample = ByteArray(RAW_SAMPLE_BYTES)
            for (fraction in 0..2) {
                source.seek(((length - sample.size).coerceAtLeast(0L) * fraction) / 2)
                val read = source.read(sample).coerceAtLeast(0)
                for (index in 0 until read) hash = (hash xor (sample[index].toLong() and 0xFFL)) * FNV_PRIME
            }
            return "$FORMAT_VERSION:raw:$transformationKey:$side:$length:${hash.toULong().toString(16)}"
        }
    }

    /** The encoded render under [key], or null when there is none or it cannot be read. */
    fun read(key: String): ByteArray? {
        val disk = cache ?: return null
        return runCatching {
            disk.openSnapshot(key)?.use { snapshot -> disk.fileSystem.read(snapshot.data) { readByteArray() } }
        }.getOrNull()
    }

    /**
     * Stores what [encode] returns under [key], off the caller's thread: the render is already on its
     * way to the screen, and the disk can wait. A failure only means the next view renders again.
     */
    fun write(key: String, encode: () -> ByteArray?): Job? {
        val disk = cache ?: return null
        return writes.launch {
            runCatching {
                val bytes = encode() ?: return@runCatching
                val editor = disk.openEditor(key) ?: return@runCatching
                try {
                    disk.fileSystem.write(editor.data) { write(bytes) }
                    editor.commit()
                } catch (failure: Exception) {
                    editor.abort()
                    throw failure
                }
            }
        }
    }

    // Raised when the encoding changes, so that old entries miss instead of decoding wrong.
    private const val FORMAT_VERSION = "render1"
    private const val FINGERPRINT_GRID = 8
    private const val RAW_SAMPLE_BYTES = 4096
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
}
