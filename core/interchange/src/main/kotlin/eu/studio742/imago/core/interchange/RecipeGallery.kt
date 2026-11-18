package eu.studio742.imago.core.interchange

/** Why a recipe could not go into the community gallery. Closed, so that it can be shown and logged safely. */
enum class RecipeGalleryError { NOT_SUPPORTER, IMPORTED, ALREADY_PUBLISHED, INVALID, LIMIT, LINK_GONE, IMAGE_UPLOAD, IMAGES_BLOCKED, OFFLINE, FAILED }

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

    /** What the page of one of the account's links shows; nothing on any doubt. */
    suspend fun linkImages(linkId: String): RecipeLinkImages = RecipeLinkImages.NONE

    /** Puts [before] and [after], re-encoded JPEG pixels, on the link's page, in place of the ones there. */
    suspend fun setLinkImages(linkId: String, before: ByteArray, after: ByteArray): Unit = throw RecipeGalleryException(RecipeGalleryError.FAILED)

    /** Takes the images off the link's page; quietly, because it also runs when the link is revoked. */
    suspend fun clearLinkImages(linkId: String) {}

    /** A page of the gallery, for anyone; throws [RecipeGalleryException] when it cannot be read. */
    suspend fun communityRecipes(sort: CommunitySort, offset: Int): List<CommunityRecipe> =
        throw RecipeGalleryException(RecipeGalleryError.FAILED)

    /** A supporter's like, or taking it back; returns the recipe's likes now. */
    suspend fun like(postId: String, like: Boolean): Int = throw RecipeGalleryException(RecipeGalleryError.FAILED)
}

/** The most liked first, or the most recent. */
enum class CommunitySort { TOP, NEW }

/** A recipe in the community gallery, which opens and copies through its link. */
data class CommunityRecipe(
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

/** How many recipes a page of the gallery brings. */
const val COMMUNITY_PAGE_SIZE = 30

/** The images on a recipe link's page: none, shown, or hidden after reports. */
enum class RecipeLinkImages { NONE, SHOWN, HIDDEN }

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
