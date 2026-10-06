package eu.studio742.imago.core.data

import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.immich.ImmichApiException
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.messageName
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A photo or video received from another app. [file] is the name of the copy inside the upload's folder. */
@Serializable
data class SharedUploadItem(
    val file: String,
    val fileName: String,
    val mimeType: String,
    val createdAt: String,
    val outcome: SharedUploadOutcome? = null,
)

enum class SharedUploadOutcome { UPLOADED, DUPLICATE, FAILED }

@Serializable
data class SharedUploadBatch(val libraryId: String, val items: List<SharedUploadItem>)

data class SharedUploadSummary(val uploaded: Int, val duplicates: Int, val failed: Int) {
    val total: Int get() = uploaded + duplicates + failed
}

/**
 * An upload of files received from another app, halfway or not yet started.
 *
 * It lives in a folder of its own: the copies of the files and a manifest saying which library they
 * go to and how each one went. The copies exist because the permission over what another app shares
 * ends when the activity that received it closes, and the upload ends long after that. The manifest
 * is rewritten after every file: an interrupted and repeated job does not send again what already
 * went up.
 */
class SharedUploadQueue private constructor(val directory: File, batch: SharedUploadBatch) {
    var batch: SharedUploadBatch = batch
        private set

    val libraryId: String get() = batch.libraryId

    /** The ones that have no outcome yet, with each one's position in the upload. */
    val pending: List<IndexedValue<SharedUploadItem>>
        get() = batch.items.withIndex().filter { it.value.outcome == null }

    val summary: SharedUploadSummary
        get() = batch.items.mapNotNull { it.outcome }.let { outcomes ->
            SharedUploadSummary(
                uploaded = outcomes.count { it == SharedUploadOutcome.UPLOADED },
                duplicates = outcomes.count { it == SharedUploadOutcome.DUPLICATE },
                failed = outcomes.count { it == SharedUploadOutcome.FAILED },
            )
        }

    fun fileOf(item: SharedUploadItem): File = File(directory, item.file)

    fun record(index: Int, outcome: SharedUploadOutcome) {
        batch = batch.copy(items = batch.items.mapIndexed { i, item -> if (i == index) item.copy(outcome = outcome) else item })
        write(directory, batch)
        // With an outcome, the copy is no longer of any use; a large video does not stay taking up space.
        fileOf(batch.items[index]).delete()
    }

    /** Gives up on what is left: it counts as failed, so the summary says how many did not go up. */
    fun giveUp() {
        pending.forEach { record(it.index, SharedUploadOutcome.FAILED) }
    }

    fun discard() {
        directory.deleteRecursively()
    }

    companion object {
        const val MANIFEST = "batch.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun create(directory: File, batch: SharedUploadBatch): SharedUploadQueue {
            write(directory, batch)
            return SharedUploadQueue(directory, batch)
        }

        /** Null if the folder no longer exists or the manifest cannot be read — the upload cannot go on. */
        fun open(directory: File): SharedUploadQueue? {
            val manifest = File(directory, MANIFEST).takeIf { it.isFile } ?: return null
            val batch = runCatching { json.decodeFromString<SharedUploadBatch>(manifest.readText()) }.getOrNull() ?: return null
            return SharedUploadQueue(directory, batch)
        }

        private fun write(directory: File, batch: SharedUploadBatch) {
            // Written alongside and swapped: a process killed halfway does not leave a cut manifest.
            val temporary = File(directory, "$MANIFEST.tmp")
            temporary.writeText(json.encodeToString(SharedUploadBatch.serializer(), batch))
            check(temporary.renameTo(File(directory, MANIFEST)) || run {
                File(directory, MANIFEST).delete() && temporary.renameTo(File(directory, MANIFEST))
            }) { "Could not save the upload manifest in $directory." }
        }
    }
}

/**
 * The folder where uploads received from other apps live, one per subfolder.
 *
 * A subfolder without a manifest is a copy that was never handed to the upload — the person gave
 * up, or the process died halfway through copying. Those are cleaned after a day.
 */
class SharedUploadStaging(private val root: File) {
    fun newDirectory(): File = File(root, UUID.randomUUID().toString()).apply { mkdirs() }

    fun cleanAbandoned(nowMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long = ABANDONED_AFTER_MILLIS) {
        root.listFiles()?.forEach { directory ->
            val abandoned = directory.isDirectory &&
                !File(directory, SharedUploadQueue.MANIFEST).exists() &&
                nowMillis - directory.lastModified() > maxAgeMillis
            if (abandoned) directory.deleteRecursively()
        }
    }

    private companion object {
        const val ABANDONED_AFTER_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** How a pass through the upload ended. */
sealed interface SharedUploadRun {
    /** Every file has an outcome. */
    data class Finished(val summary: SharedUploadSummary) : SharedUploadRun

    /** No network or server down: the remaining files stay waiting, and trying again is worth it. */
    data class RetryLater(val cause: Throwable) : SharedUploadRun

    /** Trying again changes nothing — the key was refused, the library was disconnected. */
    data class Stopped(val cause: Throwable) : SharedUploadRun
}

/** Sends to Immich what another app shared with IMAGO. */
class SharedMediaUploader @Inject constructor(
    private val configuration: ConfigurationRepository,
    private val api: ImmichApi,
) {
    suspend fun upload(
        queue: SharedUploadQueue,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): SharedUploadRun {
        val source = configuration.source(queue.libraryId)
        if (source.isDevice || !source.isConnected || source.serverUrl == null) {
            return SharedUploadRun.Stopped(UserMessageException(UserMessage.LIBRARY_DISCONNECTED, listOf(source.messageName())))
        }
        val connection = source.connection()
        val total = queue.batch.items.size
        for ((index, item) in queue.pending) {
            onProgress(total - queue.pending.size, total)
            val file = queue.fileOf(item)
            if (!file.isFile) {
                queue.record(index, SharedUploadOutcome.FAILED)
                continue
            }
            val result = try {
                api.uploadAsset(connection, file, item.fileName, item.mimeType, item.createdAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ImmichApiException.Connection) {
                return SharedUploadRun.RetryLater(error)
            } catch (error: ImmichApiException.Server) {
                // A 5xx is the server, and passes; a 4xx is this file — a format Immich does not
                // accept — and repeating does not change it. The others go on.
                if (error.status >= 500) return SharedUploadRun.RetryLater(error)
                queue.record(index, SharedUploadOutcome.FAILED)
                continue
            } catch (error: ImmichApiException) {
                return SharedUploadRun.Stopped(error)
            } catch (error: Exception) {
                queue.record(index, SharedUploadOutcome.FAILED)
                continue
            }
            queue.record(index, if (result.status == DUPLICATE_STATUS) SharedUploadOutcome.DUPLICATE else SharedUploadOutcome.UPLOADED)
        }
        onProgress(total, total)
        return SharedUploadRun.Finished(queue.summary)
    }

    private companion object {
        const val DUPLICATE_STATUS = "duplicate"
    }
}
