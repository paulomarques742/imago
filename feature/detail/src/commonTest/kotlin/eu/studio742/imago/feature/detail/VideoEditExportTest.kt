package eu.studio742.imago.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditExportTest {
    @Test
    fun theTurnAddsToWhatTheFileAlreadyCarries() {
        assertEquals(0, combinedOrientation(90, 270))
        assertEquals(180, combinedOrientation(90, 90))
        assertEquals(270, combinedOrientation(0, -90))
    }

    @Test
    fun aLocationIsReadInTheFormMp4FilesWriteIt() {
        assertEquals(38.7223 to -9.1393, parseIso6709("+38.7223-009.1393/"))
        assertEquals(-33.5 to 151.25, parseIso6709("-33.5+151.25+012.000/"))
    }

    @Test
    fun aLocationThatIsNotOneIsIgnored() {
        assertNull(parseIso6709(null))
        assertNull(parseIso6709("somewhere"))
        assertNull(parseIso6709("+95.0+010.0/"))
    }

    @Test
    fun theCutKeepsFromTheKeyframeToTheEnd() {
        assertFalse(keepsSample(900_000, startKeyUs = 1_000_000, endUs = 5_000_000))
        assertTrue(keepsSample(1_000_000, 1_000_000, 5_000_000))
        assertTrue(keepsSample(5_000_000, 1_000_000, 5_000_000))
        assertFalse(keepsSample(5_000_001, 1_000_000, 5_000_000))
        assertTrue(keepsSample(99_000_000, 1_000_000, null))
    }
}
