package eu.studio742.imago.desktop.platform

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class AppPaths(val root: Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "IMAGO")) {
    val cache: Path = root.resolve("cache")
    val secrets: Path = root.resolve("secrets")
    init { Files.createDirectories(root); Files.createDirectories(cache); Files.createDirectories(secrets) }
    fun deviceId(): String {
        val file = root.resolve("device-id")
        if (Files.exists(file)) return Files.readString(file).trim()
        return UUID.randomUUID().toString().also { Files.writeString(file, it) }
    }
}

interface SecretStore { fun read(id: String): String?; fun write(id: String, value: String); fun remove(id: String) }

/** DPAPI binds secrets to the signed-in Windows user. No plaintext fallback. */
class WindowsSecretStore(private val directory: Path) : SecretStore {
    private fun path(id: String): Path {
        require(id.matches(Regex("[a-zA-Z0-9_-]+")))
        return directory.resolve("$id.bin")
    }
    override fun read(id: String): String? = path(id).takeIf(Files::exists)?.let {
        String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(it)), Charsets.UTF_8)
    }
    override fun write(id: String, value: String) {
        val encrypted = Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8))
        atomicWrite(path(id), encrypted)
    }
    override fun remove(id: String) { Files.deleteIfExists(path(id)) }
}

fun atomicWrite(destination: Path, bytes: ByteArray) {
    Files.createDirectories(destination.toAbsolutePath().parent)
    val temporary = Files.createTempFile(destination.toAbsolutePath().parent, ".imago-", ".tmp")
    try {
        Files.write(temporary, bytes)
        try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally { Files.deleteIfExists(temporary) }
}
