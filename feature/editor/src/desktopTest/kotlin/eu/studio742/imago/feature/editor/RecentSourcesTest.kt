package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentSourcesTest {
    private var now = 0L
    private fun sources(capacity: Int = 2, lifetime: Long = 1_000L) = RecentSources<String>(capacity, lifetime) { now }

    @Test fun `a source comes back while it is fresh`() {
        val recent = sources()
        recent.put("a", "A")
        now = 900
        assertEquals("A", recent.get("a"))
    }

    @Test fun `a source nobody asks for goes after its lifetime, and asking renews it`() {
        val recent = sources()
        recent.put("a", "A")
        now = 800
        assertEquals("A", recent.get("a"))
        now = 1_700
        assertEquals("asked at 800, so it lasts until 1800", "A", recent.get("a"))
        now = 2_800
        assertNull(recent.get("a"))
    }

    @Test fun `the one used longest ago makes room`() {
        val recent = sources(capacity = 2)
        recent.put("a", "A")
        recent.put("b", "B")
        recent.get("a")
        recent.put("c", "C")
        assertNull(recent.get("b"))
        assertEquals("A", recent.get("a"))
        assertEquals("C", recent.get("c"))
    }
}
