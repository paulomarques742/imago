package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class LivePhotoPairsTest {
    private fun photo(id: String, name: String, folder: String? = "a") = PairingEntry(id, folder, name, isVideo = false)
    private fun clip(id: String, name: String, folder: String? = "a") = PairingEntry(id, folder, name, isVideo = true)

    @Test
    fun `a photo and a mov with the same name in the same folder are one live photo`() {
        val pairs = pairLivePhotosByName(listOf(photo("p", "IMG_0001.HEIC"), clip("c", "IMG_0001.MOV")))
        assertEquals(mapOf("p" to "c"), pairs)
    }

    @Test
    fun `the case of the name and of the extension does not matter`() {
        assertEquals(mapOf("p" to "c"), pairLivePhotosByName(listOf(photo("p", "img_0001.jpg"), clip("c", "IMG_0001.mov"))))
    }

    @Test
    fun `a clip without a photo and a photo without a clip stay as they are`() {
        assertTrue(pairLivePhotosByName(listOf(photo("p", "IMG_0001.HEIC"), clip("c", "IMG_0002.MOV"))).isEmpty())
        assertTrue(pairLivePhotosByName(listOf(photo("p", "IMG_0001.HEIC"))).isEmpty())
    }

    @Test
    fun `the same name in another folder is another file`() {
        assertTrue(pairLivePhotosByName(listOf(photo("p", "IMG_0001.HEIC", "a"), clip("c", "IMG_0001.MOV", "b"))).isEmpty())
    }

    @Test
    fun `only a mov is a live photo's clip, and only a still image has one`() {
        assertTrue(pairLivePhotosByName(listOf(photo("p", "DSC_1.JPG"), clip("c", "DSC_1.MP4"))).isEmpty())
        assertTrue(pairLivePhotosByName(listOf(photo("p", "IMG_1.PNG"), clip("c", "IMG_1.MOV"))).isEmpty())
    }

    @Test
    fun `a clip belongs to one photo, the heic before the jpg`() {
        val pairs = pairLivePhotosByName(listOf(photo("jpg", "IMG_1.JPG"), photo("heic", "IMG_1.HEIC"), clip("c", "IMG_1.MOV")))
        assertEquals(mapOf("heic" to "c"), pairs)
    }

    @Test
    fun `a folder of pairs is told apart pair by pair`() {
        val pairs = pairLivePhotosByName(
            listOf(photo("p1", "IMG_1.HEIC"), clip("c1", "IMG_1.MOV"), photo("p2", "IMG_2.HEIC"), clip("c2", "IMG_2.MOV"), clip("lone", "movie.mov")),
        )
        assertEquals(mapOf("p1" to "c1", "p2" to "c2"), pairs)
    }

    private val root: Path = Files.createTempDirectory("imago-live-pairs")

    @Test
    fun `a synced folder shows the pair once, as the photo with its movement`() = runBlocking {
        val folder = Files.createDirectories(root.resolve("Phone")).toRealPath()
        fun jpeg(name: String) = folder.resolve(name).also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", it.toFile()) }
        val still = jpeg("IMG_0001.jpg")
        val clipFile = folder.resolve("IMG_0001.mov").also { Files.write(it, ByteArray(16)) }
        val plain = jpeg("IMG_0002.jpg")
        DesktopDataGraph(root.resolve("app")) { file: File -> file.delete() }.use { graph ->
            graph.folders.addFolder(folder)
            val rows = graph.database.assetDao().allAssets(DEVICE_LIBRARY_ID).associateBy { it.id }

            assertEquals(setOf(still.toUri().toString(), plain.toUri().toString()), rows.keys)
            assertEquals(clipFile.toUri().toString(), rows.getValue(still.toUri().toString()).livePhotoVideoId)
            assertTrue(rows.getValue(still.toUri().toString()).livePhotoKnown)
            assertNull(rows.getValue(plain.toUri().toString()).livePhotoVideoId)

            // The next sync gives the same: the pair is written where a sync puts it back from.
            graph.folders.syncCatalog()
            val again = graph.database.assetDao().allAssets(DEVICE_LIBRARY_ID).associateBy { it.id }
            assertEquals(clipFile.toUri().toString(), again.getValue(still.toUri().toString()).livePhotoVideoId)
            assertNotNull(again[plain.toUri().toString()])
        }
    }
}
