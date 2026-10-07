package eu.studio742.imago.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFoldersTest {
    @Test
    fun onlyAlbumsInsidePicturesCanChange() {
        assertTrue(isEditableDeviceFolder("Pictures/Trip/"))
        assertTrue(isEditableDeviceFolder("Pictures/IMAGO/"))
        assertTrue(isEditableDeviceFolder("Pictures/Trips/2026/"))
        assertFalse(isEditableDeviceFolder("DCIM/Camera/"))
        assertFalse(isEditableDeviceFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertFalse(isEditableDeviceFolder("Download/"))
    }

    @Test
    fun picturesItselfAndTheScreenshotsAreNotAlbumsToChange() {
        assertFalse(isEditableDeviceFolder("Pictures/"))
        assertFalse(isEditableDeviceFolder("Pictures/Screenshots/"))
    }

    @Test
    fun aNameBecomesOneFolderInsidePictures() {
        assertEquals("Pictures/Trip/", deviceAlbumFolder("  Trip "))
        assertEquals("Pictures/Trip 2026/", deviceAlbumFolder("Trip/2026"))
        assertEquals("Pictures/Ana e Rui/", deviceAlbumFolder("Ana: e Rui?"))
        assertNull(deviceAlbumFolder(" / . "))
    }
}
