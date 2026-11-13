package eu.studio742.imago.core.interchange

/** Why a recipe could not go into the community gallery. Closed, so that it can be shown and logged safely. */
enum class RecipeGalleryError { NOT_SUPPORTER, IMPORTED, ALREADY_PUBLISHED, INVALID, LIMIT, LINK_GONE, IMAGE_UPLOAD, OFFLINE, FAILED }

class RecipeGalleryException(val error: RecipeGalleryError) : Exception()

/**
 * The supporters' community gallery, where a recipe link of one's own goes with a before and an after.
 * Who is a supporter is the server's answer; the app only hides what does not apply.
 */
interface RecipeGalleryService {
    /** The gallery's page in the app's language, or null in a build without one: nothing is then offered. */
    fun galleryUrl(portuguese: Boolean): String?

    /** False on any doubt — no account, a server without the programme, no connection. */
    suspend fun isSupporter(): Boolean

    /** The account's recipe links already in the gallery; empty on any doubt. */
    suspend fun publishedLinks(): Set<String>

    /** [before] and [after] are re-encoded JPEG pixels, without the source's metadata. */
    suspend fun publish(linkId: String, title: String, authorName: String, before: ByteArray, after: ByteArray)
}

/** A build with no account backend or no website. */
object NoRecipeGallery : RecipeGalleryService {
    override fun galleryUrl(portuguese: Boolean): String? = null
    override suspend fun isSupporter() = false
    override suspend fun publishedLinks(): Set<String> = emptySet()
    override suspend fun publish(linkId: String, title: String, authorName: String, before: ByteArray, after: ByteArray): Unit =
        throw RecipeGalleryException(RecipeGalleryError.FAILED)
}

/** What the gallery accepts, the same limits the server checks. */
object RecipeGalleryLimits {
    val TITLE = 3..80
    val AUTHOR = 1..40

    /** The longer side of each photo: enough for a card and its enlargement, well under the 1 MB the bucket takes. */
    const val PHOTO_SIDE = 1600
    const val PHOTO_MAX_BYTES = 1_000_000
}
