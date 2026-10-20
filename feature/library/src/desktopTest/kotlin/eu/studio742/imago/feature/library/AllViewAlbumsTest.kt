package eu.studio742.imago.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class AllViewAlbumsTest {
    private fun album(id: String) = AlbumChoice(id, id, 1)

    @Test
    fun untickingHidesAndTickingShows() {
        val listed = listOf(album("Camera"), album("Screenshots"), album("WhatsApp Images"))

        assertEquals(setOf("Screenshots", "WhatsApp Images"), hiddenAfterChoosing(emptySet(), listed, setOf("Camera")))
        assertEquals(emptySet<String>(), hiddenAfterChoosing(setOf("Screenshots"), listed, listed.map { it.id }.toSet()))
    }

    @Test
    fun anAlbumNotListedNowStaysHiddenForWhenItComesBack() {
        val listed = listOf(album("Camera"))

        assertEquals(setOf("SD card"), hiddenAfterChoosing(setOf("SD card"), listed, setOf("Camera")))
    }
}
