package eu.studio742.imago.core.data

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Large files the app made or fetched in this session, kept for the next time it needs the same one:
 * a server's originals and the full-resolution exports.
 *
 * Opening a RAW in the editor downloaded the whole original; exporting it downloaded it again, and so
 * did saving or sharing it. Sending an edit to Immich and then saving it to the phone rendered it twice.
 * Each of those now asks here first.
 *
 * Nothing outlives the session: [install] empties the folder at every launch, and [clear] when a library
 * is disconnected. Within a session the folder stays under its limit, the least recently used going
 * first. The callers still get a file of their own, which they delete as before.
 */
object WorkingCopies {
    @Volatile private var directory: File? = null
    @Volatile private var maxBytes = 0L
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val housekeeping = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Keeps the copies in [folder], emptied now: what an earlier session left is not reused. */
    fun install(folder: File, limitBytes: Long) {
        folder.mkdirs()
        folder.listFiles()?.forEach { it.deleteRecursively() }
        maxBytes = limitBytes
        directory = folder
    }

    /** Back to keeping nothing; for tests, which share the process. */
    internal fun uninstall() {
        directory = null
    }

    /**
     * A new empty file of the caller's own, beside the copies, for [fill] to fill; null when nothing is
     * installed. A file left behind by a process that died is swept at the next launch.
     */
    fun scratchFile(suffix: String): File? =
        directory?.let { File.createTempFile("imago-copy-", suffix, it.parentFile) }

    /** Deletes every copy, off the caller's thread: a library was disconnected. */
    fun clear() {
        val folder = directory ?: return
        housekeeping.launch { folder.listFiles()?.forEach { it.delete() } }
    }

    /**
     * Fills [destination] with the file kept under [key], or with what [make] writes into the file it is
     * given when there is none — and keeps that for next time. Two callers asking for the same key at
     * once share one [make].
     */
    suspend fun fill(key: String, destination: File, make: suspend (File) -> Unit) {
        val folder = directory ?: return make(destination)
        withContext(Dispatchers.IO) {
            locks.getOrPut(key) { Mutex() }.withLock {
                val name = nameOf(key)
                val kept = File(folder, name)
                if (kept.isFile) {
                    kept.setLastModified(System.currentTimeMillis())
                    kept.copyTo(destination, overwrite = true)
                    return@withLock
                }
                val part = File(folder, "$name.part")
                try {
                    make(part)
                } catch (failure: Throwable) {
                    part.delete()
                    throw failure
                }
                // Larger than the whole allowance, or a folder that would not take it: the caller still
                // gets its file, and nothing is kept.
                if (part.length() > maxBytes || !part.renameTo(kept)) {
                    moveTo(part, destination)
                    return@withLock
                }
                trim(keep = kept)
                kept.copyTo(destination, overwrite = true)
            }
        }
    }

    /** The oldest copies out until the folder fits its limit; [keep] is the one just made. */
    private fun trim(keep: File) {
        val folder = directory ?: return
        val copies = folder.listFiles { file -> file.isFile && !file.name.endsWith(".part") }.orEmpty()
            .sortedBy(File::lastModified)
        var total = copies.sumOf(File::length)
        for (copy in copies) {
            if (total <= maxBytes) break
            if (copy == keep) continue
            val size = copy.length()
            if (copy.delete()) total -= size
        }
    }

    private fun moveTo(source: File, destination: File) {
        if (source.renameTo(destination)) return
        source.copyTo(destination, overwrite = true)
        source.delete()
    }

    /** The key hashed: asset ids and recipes have characters a file name cannot. */
    private fun nameOf(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
}
