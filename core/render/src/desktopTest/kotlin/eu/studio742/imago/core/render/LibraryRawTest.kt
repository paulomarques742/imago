package eu.studio742.imago.core.render

import coil3.Extras
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Tone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException

class LibraryRawTest {
    private val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "", tone = Tone(exposure = .5f))
    private val uri = coil3.Uri("https", "server", "/api/assets/a/thumbnail")

    private fun options(raw: LibraryRaw?, recipe: EditRecipe? = this.recipe, side: Int = 400) = Options(
        PlatformContext.INSTANCE,
        size = Size(side, side),
        extras = Extras.Builder().apply {
            set(LibraryRawOriginal, raw)
            set(rawRecipeKey, recipe)
        }.build(),
    )

    @Test fun `only a marked request has a key, and it follows the photo, its original, the save and the size`() {
        val photo = LibraryRaw("a", "sum")
        val key = libraryRawKey(options(photo))
        assertNotNull(key)
        assertNull(libraryRawKey(options(null)))
        assertEquals(key, libraryRawKey(options(LibraryRaw("a", "sum"))))
        assertNotEquals(key, libraryRawKey(options(LibraryRaw("a", "other"))))
        assertNotEquals(key, libraryRawKey(options(LibraryRaw("b", "sum"))))
        assertNotEquals(key, libraryRawKey(options(photo, recipe.copy(tone = Tone(exposure = 1f), updatedAt = "saved again"))))
        assertNotEquals(key, libraryRawKey(options(photo, side = 1500)))
        // The grid's sizes share one develop.
        assertEquals(key, libraryRawKey(options(photo, side = 450)))
    }

    @Test fun `the original is handed to the decoder and goes away with it`() = runBlocking {
        val asked = mutableListOf<String>()
        val factory = LibraryRawFetcher.Factory { id, file -> asked += id; file.writeBytes(byteArrayOf(1, 2, 3)) }
        val fetcher = checkNotNull(factory.create(uri, options(LibraryRaw("a", "sum")), ImageLoader(PlatformContext.INSTANCE)))
        val result = fetcher.fetch() as SourceFetchResult
        val file = result.source.file().toFile()
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(listOf("a"), asked)
        result.source.close()
        assertFalse(file.exists())
    }

    @Test fun `an original that cannot be had falls back to the server's image`() = runBlocking {
        var left: File? = null
        val factory = LibraryRawFetcher.Factory { _, file -> left = file; throw IOException("offline") }
        val fetcher = checkNotNull(factory.create(uri, options(LibraryRaw("a", "sum")), ImageLoader(PlatformContext.INSTANCE)))
        assertNull(fetcher.fetch())
        assertFalse(checkNotNull(left).exists())
    }

    @Test fun `a request that is not marked is left to the others`() {
        val factory = LibraryRawFetcher.Factory { _, _ -> error("not asked") }
        assertNull(factory.create(uri, options(null), ImageLoader(PlatformContext.INSTANCE)))
    }
}
