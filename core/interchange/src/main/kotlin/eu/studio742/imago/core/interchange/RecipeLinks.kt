package eu.studio742.imago.core.interchange

/** Why a link could not be created, changed or opened. Closed, so that it can be shown and logged safely. */
enum class RecipeLinkError { NO_ACCOUNT, LIMIT, TOO_LARGE, GONE, OFFLINE, FAILED }

class RecipeLinkException(val error: RecipeLinkError) : Exception()

/** Publishing a recipe under a link, with whatever account the app has. */
interface RecipeLinkService {
    /** Null when there is no website to open links: the option is then not offered at all. */
    val baseUrl: String?
    val signedIn: Boolean

    /** The person's live links, by the recipe each one publishes. */
    suspend fun mine(): Map<String, String>
    suspend fun create(recipeId: String, content: ByteArray): String
    suspend fun update(linkId: String, content: ByteArray)
    suspend fun revoke(linkId: String)

    /** The recipe file behind a link, whoever opens it. */
    suspend fun open(linkId: String): ByteArray
}

/** A build with no account backend or no website. */
object NoRecipeLinks : RecipeLinkService {
    override val baseUrl: String? = null
    override val signedIn = false
    override suspend fun mine(): Map<String, String> = emptyMap()
    override suspend fun create(recipeId: String, content: ByteArray): String = throw RecipeLinkException(RecipeLinkError.FAILED)
    override suspend fun update(linkId: String, content: ByteArray): Unit = throw RecipeLinkException(RecipeLinkError.FAILED)
    override suspend fun revoke(linkId: String): Unit = throw RecipeLinkException(RecipeLinkError.FAILED)
    override suspend fun open(linkId: String): ByteArray = throw RecipeLinkException(RecipeLinkError.FAILED)
}

private val LINK_ID = Regex("[A-Za-z0-9_-]{16,64}")
private const val LINK_PATH = "/r/"

fun recipeLinkUrl(baseUrl: String, linkId: String): String = "${baseUrl.trimEnd('/')}$LINK_PATH$linkId"

/**
 * The identifier in an address that opens a recipe, or null when it is not one: `https://<site>/r/<id>`, or
 * `imago://r/<id>` from the website's button. Only [siteHost] counts, as any page can link to anything.
 */
fun recipeLinkId(scheme: String?, host: String?, path: String?, siteHost: String?): String? {
    val id = when {
        scheme == "imago" && host == "r" -> path.orEmpty().removePrefix("/")
        scheme == "https" && siteHost != null && host.equals(siteHost, ignoreCase = true) && path.orEmpty().startsWith(LINK_PATH) ->
            path.orEmpty().removePrefix(LINK_PATH)
        else -> return null
    }.removeSuffix("/")
    return id.takeIf(LINK_ID::matches)
}
