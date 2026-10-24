package eu.studio742.imago.core.raw

import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual fun loadRawLibrary() {
    check(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
        "This distribution contains the Windows x64 RAW decoder."
    }
    val resource = checkNotNull(RawDecoder::class.java.getResourceAsStream("/native/windows-x64/imago_raw.dll")) {
        "The native RAW decoder is missing from this distribution."
    }
    val directory = Files.createTempDirectory("imago-raw-")
    val library = directory.resolve("imago_raw.dll")
    directory.toFile().deleteOnExit()
    library.toFile().deleteOnExit()
    resource.use { Files.copy(it, library, StandardCopyOption.REPLACE_EXISTING) }
    System.load(library.toAbsolutePath().toString())
}

