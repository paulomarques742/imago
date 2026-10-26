package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Observe versions during normal app startup/configuration changes, never when exporting a report. */
suspend fun ConfigurationRepository.observeDiagnosticVersions() {
    libraries.map { sources -> sources.filter { !it.isDevice && !it.isUnified && it.isConnected && it.serverUrl != null }.map { it.connection() } }
        .distinctUntilChanged().collectLatest { connections ->
            coroutineScope {
                connections.forEach { connection -> launch {
                    try { connection.libraryId?.let { testLibrary(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { LocalDiagnostics.failure(DiagnosticArea.IMMICH, error) }
                } }
            }
        }
}
