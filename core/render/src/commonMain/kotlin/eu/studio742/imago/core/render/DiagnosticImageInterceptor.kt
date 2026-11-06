package eu.studio742.imago.core.render

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.transformations
import eu.studio742.imago.core.model.diagnostics.DiagnosticArea
import eu.studio742.imago.core.model.diagnostics.DiagnosticOperation
import eu.studio742.imago.core.model.diagnostics.LocalDiagnostics

/** Observes local decode, network and recipe render failures on both platforms without logging request data. */
class DiagnosticImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val operation = if (chain.request.transformations.isEmpty()) DiagnosticOperation.THUMBNAIL else DiagnosticOperation.RENDER_PHOTO
        val result = try { chain.proceed() } catch (error: Exception) {
            LocalDiagnostics.failure(DiagnosticArea.IMAGE, error, operation)
            throw error
        }
        if (result is ErrorResult) LocalDiagnostics.failure(DiagnosticArea.IMAGE, result.throwable, operation)
        return result
    }
}
