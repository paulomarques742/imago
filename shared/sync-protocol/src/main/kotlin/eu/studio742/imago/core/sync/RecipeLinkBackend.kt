package eu.studio742.imago.core.sync

/** Recipes published under a link. Creating needs an account; opening does not. */
interface RecipeLinkBackend {
    /** The website links point at; null in a project without one, where there is nothing to open them. */
    val websiteUrl: String?
    suspend fun create(recipeId: String, content: String): String
    suspend fun update(id: String, content: String)
    suspend fun revoke(id: String)
    suspend fun mine(): List<RecipeLink>

    /** The recipe file behind a link, or null when the link does not exist or was revoked. */
    suspend fun open(id: String): String?
}

data class RecipeLink(val id: String, val recipeId: String)

/** Carries no text of the server's, and never the link's identifier: it can end up in a diagnostic. */
class RecipeLinkBackendException(val reason: Reason, val status: Int? = null) : Exception() {
    enum class Reason { NO_ACCOUNT, LIMIT, TOO_LARGE, NOT_FOUND, OFFLINE, FAILED }
}
