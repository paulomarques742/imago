package eu.studio742.imago.core.sync

import eu.studio742.imago.core.interchange.COMMUNITY_PAGE_SIZE
import eu.studio742.imago.core.interchange.CommunityRecipe
import eu.studio742.imago.core.interchange.CommunitySort
import eu.studio742.imago.core.interchange.RecipeGalleryError
import eu.studio742.imago.core.interchange.RecipeGalleryException
import eu.studio742.imago.core.interchange.RecipeGalleryService
import eu.studio742.imago.core.interchange.RecipeLinkImages
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticEvent
import eu.studio742.imago.core.model.diagnostics.DiagnosticFailure
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.DiagnosticOutcome
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.sync.GalleryBackendException.Reason
import kotlinx.coroutines.CancellationException

/**
 * The gallery as the share dialog sees it. Failures reaching the server end up in the diagnostics, as
 * closed values only: never the link, the photos or the server's address. A refusal — not a supporter,
 * an imported recipe — is the server's answer and not a failure, so it is not recorded.
 */
class GalleryRepository(
    private val backend: GalleryBackend,
    private val isSignedIn: () -> Boolean,
) : RecipeGalleryService {
    override fun galleryUrl(portuguese: Boolean): String = galleryUrl(backend.websiteUrl, portuguese)

    override suspend fun isSupporter(): Boolean {
        if (!isSignedIn()) return false
        return quietly(false) { backend.supporter().active }
    }

    override suspend fun publishedLinks(): Set<String> {
        if (!isSignedIn()) return emptySet()
        return quietly(emptySet()) { backend.publishedLinks() }
    }

    override suspend fun publish(linkId: String, title: String, authorName: String, before: ByteArray, after: ByteArray) {
        val photos = try {
            GalleryImage(before) to GalleryImage(after)
        } catch (_: IllegalArgumentException) {
            throw RecipeGalleryException(RecipeGalleryError.INVALID)
        }
        try {
            backend.publish(linkId, title.trim(), authorName.trim(), photos.first, photos.second)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryBackendException) {
            if (error.reason.isFailure()) record(error, error.status)
            throw RecipeGalleryException(errorOf(error.reason))
        } catch (error: Exception) {
            record(error, null)
            throw RecipeGalleryException(RecipeGalleryError.FAILED)
        }
    }

    override suspend fun linkImages(linkId: String): RecipeLinkImages {
        if (!isSignedIn()) return RecipeLinkImages.NONE
        return quietly(RecipeLinkImages.NONE) {
            when (backend.linkImages(linkId)) {
                LinkImages.NONE -> RecipeLinkImages.NONE
                LinkImages.SHOWN -> RecipeLinkImages.SHOWN
                LinkImages.HIDDEN -> RecipeLinkImages.HIDDEN
            }
        }
    }

    override suspend fun setLinkImages(linkId: String, before: ByteArray, after: ByteArray) {
        val photos = try {
            GalleryImage(before) to GalleryImage(after)
        } catch (_: IllegalArgumentException) {
            throw RecipeGalleryException(RecipeGalleryError.INVALID)
        }
        try {
            backend.setLinkImages(linkId, photos.first, photos.second)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryBackendException) {
            if (error.reason.isFailure()) record(error, error.status)
            throw RecipeGalleryException(errorOf(error.reason))
        } catch (error: Exception) {
            record(error, null)
            throw RecipeGalleryException(RecipeGalleryError.FAILED)
        }
    }

    override suspend fun clearLinkImages(linkId: String) {
        if (!isSignedIn()) return
        quietly(Unit) { backend.clearLinkImages(linkId) }
    }

    override suspend fun communityRecipes(sort: CommunitySort, offset: Int): List<CommunityRecipe> =
        reaching {
            backend.posts(if (sort == CommunitySort.TOP) GallerySort.TOP else GallerySort.NEW, offset, COMMUNITY_PAGE_SIZE).map { post ->
                CommunityRecipe(post.id, post.title, post.authorName, post.linkId, post.beforeUrl, post.afterUrl, post.likes, post.liked, post.mine)
            }
        }

    override suspend fun like(postId: String, like: Boolean): Int = reaching { backend.like(postId, like) }

    /** A request whose failure the screen says: a refusal is passed on, a failure also recorded. */
    private suspend fun <T> reaching(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: GalleryBackendException) {
        if (error.reason.isFailure()) record(error, error.status)
        throw RecipeGalleryException(errorOf(error.reason))
    } catch (error: Exception) {
        record(error, null)
        throw RecipeGalleryException(RecipeGalleryError.FAILED)
    }

    /** Questions whose doubt only hides an option: the answer on doubt is [fallback], and a failure is still recorded. */
    private suspend fun <T> quietly(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: GalleryBackendException) {
        if (error.reason.isFailure()) record(error, error.status)
        fallback
    } catch (error: Exception) {
        record(error, null)
        fallback
    }

    private fun Reason.isFailure() = this == Reason.OFFLINE || this == Reason.IMAGE_UPLOAD || this == Reason.FAILED

    private fun record(error: Exception, status: Int?) {
        val outcome = when {
            status != null -> DiagnosticOutcome.HTTP_ERROR
            (error as? GalleryBackendException)?.reason == Reason.OFFLINE -> DiagnosticOutcome.CONNECTION
            else -> DiagnosticOutcome.FAILURE
        }
        LocalDiagnostics.record {
            DiagnosticEvent(DiagnosticArea.SYNC, outcome, DiagnosticOperation.GALLERY, status = status, failure = DiagnosticFailure.from(error))
        }
    }

    private fun errorOf(reason: Reason) = when (reason) {
        Reason.NO_ACCOUNT, Reason.NOT_SUPPORTER -> RecipeGalleryError.NOT_SUPPORTER
        Reason.IMPORTED -> RecipeGalleryError.IMPORTED
        Reason.ALREADY_PUBLISHED -> RecipeGalleryError.ALREADY_PUBLISHED
        Reason.INVALID -> RecipeGalleryError.INVALID
        Reason.LIMIT -> RecipeGalleryError.LIMIT
        Reason.LINK_NOT_FOUND -> RecipeGalleryError.LINK_GONE
        Reason.IMAGE_UPLOAD -> RecipeGalleryError.IMAGE_UPLOAD
        Reason.IMAGES_BLOCKED -> RecipeGalleryError.IMAGES_BLOCKED
        Reason.OFFLINE -> RecipeGalleryError.OFFLINE
        Reason.FAILED -> RecipeGalleryError.FAILED
    }
}
