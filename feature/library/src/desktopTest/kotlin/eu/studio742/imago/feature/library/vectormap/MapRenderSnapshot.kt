package eu.studio742.imago.feature.library.vectormap

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.studio742.imago.feature.library.MAP_STYLE_URL_FOR_TESTS
import eu.studio742.imago.feature.library.mercatorX
import eu.studio742.imago.feature.library.mercatorY
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draws real places with the real tiles into PNGs, to look at. It needs the network, so it only
 * runs when IMAGO_MAP_SNAPSHOTS names the folder to write to.
 */
class MapRenderSnapshot {
    @Test
    fun drawsPlacesToLookAt(): Unit = runBlocking {
        val out = System.getenv("IMAGO_MAP_SNAPSHOTS")
        assumeTrue("set IMAGO_MAP_SNAPSHOTS to a folder to draw the map", out != null)
        val folder = Files.createDirectories(Path.of(out!!))
        val density = 2f
        val resources = MapResources(folder.resolve("cache"))
        // IMAGO_MAP_STYLE_URL tries another style.
        val setup = resources.load(System.getenv("IMAGO_MAP_STYLE_URL") ?: MAP_STYLE_URL_FOR_TESTS, density)
        val store = TileStore(resources, setup.tileTemplate) {}
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(density), LayoutDirection.Ltr)
        val renderer = VectorMapRenderer(setup, store, measurer)
        val places = mapOf(
            "world" to Triple(30.0, 0.0, 0.8),
            "iberia" to Triple(40.0, -6.0, 5.0),
            "lisbon" to Triple(38.715, -9.14, 11.5),
            "baixa" to Triple(38.7105, -9.137, 15.5),
        )
        for ((name, place) in places) {
            val (latitude, longitude, zoom) = place
            val viewport = Viewport(mercatorX(longitude), mercatorY(latitude), zoom)
            val image = ImageBitmap(1200, 800)
            // The first draws ask for the tiles; it is drawn again once they have arrived.
            repeat(12) {
                CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(1200f, 800f)) { renderer.draw(this, viewport) }
                kotlinx.coroutines.delay(700)
            }
            val png = Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
            Files.write(folder.resolve("$name.png"), png)
        }
        store.close()
    }
}
