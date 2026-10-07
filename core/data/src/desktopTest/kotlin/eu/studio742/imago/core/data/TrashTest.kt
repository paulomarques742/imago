package eu.studio742.imago.core.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrashTest {
    private val now = Instant.parse("2026-10-07T20:00:00Z")

    @Test
    fun daysLeftRoundUpSoWhatGoesTonightStillHasOne() {
        assertEquals(30, daysLeft("2026-11-06T20:00:00Z", now))
        assertEquals(1, daysLeft("2026-10-07T23:59:00Z", now))
        assertEquals(2, daysLeft("2026-10-09T08:00:00Z", now))
    }

    @Test
    fun whatIsOverdueHasNoneAndAnUnreadableDateSaysNothing() {
        assertEquals(0, daysLeft("2026-10-01T00:00:00Z", now))
        assertNull(daysLeft("tomorrow", now))
    }
}
