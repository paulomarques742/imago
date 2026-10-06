package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.core.render.withRecipe
import coil3.request.crossfade
import coil3.PlatformContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.yield
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.changesTheImage

/**
 * The side of photos with a recipe was getting too expensive for the stage.
 *
 * Applying a recipe is `BitmapPhotoProcessor` walking the whole image on the CPU — the tone pass, the
 * detail pyramid, the masks. At the size of Immich's preview that is over a million pixels, and that
 * is measured in seconds, not frames.
 *
 * Coil keeps the result in memory, but only serves it to whoever asks for a size it can give: it
 * accepts a bitmap larger than requested, which it can reduce, and refuses a smaller one — and when
 * the request carries transformations the size goes into the key too. The stage asked for each photo
 * at the size it was drawn at, and so enlarging an element was enough for the cache entry to stop
 * serving: the recipe was applied again, from the start, on every frame of the gesture.
 *
 * That is why the requests here have a fixed size, which is not the element's: what the cache keeps
 * the first time serves every size the photo may be shown at, and two copies of the same photo in
 * different boxes share the same work instead of fighting over it. Scaling to the element's box is
 * left to `ContentScale`, which is GPU work.
 *
 * The price is a sharpness ceiling on the stage. Export does not go through here — it downloads the
 * original and applies the recipe at the page's resolution —, so the final file loses nothing.
 */
private const val StagePhotoEdge = 1024

/**
 * The draft's size: the thumbnail Immich already serves, processed in about a thirtieth of the large
 * version's work, so there is something to show while the other one does not arrive.
 */
private const val DraftPhotoEdge = 256

/**
 * The two requests the stage shows a photo with.
 *
 * [draft] only exists when there is a recipe to apply: without one the image appears at network
 * speed and a second layer underneath would be extra drawing.
 */
internal data class ComposerPhotoRequests(
    val draft: ImageRequest?,
    val full: ImageRequest,
)

/**
 * The identity Coil knows a recipe by.
 *
 * It is the same key `EditRecipeTransformation` uses for the cache: if two recipes share this key,
 * they share the cache entry, and rebuilding the request because of the difference between them would
 * change nothing that is seen.
 */
private fun EditRecipe.renderIdentity() = "$assetId:$processVersion:$updatedAt"

/** The requests for a stage photo, rebuilt only when its recipe changes. */
@Composable
internal fun rememberComposerPhotoRequests(
    assetId: String,
    recipe: EditRecipe,
    previewUrl: (String) -> String,
    thumbnailUrl: (String) -> String,
    apiKey: (String) -> String,
): ComposerPhotoRequests {
    val context = LocalPlatformContext.current
    return remember(context, assetId, recipe.renderIdentity()) {
        composerPhotoRequests(context, assetId, recipe, previewUrl, thumbnailUrl, apiKey)
    }
}

/** The pair of requests, outside the composition — the warm-up takes them from here too. */
private fun composerPhotoRequests(
    context: PlatformContext,
    assetId: String,
    recipe: EditRecipe,
    previewUrl: (String) -> String,
    thumbnailUrl: (String) -> String,
    apiKey: (String) -> String,
): ComposerPhotoRequests {
    val key = apiKey(assetId).takeIf(String::isNotBlank)
    val edited = recipe.takeIf(EditRecipe::changesTheImage)
    fun request(url: String, edge: Int, fade: Boolean = false) = ImageRequest.Builder(context)
        .data(url)
        .libraryAuth(key)
        // Fixed on purpose: see [StagePhotoEdge]. `INEXACT` so Coil can serve the bitmap it has without
        // decoding it again because of a few pixels of difference.
        .size(edge)
        // `FIT`, and not the `FILL` the stage's `ContentScale.Crop` would impose: with `FILL` the longer
        // side goes above [StagePhotoEdge] and the recipe goes through half a million more pixels for a
        // sharpness the element's box does not show. The crop belongs to the drawing.
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .crossfade(fade)
        .withRecipe(edited)
        .build()
    return ComposerPhotoRequests(
        draft = edited?.let { request(thumbnailUrl(assetId), DraftPhotoEdge) },
        // The swap from draft to large version fades, so there is no jump in sharpness in the middle of
        // the screen. When the image comes from memory Coil skips the transition, so this is only seen
        // the first time.
        full = request(previewUrl(assetId), StagePhotoEdge, fade = edited != null),
    )
}

/**
 * Has the photos the stage is going to show processed in the background.
 *
 * Without this the recipe's work happened in front of whoever was looking: opening a composition with
 * six edited photos meant watching six empty boxes fill one by one. Here it is the same work, done
 * before it is needed and with the same cache key — when the element asks for the image, it is
 * already in memory and appears without a transition.
 *
 * The drafts all go first: they are cheap and are what takes the empty boxes off the screen fastest.
 * The large versions come afterwards, one at a time, so there are not six processings fighting over
 * the same cores the stage uses to respond to the finger.
 */
@Composable
internal fun WarmComposerPhotos(
    project: CompositionProject,
    previewUrl: (String) -> String,
    thumbnailUrl: (String) -> String,
    apiKey: (String) -> String,
) {
    val context = LocalPlatformContext.current
    val photos = project.elements.filterIsInstance<CompositionElement.Photo>()
        .map { it.media.assetId to it.recipe.recipe }
    // The key is what tells one warm-up from the next: moving or resizing an element changes no image,
    // and re-reading the whole list on every frame of a drag would be absurd.
    val identities = photos.map { (assetId, recipe) -> "$assetId|${recipe.renderIdentity()}" }
    LaunchedEffect(identities) {
        val loader = SingletonImageLoader.get(context)
        val requests = photos.map { (assetId, recipe) ->
            composerPhotoRequests(context, assetId, recipe, previewUrl, thumbnailUrl, apiKey)
        }
        // Coil handles its own dispatch: it downloads, decodes and transforms off the main thread. What
        // runs here is the queue.
        requests.mapNotNull(ComposerPhotoRequests::draft).forEach { loader.execute(it) }
        requests.forEach {
            // One stop per photo: applying a recipe cannot be cancelled halfway, and this is where
            // closing the composition can interrupt the queue instead of running it to the end.
            yield()
            loader.execute(it.full)
        }
    }
}
