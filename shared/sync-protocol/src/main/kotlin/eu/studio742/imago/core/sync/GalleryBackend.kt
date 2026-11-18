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

    /** What the page of one of the account's links shows: nothing, the before and the after, or them hidden after reports. */
    suspend fun linkImages(linkId: String): LinkImages = LinkImages.NONE

    /** Uploads both photos and puts them on [linkId]'s page, in place of the ones there. */
    suspend fun setLinkImages(linkId: String, before: GalleryImage, after: GalleryImage): Unit =
        throw GalleryBackendException(GalleryBackendException.Reason.FAILED)

    /** Takes the images off [linkId]'s page and deletes them. */
    suspend fun clearLinkImages(linkId: String) {}

    /**
     * A page of the gallery's visible posts, for anyone: an account only adds whether it liked each one.
     * A server without the programme answers with an empty gallery.
     */
    suspend fun posts(sort: GallerySort, offset: Int, limit: Int): List<GalleryPost> =
        throw GalleryBackendException(GalleryBackendException.Reason.FAILED)

    /** A supporter's like on [postId], or taking it back; returns the post's likes. */
    suspend fun like(postId: String, like: Boolean): Int =
        throw GalleryBackendException(GalleryBackendException.Reason.FAILED)
}

/** The most liked first, or the most recent. */
enum class GallerySort { TOP, NEW }

/** A post as the gallery shows it: [beforeUrl] and [afterUrl] are the public bucket's addresses. */
data class GalleryPost(
    val id: String,
    val title: String,
    val authorName: String,
    val linkId: String,
    val beforeUrl: String,
    val afterUrl: String,
    val likes: Int,
    val liked: Boolean,
    val mine: Boolean,
)

/** The images on a recipe link's page. */
enum class LinkImages { NONE, SHOWN, HIDDEN }

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
    enum class Reason { NO_ACCOUNT, NOT_SUPPORTER, LINK_NOT_FOUND, IMPORTED, ALREADY_PUBLISHED, INVALID, LIMIT, IMAGE_UPLOAD, IMAGES_BLOCKED, OFFLINE, FAILED }
}

/** The gallery's page; the base must belong to the backend's environment. */
fun galleryUrl(baseUrl: String, portuguese: Boolean = false): String =
    "${baseUrl.trimEnd('/')}/${if (portuguese) "pt/galeria/" else "gallery/"}"
