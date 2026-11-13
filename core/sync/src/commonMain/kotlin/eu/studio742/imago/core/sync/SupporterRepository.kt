package eu.studio742.imago.core.sync

import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticEvent
import eu.studio742.imago.core.model.diagnostics.DiagnosticFailure
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.DiagnosticOutcome
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import kotlinx.coroutines.CancellationException

/** What became of a purchase sent to the server. */
enum class PlayPurchaseResult { RECORDED, OTHER_ACCOUNT, NOT_PAID, FAILED }

/**
 * The supporters programme as the app's settings show it: whether it is open in this build, the
 * account's support, and recording a support bought through Google Play. Being a supporter opens
 * nothing in the app; the server decides it from the payments.
 */
interface Supporters {
    /** False in a build where the programme is not open, or without an account backend: then nothing shows. */
    val available: Boolean

    /** The programme's page on the website, in the app's language; null without a website. */
    fun programmeUrl(portuguese: Boolean): String?

    /** The account's support, or null without an account or on any doubt. */
    suspend fun state(): SupporterState?

    /** What a purchase carries as its account; null without one. */
    fun accountHash(): String?

    suspend fun recordPlayPurchase(purchaseToken: String): PlayPurchaseResult
}

object NoSupporters : Supporters {
    override val available = false
    override fun programmeUrl(portuguese: Boolean): String? = null
    override suspend fun state(): SupporterState? = null
    override fun accountHash(): String? = null
    override suspend fun recordPlayPurchase(purchaseToken: String) = PlayPurchaseResult.FAILED
}

/**
 * Failures reaching the server end up in the diagnostics as `SYNC SUPPORTER`, with closed values only:
 * never the purchase token, the account or the server's address.
 */
class SupporterRepository(
    private val gallery: GalleryBackend?,
    private val purchases: PlayPurchaseBackend?,
    private val open: Boolean,
    private val userId: () -> String?,
) : Supporters {
    override val available: Boolean get() = open && gallery != null

    override fun programmeUrl(portuguese: Boolean): String? = gallery?.websiteUrl?.let { base ->
        "${base.trimEnd('/')}/${if (portuguese) "pt/apoiantes" else "supporters"}/"
    }

    override suspend fun state(): SupporterState? {
        val backend = gallery ?: return null
        if (userId() == null) return null
        return try {
            backend.supporter()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryBackendException) {
            if (error.reason == GalleryBackendException.Reason.OFFLINE || error.reason == GalleryBackendException.Reason.FAILED) record(error, error.status)
            null
        } catch (error: Exception) {
            record(error, null)
            null
        }
    }

    override fun accountHash(): String? = userId()?.let(::playAccountHash)

    override suspend fun recordPlayPurchase(purchaseToken: String): PlayPurchaseResult {
        val backend = purchases ?: return PlayPurchaseResult.FAILED
        return try {
            backend.record(purchaseToken)
            PlayPurchaseResult.RECORDED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlayPurchaseException) {
            when (error.reason) {
                PlayPurchaseException.Reason.OTHER_ACCOUNT -> PlayPurchaseResult.OTHER_ACCOUNT
                PlayPurchaseException.Reason.NOT_PAID -> PlayPurchaseResult.NOT_PAID
                else -> {
                    record(error, error.status)
                    PlayPurchaseResult.FAILED
                }
            }
        } catch (error: Exception) {
            record(error, null)
            PlayPurchaseResult.FAILED
        }
    }

    private fun record(error: Exception, status: Int?) {
        val offline = (error as? GalleryBackendException)?.reason == GalleryBackendException.Reason.OFFLINE ||
            (error as? PlayPurchaseException)?.reason == PlayPurchaseException.Reason.OFFLINE
        val outcome = when {
            status != null -> DiagnosticOutcome.HTTP_ERROR
            offline -> DiagnosticOutcome.CONNECTION
            else -> DiagnosticOutcome.FAILURE
        }
        LocalDiagnostics.record {
            DiagnosticEvent(DiagnosticArea.SYNC, outcome, DiagnosticOperation.SUPPORTER, status = status, failure = DiagnosticFailure.from(error))
        }
    }
}
