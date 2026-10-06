package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.requireUser
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.EditRecipe
import java.util.concurrent.CancellationException
import java.util.stream.IntStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A recipe applied to pixels in an array, from start to finish: the geometry sampled as the shader
 * samples it ([FrameGeometry]) and then the CPU processor.
 *
 * It is the desktop path — the editor preview, the export, thumbnails with a recipe — and what any
 * PixelBuffer uses. The rows are split across cores: each one only writes to its part of the array.
 */
object RecipePixels {
    fun render(source: PixelBuffer, recipe: EditRecipe): PixelBuffer {
        requireUser(recipe.processVersion <= CURRENT_PROCESS_VERSION, UserMessage.EDIT_NEEDS_NEWER_APP)
        return render(source, recipe.toRenderParameters())
    }

    /**
     * With the parameters already resolved — what the editor has at hand on every slider change.
     *
     * @param renderScale how big [source] is relative to the image it came from. A preview draft
     *   passes its reduction so that grain, which lives in image pixels, appears with the file's
     *   granularity and not the draft's.
     */
    fun render(source: PixelBuffer, params: RenderParameters, renderScale: Float = 1f): PixelBuffer {
        val frame = params.frameGeometry(source.width, source.height)
        val turns = params.normalizedQuarterTurns()
        val width = ((if (turns % 2 == 1) source.height else source.width) * params.cropWidth).roundToInt().coerceAtLeast(1)
        val height = ((if (turns % 2 == 1) source.width else source.height) * params.cropHeight).roundToInt().coerceAtLeast(1)
        val framed = PixelBuffer(width, height)
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        IntStream.range(0, height).parallel().forEach { y ->
            val uv = FloatArray(2)
            for (x in 0 until width) {
                frame.imageFromFramed((x + .5f) / width, (y + .5f) / height, uv)
                framed.pixels[y * width + x] = bilinear(source, uv[0] * source.width - .5f, uv[1] * source.height - .5f)
            }
        }
        return BitmapPhotoProcessor.renderInPlace(framed, params, frame, renderScale)
    }

    /** The image reduced to fit [maxSide], with the same sampling as the geometry. */
    fun reduce(source: PixelBuffer, maxSide: Int): PixelBuffer {
        val scale = maxSide.toFloat() / max(source.width, source.height)
        if (scale >= 1f) return source
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val out = PixelBuffer(width, height)
        IntStream.range(0, height).parallel().forEach { y ->
            for (x in 0 until width) out.pixels[y * width + x] = bilinear(source, (x + .5f) / scale - .5f, (y + .5f) / scale - .5f)
        }
        return out
    }

    private fun bilinear(source: PixelBuffer, x: Float, y: Float): Int {
        val sx = x.coerceIn(0f, (source.width - 1).toFloat()); val sy = y.coerceIn(0f, (source.height - 1).toFloat())
        val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
        val x1 = min(x0 + 1, source.width - 1); val y1 = min(y0 + 1, source.height - 1)
        val fx = sx - x0; val fy = sy - y0
        val a = source.pixels[y0 * source.width + x0]; val b = source.pixels[y0 * source.width + x1]
        val c = source.pixels[y1 * source.width + x0]; val d = source.pixels[y1 * source.width + x1]
        var result = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val top = ((a ushr shift) and 255) * (1 - fx) + ((b ushr shift) and 255) * fx
            val bottom = ((c ushr shift) and 255) * (1 - fx) + ((d ushr shift) and 255) * fx
            result = result or ((top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }
}
