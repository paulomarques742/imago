package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ExportOriginCheckerTest {
    private val photo = AssetReference("configured-source", "photo").encode()

    @Test fun anExternalPhotoIsIdentifiedBeforeSending() = runTest {
        val response = CompletableDeferred<String?>()
        val checker = ExportOriginChecker(this) { assetId ->
            assertEquals(photo, assetId)
            response.await()
        }
        checker.check(photo)
        runCurrent()
        assertEquals(ImmichExportOrigin.CHECKING, checker.state.value)
        response.complete("external-library")
        runCurrent()
        assertEquals(ImmichExportOrigin.EXTERNAL, checker.state.value)
    }

    @Test fun anUploadLibraryDoesNotShowTheExternalWarning() = runTest {
        val checker = ExportOriginChecker(this) { null }
        checker.check(photo)
        runCurrent()
        assertEquals(ImmichExportOrigin.OTHER, checker.state.value)
    }

    @Test fun aDevicePhotoDoesNotAskTheServer() = runTest {
        val checker = ExportOriginChecker(this) { error("A device photo must not request Immich detail") }
        checker.check(AssetReference(DEVICE_LIBRARY_ID, "photo").encode())
        runCurrent()
        assertEquals(ImmichExportOrigin.OTHER, checker.state.value)
    }

    @Test fun aFailedCheckRemainsUnknownAndCanBeRetried() = runTest {
        var available = false
        val checker = ExportOriginChecker(this) {
            if (!available) throw IOException("offline")
            "external-library"
        }
        checker.check(photo)
        runCurrent()
        assertEquals(ImmichExportOrigin.UNAVAILABLE, checker.state.value)
        available = true
        checker.check(photo)
        runCurrent()
        assertEquals(ImmichExportOrigin.EXTERNAL, checker.state.value)
    }

    @Test fun changingThePhotoDiscardsEvenAResultReturnedDuringCancellation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val checker = ExportOriginChecker(this) { id ->
            if (id == photo) {
                try { gate.await() } catch (_: CancellationException) { }
                "external-library"
            } else null
        }
        checker.check(photo)
        runCurrent()
        checker.check(AssetReference("configured-source", "next").encode())
        runCurrent()
        assertEquals(ImmichExportOrigin.OTHER, checker.state.value)
        checker.reset()
        assertEquals(ImmichExportOrigin.NOT_CHECKED, checker.state.value)
    }
}
