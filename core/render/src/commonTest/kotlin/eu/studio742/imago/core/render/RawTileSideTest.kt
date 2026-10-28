package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

class RawTileSideTest {
    @Test fun `the grid, the viewer placeholder and the strip ask for one render`() {
        assertEquals(rawTileSide(318), rawTileSide(360))
        assertEquals(rawTileSide(96), rawTileSide(360))
        assertEquals(RAW_TILE_MIN_SIDE, rawTileSide(1))
    }

    @Test fun `bigger requests go to the next size and never past the last`() {
        assertEquals(512, rawTileSide(385))
        assertEquals(2048, rawTileSide(2000))
        assertEquals(2048, rawTileSide(10_000))
    }
}
