package eu.studio742.imago.core.model.diagnostics

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportDiagnosticsTest {
    @Test
    fun aFailedVideoExportIsInTheReportWithoutTheFileOrTheServer() {
        val directory = Files.createTempDirectory("video-export-diagnostics").toFile()
        val store = DiagnosticStore(directory)
        LocalDiagnostics.start(store, emptyMap())
        try {
            val error = IllegalStateException("muxer died writing /storage/emulated/0/Movies/holiday-edit.mp4 for https://immich.example/api")
            LocalDiagnostics.failure(DiagnosticArea.EXPORT, error, DiagnosticOperation.VIDEO_EDIT_DOWNLOAD)
            LocalDiagnostics.record(DiagnosticEvent(DiagnosticArea.EXPORT, DiagnosticOutcome.SUCCESS, DiagnosticOperation.VIDEO_EDIT_EXPORT, durationMs = 1200))

            val report = store.report(emptyMap(), "", DiagnosticAccess.UNKNOWN, emptyList()).readText()
            assertTrue(report, report.contains("EXPORT VIDEO_EDIT_DOWNLOAD FAILURE"))
            assertTrue(report, report.contains("EXPORT VIDEO_EDIT_EXPORT SUCCESS"))
            assertTrue(report, report.contains("FAILURE"))
            assertFalse(report, report.contains("holiday-edit"))
            assertFalse(report, report.contains("immich.example"))
            assertFalse(report, report.contains("muxer died"))
        } finally {
            LocalDiagnostics.stop()
            directory.deleteRecursively()
        }
    }
}
