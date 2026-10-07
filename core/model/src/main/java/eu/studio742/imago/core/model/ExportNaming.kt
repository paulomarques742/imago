package eu.studio742.imago.core.model

/**
 * The folder, under Pictures (and Movies, for the composer's videos), where the app saves to the
 * device; the gallery shows it as an album with this name.
 */
const val DEVICE_ALBUM_NAME = "IMAGO"

/** The suffix the app stamps on every file it exports. */
const val IMMICH_ROOM_EXPORT_SUFFIX = "_ImmichRoom.jpg"

/**
 * The name an export is saved with, both in the gallery and in Immich.
 *
 * The suffix is the only mark that survives reinstalling the app, which is why it is also what lets
 * us recognise, later, an asset that came from here — see [isImmichRoomExport].
 *
 * @param untitled the name to use when the original has none, already in the app language.
 */
fun immichRoomExportFileName(original: String, untitled: String): String {
    val base = original.substringBeforeLast('.', original).ifBlank { untitled }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "$base$IMMICH_ROOM_EXPORT_SUFFIX"
}

/** Whether this file name matches an export made by the app. */
fun isImmichRoomExport(originalFileName: String): Boolean =
    originalFileName.endsWith(IMMICH_ROOM_EXPORT_SUFFIX, ignoreCase = true)
