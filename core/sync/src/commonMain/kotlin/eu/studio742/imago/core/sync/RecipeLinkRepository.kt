package eu.studio742.imago.core.sync

import eu.studio742.imago.core.interchange.RecipeLinkError
import eu.studio742.imago.core.interchange.RecipeLinkException
import eu.studio742.imago.core.interchange.RecipeLinkService
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticEvent
import eu.studio742.imago.core.model.diagnostics.DiagnosticFailure
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.DiagnosticOutcome
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import eu.studio742.imago.core.sync.RecipeLinkBackendException.Reason
import kotlinx.coroutines.CancellationException

/** Every failure talking to the server ends up in the diagnostics, as closed values only: never the link. */
class RecipeLinkRepository(
    private val backend: RecipeLinkBackend,
    private val isSignedIn: () -> Boolean,
) : RecipeLinkService {
    override val baseUrl: String? get() = backend.websiteUrl
    override val signedIn: Boolean get() = isSignedIn()

    override suspend fun mine(): Map<String, String> = observed { backend.mine().associate { it.recipeId to it.id } }

    override suspend fun create(recipeId: String, content: ByteArray): String = observed { backend.create(recipeId, content.decodeToString()) }

    override suspend fun update(linkId: String, content: ByteArray) = observed { backend.update(linkId, content.decodeToString()) }

    override suspend fun revoke(linkId: String) = observed { backend.revoke(linkId) }

    override suspend fun open(linkId: String): ByteArray = observed {
        backend.open(linkId)?.encodeToByteArray() ?: throw RecipeLinkBackendException(Reason.NOT_FOUND)
    }

    private suspend fun <T> observed(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: RecipeLinkBackendException) {
        // A link that is gone is an answer from the server and not a failure reaching it.
        if (error.reason != Reason.NOT_FOUND) record(error, error.status?.let { DiagnosticOutcome.HTTP_ERROR } ?: outcomeOf(error.reason), error.status)
        throw RecipeLinkException(errorOf(error.reason))
    } catch (error: Exception) {
        record(error, DiagnosticOutcome.FAILURE, null)
        throw RecipeLinkException(RecipeLinkError.FAILED)
    }

    private fun record(error: Exception, outcome: DiagnosticOutcome, status: Int?) {
        LocalDiagnostics.record {
            DiagnosticEvent(DiagnosticArea.SYNC, outcome, DiagnosticOperation.RECIPE_LINK, status = status, failure = DiagnosticFailure.from(error))
        }
    }

    private fun outcomeOf(reason: Reason) = if (reason == Reason.OFFLINE) DiagnosticOutcome.CONNECTION else DiagnosticOutcome.FAILURE

    private fun errorOf(reason: Reason) = when (reason) {
        Reason.NO_ACCOUNT -> RecipeLinkError.NO_ACCOUNT
        Reason.LIMIT -> RecipeLinkError.LIMIT
        Reason.TOO_LARGE -> RecipeLinkError.TOO_LARGE
        Reason.NOT_FOUND -> RecipeLinkError.GONE
        Reason.OFFLINE -> RecipeLinkError.OFFLINE
        Reason.FAILED -> RecipeLinkError.FAILED
    }
}
