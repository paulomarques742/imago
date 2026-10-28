package eu.studio742.imago.feature.editor

import coil3.PlatformContext
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.toUiText
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.editor_export_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the photo export in the background is: [phase] while it runs, then one of [message] and [error]. */
data class PhotoExportState(
    val running: Boolean = false,
    val phase: UiText? = null,
    val message: UiText? = null,
    val error: UiText? = null,
)

/**
 * A photo going to the gallery or to Immich, which carries on when the editor is left, as the "Show your
 * edit" video does ([ReelExports]). A full-resolution RAW is tens of seconds of work; tied to the editor's
 * view model it stopped with the screen. One at a time: a second one asked for while this runs is the
 * editor's own, in the foreground.
 *
 * The work runs on the main dispatcher, as it did in the view model: it reports to the editor's state and
 * does its heavy parts on its own threads.
 */
object PhotoExports {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PhotoExportState())
    val state: StateFlow<PhotoExportState> = mutableState.asStateFlow()
    private var job: Job? = null

    /**
     * Starts [work] unless another export is running, and returns whether it did. [work] is handed a way to
     * say which phase it is in; [onResult] hears how it ended, a cancellation included.
     */
    fun start(
        context: PlatformContext,
        work: suspend (onPhase: (UiText) -> Unit) -> UiText?,
        onResult: (Result<UiText?>) -> Unit,
    ): Boolean {
        if (job?.isActive == true) return false
        mutableState.value = PhotoExportState(running = true)
        photoExportStarted(context)
        job = scope.launch {
            val result = try {
                Result.success(work { phase -> mutableState.update { it.copy(phase = phase) } })
            } catch (cancelled: CancellationException) {
                mutableState.value = PhotoExportState()
                onResult(Result.failure(cancelled))
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            mutableState.value = PhotoExportState(
                message = result.getOrNull(),
                error = result.exceptionOrNull()?.toUiText(Res.string.editor_export_failed),
            )
            onResult(result)
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }
}

/** Keeps the process alive and tells the person while [PhotoExports] runs: a notification on Android, nothing on the desktop. */
internal expect fun photoExportStarted(context: PlatformContext)
