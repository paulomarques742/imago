package eu.studio742.imago.core.composition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTransparencyTest {
    private fun media(fileName: String, mimeType: String? = null) = MediaReference("id", "sum", fileName, mimeType)

    @Test fun `formats with an alpha channel are recognised by the mime type`() {
        listOf("image/png", "IMAGE/PNG", "image/apng", "image/webp", "image/gif", "image/avif").forEach {
            assertTrue(it, media("file", it).mayHaveTransparency)
        }
    }

    @Test fun `without a mime type the extension decides`() {
        listOf("logo.png", "LOGO.PNG", "sticker.webp", "anim.gif", "a.b.avif", "Untitled (1).png").forEach {
            assertTrue(it, media(it).mayHaveTransparency)
        }
        assertTrue(media("logo.png", mimeType = " ").mayHaveTransparency)
    }

    @Test fun `opaque formats keep the light preview`() {
        listOf("photo.jpg", "photo.JPEG", "raw.dng", "scan.tif", "no-extension", "png").forEach {
            assertFalse(it, media(it).mayHaveTransparency)
        }
    }

    @Test fun `the mime type wins over a misleading extension`() {
        assertFalse(media("renamed.png", "image/jpeg").mayHaveTransparency)
        assertTrue(media("renamed.jpg", "image/png").mayHaveTransparency)
    }
}
