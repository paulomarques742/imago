package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import kotlin.math.roundToInt

/**
 * The photo with the recipe applied, at preview size.
 *
 * It is the same `BitmapPhotoProcessor` as the export — the library thumbnail shows what the export
 * produces, not an approximation. `source` is never recycled: the caller may be Coil, and the input
 * bitmap belongs to it.
 */
fun renderRecipePreview(source: Bitmap, recipe: EditRecipe): Bitmap {
    val parameters = recipe.toRenderParameters()
    // Before the crop, which is when the original image's dimensions still exist.
    val geometry = parameters.frameGeometry(source.width, source.height)
    val cropped = applyRecipeGeometry(source, recipe.activeGeometry())
    // The processor writes pixels directly: it needs a mutable ARGB_8888 bitmap. A crop may return an
    // immutable one, and Coil may hand over a hardware bitmap — in either case the copy is what serves
    // as the canvas.
    val working = when {
        cropped !== source && cropped.isMutable && cropped.config == Bitmap.Config.ARGB_8888 -> cropped
        else -> cropped.copy(Bitmap.Config.ARGB_8888, true)
            .also { if (cropped !== source) cropped.recycle() }
    }
    return BitmapPhotoProcessor.renderInPlace(working, parameters, geometry)
}

/**
 * Straighten and perspective on a bitmap already turned and flipped: [frameTransformInPixels], the
 * matrix the shader samples with, in this bitmap's pixels. Null when there is
 * nothing to do. What the photo does not cover stays transparent here; `BitmapPhotoProcessor` paints
 * it white after processing, when the crop is unconstrained.
 */
fun transformedFrame(working: Bitmap, straighten: Float, perspective: PerspectiveParameters): Bitmap? {
    if (straighten == 0f && perspective.isNeutral) return null
    if (perspective.distortion != 0f) return distortedFrame(working, straighten, perspective)
    // Android's `Matrix` is row-major with the perspective in the last row: the same nine values.
    val matrix = Matrix().apply {
        setValues(frameTransformInPixels(working.width, working.height, straighten, perspective).values())
    }
    val transformed = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
    Canvas(transformed).drawBitmap(working, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return transformed
}

/**
 * [transformedFrame] with a lens distortion, which no `Matrix` holds: every pixel sampled through
 * [FrameGeometry], as the shader and the desktop do. The result is written in bands of rows, so that a
 * full-resolution export holds one copy of the photo's pixels and not two.
 */
private fun distortedFrame(working: Bitmap, straighten: Float, perspective: PerspectiveParameters): Bitmap {
    val width = working.width
    val height = working.height
    val readable = if (working.config == Bitmap.Config.HARDWARE) working.copy(Bitmap.Config.ARGB_8888, false) else working
    val source = PixelBuffer(width, height).also { readable.getPixels(it.pixels, 0, width, 0, 0, width, height) }
    if (readable !== working) readable.recycle()
    val frame = FrameGeometry(width, height, straighten = straighten, perspective = perspective)
    val transformed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val bandRows = (DISTORTION_BAND_PIXELS / width).coerceIn(1, height)
    val band = IntArray(width * bandRows)
    var row = 0
    while (row < height) {
        val until = minOf(row + bandRows, height)
        RecipePixels.sampleFrameRows(source, frame, row, until, band)
        transformed.setPixels(band, 0, width, 0, row, width, until - row)
        row = until
    }
    return transformed
}

private const val DISTORTION_BAND_PIXELS = 1 shl 20

/**
 * Rotation, flip, straighten and crop, in that order — the same as the export and the same as
 * `PhotoShaders.COMPOSITE`'s `geometryCoordinate`, which is what defines it.
 *
 * Rotation comes before the flip because the shader composes them in that order, and the two do not
 * commute: flipping an axis and rotating a quarter turn gives the opposite axis from rotating first
 * and flipping after. While the export used the opposite order, a recipe combining a flip with 90°
 * or 270° came out of the file flipped on the wrong axis compared to what the stage showed.
 *
 * The intermediates are recycled as they stop being needed; `source` is not, because it does not
 * belong to this function.
 */
private fun applyRecipeGeometry(source: Bitmap, geometry: Geometry): Bitmap {
    var working = source

    fun replaceWith(next: Bitmap) {
        if (next === working) return
        if (working !== source) working.recycle()
        working = next
    }

    val rotation = ((geometry.rotation % 360) + 360) % 360
    if (rotation != 0) {
        replaceWith(
            Bitmap.createBitmap(
                working,
                0,
                0,
                working.width,
                working.height,
                Matrix().apply { setRotate(rotation.toFloat()) },
                true,
            ),
        )
    }
    if (geometry.mirrorH || geometry.mirrorV) {
        replaceWith(
            Bitmap.createBitmap(
                working,
                0,
                0,
                working.width,
                working.height,
                Matrix().apply {
                    setScale(if (geometry.mirrorH) -1f else 1f, if (geometry.mirrorV) -1f else 1f)
                },
                true,
            ),
        )
    }
    transformedFrame(working, geometry.straighten.coerceIn(-45f, 45f), geometry.perspective.toRenderPerspective())
        ?.let(::replaceWith)
    val crop = geometry.cropRect
    val left = (normalizedCropOrigin(crop.x) * working.width).roundToInt().coerceAtMost(working.width - 1)
    val top = (normalizedCropOrigin(crop.y) * working.height).roundToInt().coerceAtMost(working.height - 1)
    val width = (normalizedCropSize(crop.x, crop.w) * working.width).roundToInt().coerceIn(1, working.width - left)
    val height = (normalizedCropSize(crop.y, crop.h) * working.height).roundToInt().coerceIn(1, working.height - top)
    if (left != 0 || top != 0 || width != working.width || height != working.height) {
        replaceWith(Bitmap.createBitmap(working, left, top, width, height))
    }
    return working
}

/**
 * The recipe applied to everything Coil loads.
 *
 * The `cacheKey` includes the instant of the last save: saving the recipe again has to change the
 * thumbnail, and nothing else does.
 */
class EditRecipeTransformation(private val recipe: EditRecipe) : Transformation() {
    override val cacheKey: String = "recipe:${recipe.assetId}:${recipe.processVersion}:${recipe.updatedAt}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        withContext(Dispatchers.Default) { renderRecipePreview(input, recipe) }
}

actual fun recipeTransformation(recipe: EditRecipe): Transformation = EditRecipeTransformation(recipe)
