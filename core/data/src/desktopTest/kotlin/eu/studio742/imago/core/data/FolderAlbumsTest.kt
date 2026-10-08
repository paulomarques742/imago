package eu.studio742.imago.core.data

import eu.studio742.imago.core.model.AssetReference
import eu.studio742.imago.core.model.DEVICE_LIBRARY_ID
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.FolderTransfer
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.name

/** The computer's folders as albums: made, filled, renamed and deleted on disk. */
class FolderAlbumsTest {
    private val root: Path = Files.createTempDirectory("imago-folder-albums")
    private val home: Path = Files.createDirectories(root.resolve("Photos")).toRealPath()
    private val work: Path = Files.createDirectories(root.resolve("Work")).toRealPath()
    private val trashed = mutableListOf<String>()

    private fun photo(folder: Path, name: String): Path {
        Files.createDirectories(folder)
        val file = folder.resolve(name)
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", file.toFile())
        return file
    }

    private fun graph() = DesktopDataGraph(root.resolve("app")) { file: File ->
        trashed += file.name
        file.deleteRecursively()
    }

    private fun DesktopDataGraph.idOf(file: Path): String =
        runBlocking { database.assetDao().allAssets(DEVICE_LIBRARY_ID) }.single { Path.of(java.net.URI(it.id)) == file }.id

    @Test
    fun `the chosen folders take photos and the ones inside them are albums to change`() = runBlocking {
        photo(home, "loose.png")
        photo(home.resolve("Beach"), "wave.png")
        graph().use { graph ->
            graph.folders.addFolder(home)
            val albums = graph.folders.albums().associateBy { it.name }

            assertTrue(albums.getValue("Photos").canEditContent)
            assertFalse("a chosen folder is changed in Settings", albums.getValue("Photos").isOwned)
            assertTrue(albums.getValue("Beach").isOwned)
            assertTrue(albums.values.all { it.isFolder })
            val error = runCatching { graph.folders.renameAlbum(albums.getValue("Photos").id, "Other") }.exceptionOrNull()
            assertEquals(UserMessage.FOLDER_FILE_UNAVAILABLE, (error as UserMessageException).userMessage)
        }
    }

    @Test
    fun `with two chosen folders a new album goes where it was asked, and the moved photo keeps its recipe and favourite`() = runBlocking {
        val wave = photo(home.resolve("Beach"), "wave.png")
        val report = photo(work, "report.png")
        graph().use { graph ->
            graph.folders.addFolder(home)
            graph.folders.addFolder(work)
            val waveId = graph.idOf(wave)
            assertEquals(listOf(false, true), graph.folders.albumPlaces(listOf(graph.idOf(report))).map { it.suggested })
            assertTrue("without photos none is suggested", graph.folders.albumPlaces(emptyList()).none { it.suggested })

            val reference = AssetReference(DEVICE_LIBRARY_ID, waveId).encode()
            graph.recipes.save(EditRecipe(assetId = reference, originalChecksum = "", createdAt = "2026-10-08T10:00:00Z",
                updatedAt = "2026-10-08T10:00:00Z", tone = Tone(exposure = 0.4f)))
            graph.folders.setFavorite(waveId, true)

            val album = graph.folders.createFolderAlbum("Trip/2026", listOf(waveId), FolderTransfer.MOVE, place = work.toString())

            val moved = work.resolve("Trip 2026").resolve("wave.png")
            assertEquals(work.resolve("Trip 2026").toString(), album.id)
            assertTrue(moved.exists())
            assertFalse(wave.exists())
            val movedId = graph.idOf(moved)
            val row = graph.database.assetDao().asset(DEVICE_LIBRARY_ID, movedId)!!
            assertTrue(row.isFavorite)
            assertTrue(row.hasLocalRecipe)
            assertEquals(0.4f, graph.recipes.get(AssetReference(DEVICE_LIBRARY_ID, movedId).encode())!!.tone.exposure)
            assertEquals(null, graph.database.assetDao().asset(DEVICE_LIBRARY_ID, waveId))
        }
    }

    @Test
    fun `a copy keeps the original, and a taken name gets a number`() = runBlocking {
        val first = photo(home.resolve("Beach"), "wave.png")
        val second = photo(home.resolve("Hills"), "wave.png")
        graph().use { graph ->
            graph.folders.addFolder(home)
            val beach = graph.folders.albums().single { it.name == "Beach" }

            val result = graph.folders.fileIntoAlbum(beach.id, listOf(graph.idOf(first), graph.idOf(second)), FolderTransfer.COPY)

            assertEquals(1, result.added)
            assertEquals(1, result.alreadyThere)
            assertTrue(second.exists())
            assertTrue(home.resolve("Beach").resolve("wave (2).png").exists())
            assertEquals(2, graph.folders.albums().single { it.name == "Beach" }.assetCount)
        }
    }

    @Test
    fun `renaming takes the folder with everything in it, and the recipes follow`() = runBlocking {
        val wave = photo(home.resolve("Beach"), "wave.png")
        Files.write(home.resolve("Beach").resolve("notes.txt"), ByteArray(4))
        graph().use { graph ->
            graph.folders.addFolder(home)
            val reference = AssetReference(DEVICE_LIBRARY_ID, graph.idOf(wave)).encode()
            graph.recipes.save(EditRecipe(assetId = reference, originalChecksum = "", createdAt = "2026-10-08T10:00:00Z",
                updatedAt = "2026-10-08T10:00:00Z", tone = Tone(exposure = 0.2f)))
            val beach = graph.folders.albums().single { it.name == "Beach" }

            val id = graph.folders.renameAlbum(beach.id, "Coast")

            val coast = home.resolve("Coast")
            assertEquals(coast.toString(), id)
            assertTrue(coast.resolve("notes.txt").exists())
            assertFalse(home.resolve("Beach").exists())
            assertEquals(listOf("Coast"), graph.folders.albums().map { it.name })
            assertNotNull(graph.recipes.get(AssetReference(DEVICE_LIBRARY_ID, graph.idOf(coast.resolve("wave.png"))).encode()))

            // Only the case changes: Windows would otherwise see the same folder and do nothing.
            graph.folders.renameAlbum(id, "COAST")
            assertEquals(listOf("COAST"), Files.list(home).use { list -> list.map { it.name }.toList() })
        }
    }

    @Test
    fun `deleting an album sends its photos to the Recycle Bin, and the folder when nothing else is left`() = runBlocking {
        photo(home.resolve("Beach"), "wave.png")
        Files.write(home.resolve("Beach").resolve("notes.txt"), ByteArray(4))
        photo(home.resolve("Hills"), "peak.png")
        graph().use { graph ->
            graph.folders.addFolder(home)
            val albums = graph.folders.albums().associateBy { it.name }

            graph.folders.deleteAlbum(albums.getValue("Beach").id)
            graph.folders.deleteAlbum(albums.getValue("Hills").id)

            assertEquals(listOf("wave.png", "peak.png", "Hills"), trashed)
            assertTrue("other files keep the folder", home.resolve("Beach").resolve("notes.txt").exists())
            assertFalse(home.resolve("Hills").exists())
            assertTrue(graph.folders.albums().isEmpty())
        }
    }

    @Test
    fun `a file takes a new name with its extension, its recipe follows, and a name the folder has is refused`() = runBlocking {
        val cake = photo(home.resolve("Party"), "20261003_173126.png")
        photo(home.resolve("Party"), "taken.png")
        graph().use { graph ->
            graph.folders.addFolder(home)
            val id = graph.idOf(cake)
            val reference = AssetReference(DEVICE_LIBRARY_ID, id).encode()
            graph.recipes.save(EditRecipe(assetId = reference, originalChecksum = "", createdAt = "2026-10-08T10:00:00Z",
                updatedAt = "2026-10-08T10:00:00Z", tone = Tone(exposure = 0.3f)))

            val renamed = graph.folders.renameAsset(id, "Birthday cake")

            val file = home.resolve("Party").resolve("Birthday cake.png")
            assertTrue(file.exists())
            assertFalse(cake.exists())
            assertEquals(graph.idOf(file), renamed)
            assertEquals("Birthday cake.png", graph.database.assetDao().asset(DEVICE_LIBRARY_ID, renamed)!!.originalFileName)
            assertEquals(0.3f, graph.recipes.get(AssetReference(DEVICE_LIBRARY_ID, renamed).encode())!!.tone.exposure)

            val error = runCatching { graph.folders.renameAsset(renamed, "TAKEN") }.exceptionOrNull()
            assertEquals(UserMessage.FILE_NAME_TAKEN, (error as UserMessageException).userMessage)

            // Only the case changes: Windows would otherwise see the same file and do nothing.
            graph.folders.renameAsset(renamed, "birthday cake")
            assertTrue(Files.list(home.resolve("Party")).use { list -> list.anyMatch { it.name == "birthday cake.png" } })
        }
    }

    @Test
    fun `move or copy, once remembered, survives reopening`() {
        graph().use { it.folders.rememberTransfer(FolderTransfer.COPY) }
        graph().use { assertEquals(FolderTransfer.COPY, it.folders.rememberedTransfer.value) }
    }
}
