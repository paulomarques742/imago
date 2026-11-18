package eu.studio742.imago.core.ml

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticEvent
import eu.studio742.imago.core.model.diagnostics.DiagnosticFailure
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.DiagnosticOutcome
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.model.diagnostics.diagnosticOutcome
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The mask models from a public bucket (`ml-models`, 20261116120000_ml_models.sql): `manifest.json` names each
 * model's file, size and SHA-256, and a file only lands in [directory] once it matches them. Files are never
 * replaced: a new version of a model is a new folder, because recipes name the version they used.
 */
class HttpModelSource(
    private val directory: File,
    bucketUrl: String,
    client: OkHttpClient,
) : ModelSource {
    private val base = bucketUrl.trimEnd('/') + "/"
    private val http = client.newBuilder().retryOnConnectionFailure(true).build()

    override fun localPath(model: MaskModel): String? = target(model).takeIf { it.isFile }?.path

    @Synchronized
    override fun download(model: MaskModel, progress: (Long, Long) -> Unit): String {
        localPath(model)?.let { return it }
        val start = System.nanoTime()
        try {
            val entry = manifestEntry(model)
            val target = target(model)
            val folder = checkNotNull(target.parentFile)
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("No folder for the model")
            val partial = File(folder, target.name + ".part")
            try {
                fetch(entry.file) { response ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    response.body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                received += read
                                if (received > entry.size) throw ModelIntegrityException()
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                progress(received, entry.size)
                            }
                        }
                    }
                    if (received != entry.size || digest.digest().toHex() != entry.sha256) throw ModelIntegrityException()
                }
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                partial.delete()
            }
            LocalDiagnostics.record {
                DiagnosticEvent(DiagnosticArea.EDITOR, DiagnosticOutcome.SUCCESS, DiagnosticOperation.MASK_MODEL, durationMs = millisSince(start))
            }
            return target.path
        } catch (error: Exception) {
            LocalDiagnostics.record {
                val outcome = when (error) {
                    is ModelHttpException -> DiagnosticOutcome.HTTP_ERROR
                    is ModelIntegrityException -> DiagnosticOutcome.INVALID_RESPONSE
                    else -> diagnosticOutcome(error)
                }
                DiagnosticEvent(
                    DiagnosticArea.EDITOR, outcome, DiagnosticOperation.MASK_MODEL,
                    status = (error as? ModelHttpException)?.status,
                    durationMs = millisSince(start),
                    failure = DiagnosticFailure.from(error),
                )
            }
            throw UserMessageException(UserMessage.MASK_MODEL_DOWNLOAD_FAILED, cause = error)
        }
    }

    private fun target(model: MaskModel) = File(directory, "${model.id}/${model.version}/${model.fileName}")

    private fun manifestEntry(model: MaskModel): ManifestEntry {
        val text = fetch("manifest.json") { it.body.string() }
        val models = try {
            Json.parseToJsonElement(text).jsonObject["models"]?.jsonArray.orEmpty()
        } catch (_: IllegalArgumentException) {
            throw ModelIntegrityException()
        }
        val entry = models.map { it.jsonObject }.firstOrNull {
            it.text("id") == model.id && it["version"]?.jsonPrimitive?.intOrNull == model.version
        } ?: throw ModelIntegrityException()
        val file = entry.text("file")
        val size = entry["size"]?.jsonPrimitive?.longOrNull
        val sha256 = entry.text("sha256")
        // The manifest is data from the network: only a plain path inside the bucket, a sane size and a real digest.
        if (file == null || !SAFE_PATH.matches(file) || size == null || size !in 1..MAX_SIZE || sha256 == null || !SHA256.matches(sha256)) {
            throw ModelIntegrityException()
        }
        return ManifestEntry(file, size, sha256)
    }

    private fun <T> fetch(path: String, read: (Response) -> T): T =
        http.newCall(Request.Builder().url(base + path).build()).execute().use { response ->
            if (!response.isSuccessful) throw ModelHttpException(response.code)
            read(response)
        }

    private class ManifestEntry(val file: String, val size: Long, val sha256: String)
}

private class ModelHttpException(val status: Int) : IOException("HTTP $status")
private class ModelIntegrityException : IOException("The model does not match the manifest")

private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.contentOrNull
private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
private fun millisSince(start: Long) = (System.nanoTime() - start) / 1_000_000

// Folders and a file name, none of them "." or "..".
private val SAFE_PATH = Regex("[a-z0-9-]+(/(?!\\.{1,2}(/|$))[A-Za-z0-9._-]+)*")
private val SHA256 = Regex("[0-9a-f]{64}")
private const val MAX_SIZE = 64L * 1024 * 1024
