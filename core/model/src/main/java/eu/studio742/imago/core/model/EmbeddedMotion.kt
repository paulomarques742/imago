package eu.studio742.imago.core.model

/**
 * The movement of a Motion Photo kept inside the photo's own file: [length] bytes of MP4 from
 * [start]. It travels as the photo's [ImmichAsset.livePhotoVideoId], so the player knows to read
 * only that range of [fileUrl] and not the whole file.
 */
data class EmbeddedMotion(val fileUrl: String, val start: Long, val length: Long) {
    fun encode(): String = "$fileUrl#$MARKER$start-$length"

    companion object {
        private const val MARKER = "imago-motion="

        /** Null for anything that is not an [encode]d movement: a server's video id, a plain file. */
        fun parse(value: String): EmbeddedMotion? {
            val at = value.lastIndexOf("#$MARKER").takeIf { it > 0 } ?: return null
            val range = value.substring(at + 1 + MARKER.length).split('-')
            val start = range.getOrNull(0)?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val length = range.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return EmbeddedMotion(value.substring(0, at), start, length).takeIf { range.size == 2 }
        }
    }
}
