package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.RecipeOrigin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** The extensions the app offers to open, including a DNG, which only the import button treats as a preset. */
val RECIPE_FILE_EXTENSIONS = listOf(IMAGO_FILE_EXTENSION, "xmp", "dng", "zip")

/** A pack of presets in DNG weighs far more than one recipe file, and this still fits in memory on a phone. */
const val RECIPE_FILE_MAX_BYTES = 64 * 1024 * 1024

private const val ZIP_MAX_ENTRIES = 2000
private const val ZIP_MAX_TOTAL_BYTES = 128L * 1024 * 1024
private const val TIFF_TAG_XMP = 700

/** Reads what any of the supported formats holds. By content, because a name from another app proves nothing. */
fun decodeRecipeFile(fileName: String, bytes: ByteArray): ImagoFileContent = runCatching {
    when {
        bytes.isZip() -> decodeZip(fileName, bytes)
        bytes.isTiff() -> decodePreset(tiffXmp(bytes), recipeFileStem(fileName), "", RecipeOrigin.DNG)
        bytes.size <= IMAGO_FILE_MAX_BYTES && xmpText(bytes) != null ->
            decodePreset(bytes, recipeFileStem(fileName), "", RecipeOrigin.XMP)
        else -> decodeImagoFile(bytes)
    }
}.getOrDefault(ImagoFileContent.Unreadable)

/** A file's name without an extension this app reads. */
fun recipeFileStem(fileName: String): String {
    val extension = fileName.substringAfterLast('.', "")
    return if (RECIPE_FILE_EXTENSIONS.any { it.equals(extension, ignoreCase = true) }) fileName.dropLast(extension.length + 1) else fileName
}

private fun decodePreset(packet: ByteArray?, name: String, collection: String, origin: RecipeOrigin): ImagoFileContent {
    val entry = presetEntryOf(packet, name, collection, origin) ?: return ImagoFileContent.Unreadable
    return ImagoFileContent.Recipes(listOf(entry))
}

private fun presetEntryOf(packet: ByteArray?, name: String, collection: String, origin: RecipeOrigin): ImagoFileEntry? {
    val text = packet?.let(::xmpText) ?: return null
    val fields = readXmpFields(text) ?: return null
    return presetEntry(fields, name, collection, origin)
}

private fun ByteArray.isZip() = size >= 4 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte() && this[2] == 3.toByte() && this[3] == 4.toByte()

private fun ByteArray.isTiff() = size >= 8 &&
    ((this[0] == 'I'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 42.toByte() && this[3] == 0.toByte()) ||
        (this[0] == 'M'.code.toByte() && this[1] == 'M'.code.toByte() && this[2] == 0.toByte() && this[3] == 42.toByte()))

/** The XMP packet of a TIFF or DNG, and nothing else of it: the image is never looked at. */
internal fun tiffXmp(bytes: ByteArray): ByteArray? {
    val littleEndian = bytes[0] == 'I'.code.toByte()
    fun unsigned(at: Int) = bytes[at].toInt() and 0xFF
    fun short(at: Int) = if (littleEndian) unsigned(at) or (unsigned(at + 1) shl 8) else (unsigned(at) shl 8) or unsigned(at + 1)
    fun long(at: Int): Long {
        val high = short(if (littleEndian) at + 2 else at).toLong()
        val low = short(if (littleEndian) at else at + 2).toLong()
        return (high shl 16) or low
    }

    val directory = long(4)
    if (directory + 2 > bytes.size) return null
    val entries = short(directory.toInt())
    val first = directory.toInt() + 2
    if (first + entries.toLong() * 12 > bytes.size) return null
    for (index in 0 until entries) {
        val at = first + index * 12
        if (short(at) != TIFF_TAG_XMP) continue
        val length = long(at + 4)
        val offset = if (length <= 4) (at + 8).toLong() else long(at + 8)
        if (length == 0L || offset + length > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
    }
    return null
}

private class ZipRefused : Exception()

/** The whole pack is refused for one hostile path: nothing else in such a file can be taken at its word. */
private fun decodeZip(fileName: String, bytes: ByteArray): ImagoFileContent {
    val pack = recipeFileStem(fileName)
    val entries = mutableListOf<ImagoFileEntry>()
    var skipped = 0
    var count = 0
    var total = 0L
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > ZIP_MAX_ENTRIES) throw ZipRefused()
                val path = entry.name.replace('\\', '/')
                if (path.isHostile()) throw ZipRefused()
                val leaf = path.substringAfterLast('/')
                val extension = leaf.substringAfterLast('.', "").lowercase()
                val wanted = !entry.isDirectory && extension in PRESET_ENTRY_EXTENSIONS && !path.isMacResource()
                // Everything is inflated through the counter, read or not: the header's size is whatever the file says.
                val content = readCounted(zip, keep = wanted, limit = IMAGO_FILE_MAX_BYTES) { total += it; if (total > ZIP_MAX_TOTAL_BYTES) throw ZipRefused() }
                when {
                    wanted -> {
                        val stem = leaf.substringBeforeLast('.')
                        val packet = if (extension == "dng") content?.takeIf { it.isTiff() }?.let(::tiffXmp) else content
                        val origin = if (extension == "dng") RecipeOrigin.DNG else RecipeOrigin.XMP
                        entries += presetEntryOf(packet, stem, pack, origin) ?: ImagoFileEntry.Invalid
                    }
                    extension in LATER_FORMATS && !path.isMacResource() -> skipped++
                }
            }
        }
    } catch (_: ZipRefused) {
        return ImagoFileContent.Unreadable
    }
    if (entries.isEmpty() && skipped == 0) return ImagoFileContent.Unreadable
    return ImagoFileContent.Recipes(entries, skippedFiles = skipped)
}

private val PRESET_ENTRY_EXTENSIONS = setOf("xmp", "dng")

/** Recipes in a format this version cannot read yet: said out loud, unlike a pack's read-me or its sample photos. */
private val LATER_FORMATS = setOf("lrtemplate", "cube")

private val DRIVE_PREFIX = Regex("^[A-Za-z]:.*")

private fun String.isHostile() = startsWith("/") || matches(DRIVE_PREFIX) || split('/').any { it == ".." }

private fun String.isMacResource() = startsWith("__MACOSX/") || substringAfterLast('/').startsWith("._")

/** Null when there was no call to keep it, or when it is over [limit]. */
private inline fun readCounted(input: InputStream, keep: Boolean, limit: Int, onRead: (Int) -> Unit): ByteArray? {
    val out = if (keep) ByteArrayOutputStream() else null
    val buffer = ByteArray(16 * 1024)
    var tooBig = false
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        onRead(read)
        if (out != null && !tooBig) {
            out.write(buffer, 0, read)
            if (out.size() > limit) tooBig = true
        }
    }
    return if (tooBig) null else out?.toByteArray()
}
