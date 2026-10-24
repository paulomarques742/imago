package eu.studio742.imago.feature.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerMediaFormatTest {
    private fun photo(width: Long?, height: Long?, video: Boolean = false) =
        ComposerMedia("id", "sum", "a.jpg", width = width, height = height, isVideo = video)

    @Test
    fun `a photo gives a page of its own size`() {
        val format = photo(3000, 2000).fileFormat()!!
        assertEquals(3000, format.pixelWidth)
        assertEquals(2000, format.pixelHeight)
    }

    @Test
    fun `a photo bigger than a page is scaled down keeping its proportions`() {
        val format = photo(8192, 4096).fileFormat()!!
        assertEquals(4096, format.pixelWidth)
        assertEquals(2048, format.pixelHeight)
    }

    @Test
    fun `no size known or a video gives no page`() {
        assertNull(photo(null, null).fileFormat())
        assertNull(photo(1920, 1080, video = true).fileFormat())
    }
}
