package eu.studio742.imago.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.BitmapPhotoProcessor
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.decodePixels
import eu.studio742.imago.core.render.toImageBitmap
import eu.studio742.imago.core.render.toPixelBuffer
import eu.studio742.imago.core.render.toRenderParameters

/** What the preview thumbnail is reduced to before being processed. */
private const val PREVIEW_MAX_EDGE = 220

/** How many previews stay in memory. */
private const val PREVIEW_CACHE_SIZE = 48

/**
 * The recipe previews, applied to the open photo.
 *
 * The processing is `BitmapPhotoProcessor`'s, the same that produces the export — not an
 * approximation. It runs on the CPU and off the preview's thread, so it competes with nothing there;
 * at 220 px each one costs milliseconds.
 *
 * The cache is keyed by the photo and the recipe: switching photos invalidates everything, which is
 * exactly what is wanted.
 */
object RecipePreviewCache {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(PREVIEW_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > PREVIEW_CACHE_SIZE
    }

    private var sourceKey: String? = null
    private var source: PixelBuffer? = null

    /** Sets the source photo. Switching photos throws away the old previews. */
    fun setSource(assetId: String, bitmap: coil3.Bitmap) = setSource(assetId, bitmap.toPixelBuffer())

    @Synchronized
    fun setSource(assetId: String, pixels: PixelBuffer) {
        if (sourceKey == assetId && source != null) return
        cache.clear()
        sourceKey = assetId
        source = RecipePixels.reduce(pixels, PREVIEW_MAX_EDGE)
    }

    @Synchronized
    fun clear() {
        cache.clear()
        sourceKey = null
        source = null
    }

    @Synchronized
    private fun cached(key: String): ImageBitmap? = cache[key]

    @Synchronized
    private fun store(key: String, bitmap: ImageBitmap) {
        cache[key] = bitmap
    }

    @Synchronized
    private fun sourceFor(assetId: String): PixelBuffer? = source.takeIf { sourceKey == assetId }

    suspend fun preview(assetId: String, recipeId: String, recipe: EditRecipe): ImageBitmap? {
        val key = "$assetId|$recipeId"
        cached(key)?.let { return it }
        val origin = sourceFor(assetId) ?: return null
        val rendered = withContext(Dispatchers.Default) {
            BitmapPhotoProcessor.renderInPlace(origin.copy(), recipe.toRenderParameters()).toImageBitmap()
        }
        store(key, rendered)
        return rendered
    }
}

/**
 * A recipe's preview, computed outside the composition.
 *
 * It returns `null` until it is ready; the caller shows the unprocessed thumbnail in the meantime,
 * so the grid does not open with holes.
 */
@Composable
fun rememberRecipePreview(assetId: String?, recipeId: String, recipe: EditRecipe): State<ImageBitmap?> {
    val result = remember(assetId, recipeId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(assetId, recipeId) {
        if (assetId == null) return@LaunchedEffect
        result.value = RecipePreviewCache.preview(assetId, recipeId, recipe)
    }
    return result
}

/**
 * The sample photos, for when none is open.
 *
 * The recipe library also opens from the bottom bar, far from any photo, and without a source the grid
 * was a wall of black rectangles — precisely the opposite of what a recipe grid is for. They are real
 * photos, with sky, skin, shadows and highlights, because a synthetic gradient says nothing about what
 * a recipe does. They live in the shared resources, so both apps use the same ones.
 */
private const val SAMPLE_COUNT = 9

/** Tells the sample apart from any Immich id, so the two never share cache. */
private const val SAMPLE_KEY_PREFIX = "sample:"

/**
 * The sample photo of this visit, already installed as the source of the previews.
 *
 * It returns the key to pass where the photo's id would go, or `null` while decoding. It is a single
 * one for every tile and not one per tile: the grid exists to compare recipes, and comparing requires
 * the photo underneath to be the same. The variety is between visits — every time the screen opens
 * another one comes out.
 */
@Composable
fun rememberSampleRecipeSource(): String? {
    // `rememberSaveable` so that rotating the tablet does not swap the photo under the recipes.
    val index = rememberSaveable { (0 until SAMPLE_COUNT).random() }
    val key = SAMPLE_KEY_PREFIX + index
    val result = remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        val pixels = withContext(Dispatchers.IO) {
            val name = "/recipe_samples/recipe_sample_%02d.jpg".format(index + 1)
            RecipePreviewCache::class.java.getResourceAsStream(name)?.use { it.readBytes() }?.let(::decodePixels)
        } ?: return@LaunchedEffect
        RecipePreviewCache.setSource(key, pixels)
        result.value = key
    }
    return result.value
}
