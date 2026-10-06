package eu.studio742.imago.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Tone
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * The Android data layer, assembled on desktop with a real folder.
 *
 * It proves the three pieces only desktop has — embedded SQLite, the DPAPI configuration and the
 * folder library — working with the shared repositories.
 */
class DesktopDataGraphTest {
    @Test
    fun `a folder enters the catalogue, keeps recipes and favourites, and the configuration survives reopening`() = runBlocking {
        val root = Files.createTempDirectory("imago-data-graph")
        val photos = Files.createDirectories(root.resolve("Photos/Café"))
        ImageIO.write(BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", photos.resolve("praia.png").toFile())
        ImageIO.write(BufferedImage(30, 40, BufferedImage.TYPE_INT_RGB), "jpg", photos.resolve("retrato.jpg").toFile())
        Files.write(photos.resolve("mergulho.mp4"), ByteArray(16))
        Files.write(photos.resolve("notas.txt"), ByteArray(4))

        DesktopDataGraph(root.resolve("app")).use { graph ->
            graph.folders.addFolder(root.resolve("Photos"))
            val rows = graph.database.assetDao().allAssets(DEVICE_LIBRARY_ID)
            assertEquals(setOf("praia.png", "retrato.jpg", "mergulho.mp4"), rows.map { it.originalFileName }.toSet())
            val beach = rows.single { it.originalFileName == "praia.png" }
            assertEquals(64L to 48L, beach.width to beach.height)
            assertEquals("IMAGE", beach.type)
            assertEquals("VIDEO", rows.single { it.originalFileName == "mergulho.mp4" }.type)
            assertEquals("Café", graph.folders.albums().single().name)
            assertEquals(3, graph.folders.albums().single().assetCount)

            // The recipe of a photo in the folder: the content hash reads the file through its URI.
            val assetId = AssetReference(DEVICE_LIBRARY_ID, beach.id).encode()
            val recipe = EditRecipe(assetId = assetId, originalChecksum = "", createdAt = "2026-09-15T10:00:00Z",
                updatedAt = "2026-09-15T10:00:00Z", tone = Tone(exposure = 0.7f))
            graph.recipes.save(recipe)
            assertEquals(0.7f, graph.recipes.get(assetId)!!.tone.exposure)

            // The favourite does not exist in the file system: it survives rebuilding the catalogue.
            graph.folders.setFavorite(beach.id, true)
            graph.folders.syncCatalog()
            assertTrue(graph.database.assetDao().asset(DEVICE_LIBRARY_ID, beach.id)!!.isFavorite)
            assertTrue(graph.database.assetDao().asset(DEVICE_LIBRARY_ID, beach.id)!!.hasLocalRecipe)

            graph.configuration.lastExportLibraryId = "configuration-secret"
        }

        val stored = Files.readAllBytes(root.resolve("app").resolve(DesktopDataGraph.PREFERENCES_FILE))
        assertFalse("the configuration is not stored as plain text", String(stored, Charsets.ISO_8859_1).contains("configuration-secret"))

        DesktopDataGraph(root.resolve("app")).use { graph ->
            assertEquals("configuration-secret", graph.configuration.lastExportLibraryId)
            assertEquals(listOf(root.resolve("Photos").toRealPath().toString()), graph.folders.folders.value)
            assertNotNull(graph.database.assetDao().allAssets(DEVICE_LIBRARY_ID).singleOrNull { it.isFavorite })
        }
    }
}
