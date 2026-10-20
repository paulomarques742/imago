package eu.studio742.imago.feature.library

/**
 * Whether what another app shares keeps its GPS on the way to Immich.
 *
 * Android's media store wipes the location from the EXIF of every file it serves to an app without the
 * media location permission — and the share grants the right to read the file, not that permission,
 * which is IMAGO's own. With it, the file is opened as the original, location included. Other
 * providers serve the file as they have it: whatever they keep or strip is their decision.
 */
object SharedMediaLocation {
    /** The media store's authority, `MediaStore.AUTHORITY`. */
    const val MEDIA_STORE_AUTHORITY = "media"

    /**
     * Asked once, the first time a share brings files from the media store without the permission.
     * Refused, the files go anyway, without the location, and the share never asks again.
     */
    fun shouldAsk(authorities: List<String?>, granted: Boolean, askedBefore: Boolean): Boolean =
        !granted && !askedBefore && authorities.any { it == MEDIA_STORE_AUTHORITY }

    /** Whether the file is to be opened as the original, which only the media store serves. */
    fun readsOriginal(authority: String?, granted: Boolean): Boolean = granted && authority == MEDIA_STORE_AUTHORITY
}
