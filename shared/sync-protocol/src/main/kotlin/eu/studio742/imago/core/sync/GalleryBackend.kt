package eu.studio742.imago.core.sync

/**
 * The community gallery on the website, for supporters: publishing a recipe link of one's own with a
 * before and an after. Whether someone is a supporter is decided by the server, never here.
 */
interface GalleryBackend {
    /** The website the gallery lives on. */
    val websiteUrl: String
    /** A server without the supporters programme answers as for someone who never supported IMAGO. */
    suspend fun supporter(): SupporterState

    /** The account's recipe links that are already in the gallery, shown or hidden. */
    suspend fun publishedLinks(): Set<String>

    /** Uploads both photos and publishes [linkId]; returns the post's id. */
    suspend fun publish(linkId: String, title: String, authorName: String, before: GalleryImage, after: GalleryImage): String
}

/** [until] is when the support ends or ended, as the server wrote it; null when there never was any. */
data class SupporterState(val active: Boolean, val until: String?)

/** Re-encoded JPEG pixels only: the source file and its metadata never enter the transport. */
class GalleryImage(val bytes: ByteArray) {
    init { require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) }

    companion object {
        /** The bucket refuses anything over 1 MB. */
        const val MAX_BYTES = 1_000_000
    }
}

/** Carries no text of the server's: it can end up in a diagnostic. */
class GalleryBackendException(val reason: Reason, val status: Int? = null) : Exception(reason.name) {
    enum class Reason { NO_ACCOUNT, NOT_SUPPORTER, LINK_NOT_FOUND, IMPORTED, ALREADY_PUBLISHED, INVALID, LIMIT, IMAGE_UPLOAD, OFFLINE, FAILED }
}

/** The gallery's page; the base must belong to the backend's environment. */
fun galleryUrl(baseUrl: String, portuguese: Boolean = false): String =
    "${baseUrl.trimEnd('/')}/${if (portuguese) "pt/galeria/" else "gallery/"}"
