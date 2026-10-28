package eu.studio742.imago.core.render

import coil3.size.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Tone

/** Edited photos rendered once and read back: what makes a second visit skip the recipe. */
class RecipeRenderCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @After
    fun tearDown() = RecipeRenderCache.uninstall()

    private fun install() = RecipeRenderCache.install(folder.root.toOkioPath(), 8L * 1024L * 1024L)

    private fun gradient(width: Int, height: Int) = PixelBuffer(width, height).apply {
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = (0xFF shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 64
        }
    }

    private val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "2026-10-27T10:00:00Z",
        tone = Tone(exposure = 0.5f))

    @Test
    fun `a RAW's key is known without developing it, and follows its file, its save and its size`() {
        val original = folder.newFile("a.raf").apply { writeBytes(ByteArray(40_000) { (it * 31).toByte() }) }
        val same = RecipeRenderCache.rawKey("recipe:a:21:t1", original, 512)
        assertEquals(same, RecipeRenderCache.rawKey("recipe:a:21:t1", original, 512))
        assertNotEquals("another save", same, RecipeRenderCache.rawKey("recipe:a:21:t2", original, 512))
        assertNotEquals("another size", same, RecipeRenderCache.rawKey("recipe:a:21:t1", original, 2048))
        val longer = folder.newFile("b.raf").apply { writeBytes(original.readBytes() + 1) }
        assertNotEquals("another length", same, RecipeRenderCache.rawKey("recipe:a:21:t1", longer, 512))
        val edited = folder.newFile("c.raf").apply { writeBytes(original.readBytes().also { it[20_000] = 99 }) }
        assertNotEquals("a different middle", same, RecipeRenderCache.rawKey("recipe:a:21:t1", edited, 512))
    }

    @Test
    fun `a phone's RAW is named without opening it, by what the phone says of it`() {
        val key = RecipeRenderCache.identityKey("recipe:a:21:t1", "content://media/external/file/7:28000000:1761600000", 512)
        assertEquals(key, RecipeRenderCache.identityKey("recipe:a:21:t1", "content://media/external/file/7:28000000:1761600000", 512))
        assertNotEquals("saved again", key, RecipeRenderCache.identityKey("recipe:a:21:t2", "content://media/external/file/7:28000000:1761600000", 512))
        assertNotEquals("changed on the phone", key, RecipeRenderCache.identityKey("recipe:a:21:t1", "content://media/external/file/7:28000000:1761600999", 512))
        assertNotEquals("another size", key, RecipeRenderCache.identityKey("recipe:a:21:t1", "content://media/external/file/7:28000000:1761600000", 2048))
    }

    @Test
    fun `a developed RAW goes through the cache as bytes and comes back as an image`() {
        install()
        val original = folder.newFile("d.raf").apply { writeBytes(ByteArray(10_000) { it.toByte() }) }
        val key = RecipeRenderCache.rawKey("default3", original, 256)
        assertNull(RecipeRenderCache.read(key))
        val rendered = gradient(64, 48).toSkiaBitmap()
        runBlocking { withTimeout(10_000) { RecipeRenderCache.write(key) { rendered.encodeForRenderCache() }?.join() } }
        val back = decodeRenderCacheBytes(requireNotNull(RecipeRenderCache.read(key)))
        assertEquals(64 to 48, requireNotNull(back).let { it.width to it.height })
    }

    @Test
    fun `the key follows the save, the size and the pixels`() {
        val grey = { _: Int, _: Int -> 0xFF808080.toInt() }
        val key = RecipeRenderCache.key("recipe:a:3:t1", 100, 80, grey)
        assertEquals(key, RecipeRenderCache.key("recipe:a:3:t1", 100, 80, grey))
        assertNotEquals(key, RecipeRenderCache.key("recipe:a:3:t2", 100, 80, grey))
        assertNotEquals(key, RecipeRenderCache.key("recipe:a:3:t1", 80, 100, grey))
        // Same size, another photo — a server that regenerated its thumbnail, for one.
        assertNotEquals(key, RecipeRenderCache.key("recipe:a:3:t1", 100, 80) { x, y -> if (x > 50) -1 else grey(x, y) })
    }

    @Test
    fun `what is written is read back, and nothing else is`() = runBlocking {
        install()
        RecipeRenderCache.write("render") { byteArrayOf(1, 2, 3) }!!.join()
        assertArrayEquals(byteArrayOf(1, 2, 3), RecipeRenderCache.read("render"))
        assertNull(RecipeRenderCache.read("another"))
    }

    @Test
    fun `without a folder nothing is kept`() = runBlocking {
        assertNull(RecipeRenderCache.write("render") { byteArrayOf(1) })
        assertNull(RecipeRenderCache.read("render"))
    }

    @Test
    fun `the transformation keeps its render and decodes it the next time`() = runBlocking {
        install()
        val transformation = SkiaRecipeTransformation(recipe)
        val source = gradient(40, 30).toSkiaBitmap()
        val key = RecipeRenderCache.key(transformation.cacheKey, 40, 30, source::getColor)

        transformation.transform(source, Size.ORIGINAL)
        withTimeout(5_000) { while (RecipeRenderCache.read(key) == null) delay(10) }

        // What is on disk is what comes back: a solid red stands in for the render, so the next pass
        // can only have read it, not rendered again.
        val red = PixelBuffer(40, 30).apply { pixels.fill(0xFFFF0000.toInt()) }.toSkiaBitmap()
        RecipeRenderCache.write(key) { Image.makeFromBitmap(red).encodeToData(EncodedImageFormat.PNG)!!.bytes }!!.join()
        val second = transformation.transform(source, Size.ORIGINAL)
        assertEquals(40, second.width)
        assertEquals(30, second.height)
        assertEquals(0xFFFF0000.toInt(), second.getColor(20, 15))
    }
}
