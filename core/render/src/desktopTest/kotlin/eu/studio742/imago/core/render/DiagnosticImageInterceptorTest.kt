package eu.studio742.imago.core.render

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import eu.studio742.imago.core.model.diagnostics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DiagnosticImageInterceptorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun returnedAndThrownImageErrorsKeepTheirOutcomeWithoutRequestOrExceptionContents() = runBlocking {
        val store = DiagnosticStore(temporary.newFolder())
        LocalDiagnostics.start(store, emptyMap())
        try {
            for (throws in listOf(false, true)) {
                val loader = ImageLoader.Builder(PlatformContext.INSTANCE).components {
                    add(DiagnosticImageInterceptor())
                    add(object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                            val error = IOException("private-photo.jpg private-api-key https://private.example.invalid")
                            if (throws) throw error
                            return ErrorResult(null, chain.request, error)
                        }
                    })
                }.build()
                try {
                    val result = loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data("private-file-path/photo.jpg").size(32).build())
                    assertTrue(result is ErrorResult)
                } finally { loader.shutdown() }
            }
            val report = store.report(emptyMap(), "Issue", DiagnosticAccess.UNKNOWN, emptyList()).readText()
            assertEquals(2, report.lineSequence().count { it.contains("IMAGE THUMBNAIL CONNECTION") })
            listOf("private-photo.jpg", "private-api-key", "private.example.invalid", "private-file-path").forEach { assertFalse(report.contains(it)) }
        } finally { LocalDiagnostics.stop() }
    }
}
