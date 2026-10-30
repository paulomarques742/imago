package eu.studio742.imago.core.render

import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Tone
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LibraryRawTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() = RecipeRenderCache.uninstall()

    private val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "saved", tone = Tone(exposure = .5f))
    private val photo = LibraryRaw("a", "sum")
    private val uri = coil3.Uri("https", "server", "/api/assets/a/thumbnail")

    private fun options(raw: LibraryRaw?, recipe: EditRecipe? = this.recipe, side: Int = 400, download: Boolean = false) = Options(
        PlatformContext.INSTANCE,
        size = Size(side, side),
        extras = Extras.Builder().apply {
            set(LibraryRawOriginal, raw)
            set(rawRecipeKey, recipe)
            set(LibraryRawDownload, download)
        }.build(),
    )

    private fun fetcher(download: suspend (String, File) -> Unit, allowed: Boolean = true) =
        LibraryRawFetcher.Factory(download).create(uri, options(photo, download = allowed), ImageLoader(PlatformContext.INSTANCE))

    @Test fun `only a marked request has a key, and it follows the photo, its original, the save and the size`() {
        val key = libraryRawKey(options(photo))
        assertNotNull(key)
        assertNull(libraryRawKey(options(null)))
        assertEquals(key, libraryRawKey(options(LibraryRaw("a", "sum"))))
        assertNotEquals(key, libraryRawKey(options(LibraryRaw("a", "other"))))
        assertNotEquals(key, libraryRawKey(options(LibraryRaw("b", "sum"))))
        assertNotEquals(key, libraryRawKey(options(photo, recipe.copy(updatedAt = "saved again"))))
        assertNotEquals(key, libraryRawKey(options(photo, side = 1500)))
        // Two sides only: the grid's and the viewer's, which the editor can keep.
        assertEquals(key, libraryRawKey(options(photo, side = 300)))
        assertEquals(libraryRawKey(photo, recipe, LIBRARY_RAW_TILE_SIDE), key)
        assertEquals(libraryRawKey(photo, recipe, LIBRARY_RAW_FULL_SIDE), libraryRawKey(options(photo, side = 1500)))
    }

    @Test fun `the original is handed to the decoder and goes away with it`() = runBlocking {
        val asked = mutableListOf<String>()
        val fetcher = checkNotNull(fetcher({ id, file -> asked += id; file.writeBytes(byteArrayOf(1, 2, 3)) }))
        val result = fetcher.fetch() as SourceFetchResult
        val file = result.source.file().toFile()
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(listOf("a"), asked)
        result.source.close()
        assertFalse(file.exists())
    }

    @Test fun `an original that cannot be had falls back to the approximation`() = runBlocking {
        var left: File? = null
        val fetcher = checkNotNull(fetcher({ _, file -> left = file; throw IOException("offline") }))
        assertNull(fetcher.fetch())
        assertFalse(checkNotNull(left).exists())
    }

    @Test fun `nothing is downloaded unless the request asks for it`() {
        assertNull(fetcher({ _, _ -> error("not asked") }, allowed = false))
        assertNull(LibraryRawFetcher.Factory { _, _ -> error("not asked") }
            .create(uri, options(null, download = true), ImageLoader(PlatformContext.INSTANCE)))
    }

    @Test fun `the editor's development is kept at the grid's and the viewer's sides`() = runBlocking {
        RecipeRenderCache.install(folder.root.toOkioPath(), 8L * 1024L * 1024L)
        val development = FloatPixelBuffer(64, 48, FloatArray(64 * 48 * 3) { (it % 97) / 97f })
        LibraryRawEdits.keep(photo, recipe, development, rawExposure = 0f).join()
        withTimeout(5_000) {
            for (side in listOf(LIBRARY_RAW_TILE_SIDE, LIBRARY_RAW_FULL_SIDE)) {
                while (RecipeRenderCache.read(libraryRawKey(photo, recipe, side)) == null) delay(10)
            }
        }
        assertNull(RecipeRenderCache.read(libraryRawKey(photo, recipe.copy(updatedAt = "saved again"), LIBRARY_RAW_TILE_SIDE)))
        // What the memory cache's key is told apart by, without reading the render.
        assertEquals(true, RecipeRenderCache.contains(libraryRawKey(photo, recipe, LIBRARY_RAW_FULL_SIDE)))
        assertEquals(false, RecipeRenderCache.contains(libraryRawKey(photo, recipe.copy(updatedAt = "saved again"), LIBRARY_RAW_FULL_SIDE)))
    }
}
