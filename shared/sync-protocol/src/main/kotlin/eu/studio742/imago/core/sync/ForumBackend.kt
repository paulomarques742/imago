package eu.studio742.imago.core.sync

/** Public community posts are separate from private diagnostics and account sync. */
interface ForumBackend {
    /** A website backed by this same project; absent in a development project without a site. */
    val websiteUrl: String? get() = null
    suspend fun profile(): ForumProfile
    suspend fun publish(draft: ForumDraft, images: List<ForumImage>): ForumPost
    suspend fun delete(post: ForumPost)
}

enum class ForumCategory(val wire: String) { BUG("bug"), IDEA("idea"), QUESTION("question"), OTHER("other") }
data class ForumProfile(val signedIn: Boolean, val name: String?)
data class ForumDraft(
    val category: ForumCategory,
    val title: String,
    val body: String,
    val name: String,
    val appVersion: String = "",
    val device: String = "",
) {
    fun isValid(): Boolean = title.trim().length in 3..120 && body.trim().length in 1..5000 &&
        name.trim().length in 1..40 && appVersion.length <= 40 && device.length <= 80
}

/** Re-encoded pixels only; the source file and its metadata never enter the transport. */
class ForumImage(val bytes: ByteArray) {
    init { require(bytes.isNotEmpty() && bytes.size <= 1_000_000) }
}

/** The ownership token stays in encrypted local storage, never in links or diagnostics. */
class ForumPost(val id: String, val token: String, val title: String)

class ForumException(val reason: Reason, val status: Int? = null) : Exception(reason.name) {
    enum class Reason {
        OFFLINE, INVALID, RATE_LIMITED, BUSY, TOO_MANY_LINKS, REJECTED, IMAGE_UPLOAD,
        IMAGE_MISSING, SESSION_EXPIRED, FORBIDDEN, HAS_REPLIES, NOT_FOUND, FAILED,
    }
}
