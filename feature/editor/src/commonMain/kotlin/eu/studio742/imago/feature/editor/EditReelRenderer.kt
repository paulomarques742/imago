package eu.studio742.imago.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.RecipePixels
import eu.studio742.imago.core.render.toImageBitmap

const val REEL_FPS = 30

/**
 * How much larger than it is shown the CPU keeps the photo. The CPU reduces the original before it
 * crops, so a crop that zooms in needs the margin, or it would show a blurred enlargement.
 */
const val REEL_CPU_MARGIN = 1.5f

/** How the video turns a recipe into the photo of one frame. */
interface ReelPhotoRenderer : AutoCloseable {
    /** The size of the image the masks are normalised to. */
    val sourceWidth: Int
    val sourceHeight: Int

    /** The photo with [recipe], framed and cropped, about the size it is shown. */
    fun render(recipe: EditRecipe): ImageBitmap

    override fun close() = Unit
}

/**
 * The CPU render the export of a photo uses, the same on both platforms.
 *
 * @param maxSide the longest side the photo is shown at; the original is kept [REEL_CPU_MARGIN] larger
 */
class CpuReelPhotoRenderer(source: PixelBuffer, maxSide: Int) : ReelPhotoRenderer {
    private val reduced = RecipePixels.reduce(source, (maxSide * REEL_CPU_MARGIN).toInt())
    override val sourceWidth: Int get() = reduced.width
    override val sourceHeight: Int get() = reduced.height

    override fun render(recipe: EditRecipe): ImageBitmap = RecipePixels.render(reduced, recipe).toImageBitmap()
}

/**
 * The video's frames, one at a time: the photo rendered with the recipe of each instant, and the
 * painter's overlay on top.
 *
 * A recipe is rendered once: the pauses, the comparison and the closing reuse what was already
 * rendered.
 */
class EditReelRenderer(
    private val script: EditReelScript,
    private val photos: ReelPhotoRenderer,
    private val painter: ReelFramePainter,
    val fps: Int = REEL_FPS,
) {
    val frameCount: Int = ((script.durationMs * fps + 999) / 1000).toInt()

    /** How many distinct photos the video needs rendered: what the time to make it depends on. */
    var photoRenders: Int = 0
        private set

    private var lastRecipe: EditRecipe? = null
    private var lastPhoto: ImageBitmap? = null
    private var comparePhoto: ImageBitmap? = null
    private var afterPhoto: ImageBitmap? = null
    private var lastLook: Pair<Int, Any>? = null
    private var lastFrame: ImageBitmap? = null

    fun timeMs(index: Int): Long = index * 1000L / fps

    /**
     * The frame at [index]. A frame that looks like the one before — the photo untouched, a step
     * holding still, the closing once it has faded in — is that same bitmap, and an encoder may
     * reuse its work by identity.
     */
    fun frame(index: Int): ImageBitmap {
        require(index in 0 until frameCount) { "Frame $index outside $frameCount" }
        val frame = script.frameAt(timeMs(index))
        val look = lookOf(frame)
        lastFrame?.takeIf { look == lastLook }?.let { return it }
        return draw(frame).also {
            lastLook = look
            lastFrame = it
        }
    }

    private fun lookOf(frame: ReelFrame): Pair<Int, Any> = frame.segmentIndex to painter.stillKey(frame)

    private fun draw(frame: ReelFrame): ImageBitmap {
        val photo = photoFor(frame.recipe)
        val before = if (frame.segment == ReelSegment.Compare) {
            comparePhoto ?: render(script.compareBefore).also { comparePhoto = it }
        } else {
            null
        }
        // The untouched photo keeps its own background; from the first step on, the edited one's.
        val backdrop = if (frame.segment == ReelSegment.Before) photo else afterPhoto()
        return painter.paint(frame, photo, before, backdrop, photos.sourceWidth, photos.sourceHeight)
    }

    private fun afterPhoto(): ImageBitmap =
        afterPhoto ?: (lastPhoto?.takeIf { lastRecipe == script.after } ?: render(script.after)).also { afterPhoto = it }

    private fun photoFor(recipe: EditRecipe): ImageBitmap {
        lastPhoto?.takeIf { recipe == lastRecipe }?.let { return it }
        afterPhoto?.takeIf { recipe == script.after }?.let { return it }
        return render(recipe).also {
            lastRecipe = recipe
            lastPhoto = it
        }
    }

    private fun render(recipe: EditRecipe): ImageBitmap {
        photoRenders++
        return photos.render(recipe)
    }
}
