package eu.studio742.imago.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNamingTest {
    @Test
    fun exportNameIsStableAndJpeg() {
        assertEquals("My_photo_ImmichRoom.jpg", immichRoomExportFileName("My photo.HEIC", "photo"))
    }

    /** A file without a name gets the one the caller gives, which comes in the app language. */
    @Test
    fun anUntitledOriginalTakesTheGivenName() {
        assertEquals("photo_ImmichRoom.jpg", immichRoomExportFileName("", "photo"))
        assertEquals("fotografia_ImmichRoom.jpg", immichRoomExportFileName(".jpg", "fotografia"))
    }

    @Test
    fun exportsAreRecognisedByTheirSuffix() {
        assertTrue(isImmichRoomExport("IMG_0001_ImmichRoom.jpg"))
        assertFalse(isImmichRoomExport("IMG_0001.jpg"))
        assertFalse(isImmichRoomExport("ImmichRoom.jpg"))
        assertFalse(isImmichRoomExport("IMG_0001_ImmichRoom.png"))
    }

    @Test
    fun everyGeneratedNameIsRecognisedBack() {
        listOf("My photo.HEIC", "", "IMG_0001.jpg", "férias 2025.dng", "no-extension")
            .forEach { original ->
                assertTrue(original, isImmichRoomExport(immichRoomExportFileName(original, "photo")))
            }
    }
}
