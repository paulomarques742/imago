package eu.studio742.imago.core.model

import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVersionTest {
    @Test
    fun versionsCompareSemantically() {
        assertTrue(ServerVersion(3, 1, 0) > ServerVersion(3, 0, 99))
        assertTrue(ServerVersion(4, 0, 0) > ServerVersion(3, 99, 99))
    }
}
