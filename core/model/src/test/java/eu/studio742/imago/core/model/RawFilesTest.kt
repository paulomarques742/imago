package eu.studio742.imago.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFilesTest {
    @Test fun `camera RAWs are recognised by their type or their name`() {
        assertTrue(isRawOriginal("image/x-adobe-dng", "20230817_120455.dng"))
        assertTrue(isRawOriginal("image/x-sony-arw", "DSC0009"))
        assertTrue(isRawOriginal("image/x-raw", "photo"))
        assertTrue(isRawOriginal(null, "DSCF0266.RAF"))
        assertTrue(isRawOriginal("application/octet-stream", "IMG_0001.CR3"))
    }

    @Test fun `display images and videos are not`() {
        assertFalse(isRawOriginal("image/jpeg", "photo.jpg"))
        assertFalse(isRawOriginal("image/heic", "photo.heic"))
        assertFalse(isRawOriginal("image/x-icon", "favicon.ico"))
        assertFalse(isRawOriginal("video/mp4", "clip.mp4"))
        assertFalse(isRawOriginal(null, "notes"))
    }
}
