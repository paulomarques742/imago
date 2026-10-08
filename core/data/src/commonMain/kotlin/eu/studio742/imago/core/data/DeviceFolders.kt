package eu.studio742.imago.core.data

/**
 * The folders of the phone IMAGO may change: the albums inside Pictures/. The camera, the
 * screenshots and the folders of other apps stay where the system and those apps expect them, and
 * Pictures/ itself is not an album to rename.
 */
fun isEditableDeviceFolder(relativePath: String): Boolean {
    val path = relativePath.trim('/')
    if (!path.startsWith("Pictures/", ignoreCase = true)) return false
    val album = path.substringAfter('/')
    return album.isNotBlank() && !album.equals("Screenshots", ignoreCase = true) &&
        !album.startsWith("Screenshots/", ignoreCase = true)
}

/**
 * The folder for an album called [name], as MediaStore wants it (`Pictures/Trip/`), or null when
 * nothing usable is left of the name.
 */
fun deviceAlbumFolder(name: String): String? = albumFolderName(name)?.let { "Pictures/$it/" }

/**
 * The name of the folder for an album called [name], or null when nothing usable is left of it.
 * Separators and the characters file systems refuse become spaces: a "/" in the name would
 * otherwise make a folder inside a folder.
 */
fun albumFolderName(name: String): String? =
    name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '.')
        .takeIf(String::isNotEmpty)
