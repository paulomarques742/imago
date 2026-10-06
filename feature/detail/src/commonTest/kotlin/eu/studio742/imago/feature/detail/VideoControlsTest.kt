package eu.studio742.imago.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoControlsTest {
    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("0:00", formatPlaybackTime(0L, 200_000L))
        assertEquals("0:09", formatPlaybackTime(9_999L, 200_000L))
        assertEquals("1:05", formatPlaybackTime(65_000L, 200_000L))
        assertEquals("59:59", formatPlaybackTime(3_599_000L, 3_599_000L))
    }

    @Test
    fun hoursFollowTheDurationSoBothTimesHaveTheSameShape() {
        assertEquals("0:01:05", formatPlaybackTime(65_000L, 3_600_000L))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L, 4_000_000L))
    }

    @Test
    fun negativePositionReadsAsStart() {
        assertEquals("0:00", formatPlaybackTime(-500L, 10_000L))
    }

    @Test
    fun skipStaysInsideTheVideo() {
        assertEquals(15_000L, skipTarget(5_000L, VIDEO_SKIP_MS, 60_000L))
        assertEquals(0L, skipTarget(4_000L, -VIDEO_SKIP_MS, 60_000L))
        assertEquals(60_000L, skipTarget(55_000L, VIDEO_SKIP_MS, 60_000L))
    }

    @Test
    fun skipForwardIsUnboundedWhileTheDurationIsUnknown() {
        assertEquals(15_000L, skipTarget(5_000L, VIDEO_SKIP_MS, 0L))
    }
}
