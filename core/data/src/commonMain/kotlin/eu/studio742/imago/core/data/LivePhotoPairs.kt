package eu.studio742.imago.core.data

/** One file of a folder, as far as pairing a Live Photo with its video needs to know it. */
internal data class PairingEntry(val id: String, val folderId: String?, val name: String, val isVideo: Boolean)

private val PHOTO_EXTENSIONS = listOf("heic", "heif", "jpg", "jpeg")
private const val VIDEO_EXTENSION = "mov"

/**
 * The photos of a folder that are the still of an Apple Live Photo, each with its video: the photo
 * `IMG_0001.HEIC` and the clip `IMG_0001.MOV` side by side, as an iPhone writes them when they are
 * copied to a computer. Photo id to video id.
 *
 * The pair is told by the name and the folder alone. Apple also stamps both files with the same
 * content identifier, which is the stronger proof, but it needs the files opened; until it is read, a
 * clip is only taken for a Live Photo's when it is a `.mov` with a photo's name beside it. A photo
 * without a clip, and a clip without a photo, are left as they are.
 *
 * Each clip belongs to one photo: when a folder holds both `IMG_0001.HEIC` and `IMG_0001.JPG`, the
 * one that comes first in [PHOTO_EXTENSIONS] is the Live Photo's still.
 */
internal fun pairLivePhotosByName(entries: List<PairingEntry>): Map<String, String> {
    val clips = entries.filter { it.isVideo && it.name.extensionOf() == VIDEO_EXTENSION }
        .groupBy { it.folderId to it.name.stemOf() }
    if (clips.isEmpty()) return emptyMap()
    val taken = mutableSetOf<String>()
    val pairs = linkedMapOf<String, String>()
    entries.filter { !it.isVideo && it.name.extensionOf() in PHOTO_EXTENSIONS }
        .sortedBy { PHOTO_EXTENSIONS.indexOf(it.name.extensionOf()) }
        .forEach { photo ->
            val clip = clips[photo.folderId to photo.name.stemOf()]?.firstOrNull { it.id !in taken } ?: return@forEach
            taken += clip.id
            pairs[photo.id] = clip.id
        }
    return pairs
}

private fun String.extensionOf() = substringAfterLast('.', "").lowercase()

private fun String.stemOf() = substringBeforeLast('.').lowercase()
