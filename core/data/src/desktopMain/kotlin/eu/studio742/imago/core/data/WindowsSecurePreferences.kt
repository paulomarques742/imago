package eu.studio742.imago.core.data

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The configuration in a file encrypted with DPAPI: only the Windows user who saved it can read it.
 *
 * The whole file is one JSON object encrypted at once, and every save swaps it atomically — a crash
 * halfway leaves the previous version, never half of one. There is no plain-text fallback.
 */
class WindowsSecurePreferences(private val file: Path) : SecurePreferences {
    private var values: Map<String, String> = read()

    @Synchronized override fun getString(key: String): String? = values[key]

    @Synchronized override fun write(changes: Map<String, String?>): Boolean = runCatching {
        val next = values.toMutableMap()
        for ((key, value) in changes) if (value == null) next.remove(key) else next[key] = value
        val plain = JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
        Files.createDirectories(file.toAbsolutePath().parent)
        val temporary = Files.createTempFile(file.toAbsolutePath().parent, ".imago-", ".tmp")
        try {
            Files.write(temporary, Crypt32Util.cryptProtectData(plain))
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
        values = next
    }.isSuccess

    private fun read(): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        val plain = String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), Charsets.UTF_8)
        val stored = Json.parseToJsonElement(plain) as JsonObject
        return stored.mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }.toMap()
    }
}
