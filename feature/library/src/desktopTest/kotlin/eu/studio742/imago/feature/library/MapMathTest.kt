package eu.studio742.imago.feature.library

import eu.studio742.imago.core.model.MapMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMathTest {
    private val lisbon = MapMarker("a", 38.7223, -9.1393, "Lisbon")
    private val belem = MapMarker("b", 38.6916, -9.2160, "Lisbon")
    private val porto = MapMarker("c", 41.1579, -8.6291, "Porto")

    @Test
    fun mercatorGoesThereAndBack() {
        assertEquals(0.5, mercatorX(0.0), 1e-12)
        assertEquals(0.5, mercatorY(0.0), 1e-12)
        assertEquals(38.7223, latitudeOf(mercatorY(38.7223)), 1e-9)
        assertEquals(-9.1393, longitudeOf(mercatorX(-9.1393)), 1e-9)
    }

    @Test
    fun placesCloseOnTheScreenAreOneGroupAndComeApartWhenZoomingIn() {
        val far = clusterMarkers(listOf(lisbon, belem, porto), zoom = 4.0)
        assertEquals(listOf(2, 1), far.map { it.assetIds.size }.sortedDescending())
        assertEquals("Lisbon", far.maxBy { it.assetIds.size }.city)

        val near = clusterMarkers(listOf(lisbon, belem, porto), zoom = 13.0)
        assertEquals(3, near.size)
    }

    @Test
    fun theFirstViewShowsEveryPhoto() {
        val camera = cameraFitting(listOf(lisbon, porto), width = 400.0, height = 800.0)
        val world = worldSize(camera.zoom)
        val spanY = (mercatorY(lisbon.latitude) - mercatorY(porto.latitude)) * world
        assertTrue("both fit: $spanY", spanY <= 800.0)
        assertTrue("and are not lost in the middle: $spanY", spanY >= 400.0)
        assertEquals(14.0, cameraFitting(listOf(lisbon), 400.0, 800.0).zoom, 1e-9)
    }
}
