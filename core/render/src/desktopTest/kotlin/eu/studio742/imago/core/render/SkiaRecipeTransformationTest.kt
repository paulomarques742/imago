package eu.studio742.imago.core.render

import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Tone

/** The recipe in desktop thumbnails: the Skia Bitmap round trip, and the same engine as the export. */
class SkiaRecipeTransformationTest {
    private fun gradient(width: Int, height: Int) = PixelBuffer(width, height).apply {
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = (0xFF shl 24) or ((x * 255 / width) shl 16) or ((y * 255 / height) shl 8) or 64
        }
    }

    @Test
    fun `pixels go through the Skia Bitmap unchanged`() {
        val source = gradient(37, 21)
        assertArrayEquals(source.pixels, source.toSkiaBitmap().toPixelBuffer().pixels)
    }

    @Test
    fun `the transformation is the CPU engine, with the geometry applied`() = runBlocking {
        val source = gradient(80, 40)
        val recipe = EditRecipe(assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "2026-09-15T10:00:00Z",
            tone = Tone(exposure = 0.5f), geometry = Geometry(rotation = 90))
        val transformed = SkiaRecipeTransformation(recipe).transform(source.toSkiaBitmap(), Size.ORIGINAL).toPixelBuffer()
        val expected = RecipePixels.render(source, recipe)
        assertEquals(40 to 80, transformed.width to transformed.height)
        assertArrayEquals(expected.pixels, transformed.pixels)
        assertNotEquals(recipeTransformation(recipe).cacheKey, recipeTransformation(recipe.copy(updatedAt = "later")).cacheKey)
    }
}
