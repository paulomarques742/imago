package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.model.DEVICE_ALBUM_NAME
import eu.studio742.imago.core.designsystem.i18n.LocalizedException
import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.composer.resources.*
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.boundsOnPage
import eu.studio742.imago.core.composition.hasVariablePressure
import eu.studio742.imago.core.composition.strokeWidthAt
import eu.studio742.imago.core.composition.backgroundBlurDivisor
import eu.studio742.imago.core.composition.FrameStyle
import eu.studio742.imago.core.composition.renderElements
import eu.studio742.imago.core.composition.placementImageRect
import eu.studio742.imago.core.composition.videoDecorationAlphaFactor
import androidx.compose.ui.graphics.asAndroidPath
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.PlacementCrop
import eu.studio742.imago.core.composition.ShapeKind
import eu.studio742.imago.core.composition.StrokeKind
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.renderRecipePreview

data class CompositionExportResult(
    val uris: List<Uri>,
    val fileNames: List<String>,
    val mimeTypes: List<String> = emptyList(),
    val immichUploadFailures: Int = 0,
)

/**
 * A deliberately paged renderer: only one final 1080 px wide bitmap exists in memory, even in a
 * twenty-page project. The same global transformation is projected onto each page, so a continuous
 * element is cut exactly at the separator, without a gutter.
 */
class CompositionStaticExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val media: CompositionMediaRepository,
) {
    suspend fun renderPageToCache(
        project: CompositionProject,
        pageIndex: Int,
        format: StaticExportFormat = StaticExportFormat.PNG,
        includeBackground: Boolean = true,
        elementIds: Set<String>? = null,
    ): File = withContext(Dispatchers.IO) {
        val sources = mutableMapOf<String, File>()
        val bitmaps = mutableMapOf<String, Bitmap>()
        val output = File.createTempFile("composition-page-${pageIndex + 1}-", ".${format.extension}", context.cacheDir)
        val bitmap = Bitmap.createBitmap(project.format.pixelWidth, project.format.pixelHeight, Bitmap.Config.ARGB_8888)
        try {
            renderPage(bitmap, project, pageIndex, sources, bitmaps, includeBackground, elementIds)
            FileOutputStream(output).buffered().use { stream ->
                val compression = if (format == StaticExportFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                check(bitmap.compress(compression, if (format == StaticExportFormat.JPEG) 95 else 100, stream))
            }
            output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            bitmap.recycle()
            bitmaps.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
            sources.values.forEach(File::delete)
        }
    }

    suspend fun renderVideoDecorationToCache(project: CompositionProject, pageIndex: Int, video: CompositionElement.Video,
        width: Int, height: Int,
    ): File = withContext(Dispatchers.IO) {
        val output = File.createTempFile("composition-video-frame-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val b = video.transform.bounds
        val w = b.width * width; val h = b.height * height
        val cx = (b.x - pageIndex + b.width / 2f) * width; val cy = (b.y + b.height / 2f) * height
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        fun transform(canvas: Canvas, row: Int = 0): Int {
            val checkpoint = canvas.save()
            canvas.translate(0f, -row.toFloat())
            canvas.rotate(video.transform.rotationDegrees, cx, cy)
            canvas.scale(if (video.transform.mirrorHorizontal) -1f else 1f, if (video.transform.mirrorVertical) -1f else 1f, cx, cy)
            return checkpoint
        }
        try {
            val canvas = Canvas(bitmap)
            val checkpoint = transform(canvas)
            drawContent(canvas, rect) {
                drawCompositionMediaBackground(video.frame, width / REFERENCE_PAGE_WIDTH)
                drawCompositionFrame(video.frame, width / REFERENCE_PAGE_WIDTH)
            }
            canvas.restoreToCount(checkpoint)
            if (video.transform.opacity < 1f) {
                // A narrow mask strip bounds working memory even for a full-size page.
                val rows = min(64, height)
                val mask = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
                try {
                    val maskCanvas = Canvas(mask)
                    val coverage = IntArray(width * rows); val pixels = IntArray(width * rows)
                    val path = mediaCutoutPath(video.frame, androidx.compose.ui.geometry.Size(w, h)).asAndroidPath().apply { offset(rect.left, rect.top) }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
                    for (top in 0 until height step rows) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = min(rows, height - top)
                        mask.eraseColor(Color.TRANSPARENT)
                        val mark = transform(maskCanvas, top)
                        maskCanvas.drawPath(path, paint); maskCanvas.restoreToCount(mark)
                        mask.getPixels(coverage, 0, width, 0, 0, width, count)
                        bitmap.getPixels(pixels, 0, width, 0, top, width, count)
                        for (index in 0 until width * count) {
                            val alpha = pixels[index] ushr 24
                            if (alpha == 0) continue
                            val factor = videoDecorationAlphaFactor((coverage[index] ushr 24) / 255f, video.transform.opacity)
                            pixels[index] = (pixels[index] and 0xFFFFFF) or ((alpha * factor).roundToInt().coerceIn(0, 255) shl 24)
                        }
                        bitmap.setPixels(pixels, 0, width, 0, top, width, count)
                    }
                } finally { mask.recycle() }
            }
            output.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            output
        } catch (error: Exception) { output.delete(); throw error }
        finally { bitmap.recycle() }
    }

    suspend fun export(
        project: CompositionProject,
        format: StaticExportFormat,
        pageIndex: Int? = null,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): CompositionExportResult = withContext(Dispatchers.IO) {
        val targets = pageIndex?.let { listOf(it.coerceIn(project.pages.indices)) } ?: project.pages.indices.toList()
        val unsupported = project.elements.filterIsInstance<CompositionElement.Video>()
            .firstOrNull { it.visible && it.pageIndex in targets }
        if (unsupported != null) throw LocalizedException(uiText(Res.string.composer_video_needs_mp4))

        val workDir = File(context.cacheDir, "composition-export").apply { mkdirs() }
        val sourceFiles = mutableMapOf<String, File>()
        val bitmaps = mutableMapOf<String, Bitmap>()
        val outputs = mutableListOf<File>()
        try {
            targets.forEachIndexed { position, page ->
                ensureActive()
                val bitmap = Bitmap.createBitmap(project.format.pixelWidth, project.format.pixelHeight, Bitmap.Config.ARGB_8888)
                try {
                    renderPage(bitmap, project, page, sourceFiles, bitmaps)
                    val output = File.createTempFile("composition-${page + 1}-", ".${format.extension}", workDir)
                    FileOutputStream(output).buffered().use { stream ->
                        val compression = if (format == StaticExportFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                        check(bitmap.compress(compression, if (format == StaticExportFormat.JPEG) 95 else 100, stream))
                    }
                    outputs += output
                    onProgress(position + 1, targets.size)
                } finally {
                    bitmap.recycle()
                }
            }
            publishAtomically(project.name, appString(Res.string.composer_composition), targets, outputs, format)
        } finally {
            bitmaps.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
            sourceFiles.values.forEach(File::delete)
            outputs.forEach(File::delete)
        }
    }

    private suspend fun renderPage(
        target: Bitmap,
        project: CompositionProject,
        pageIndex: Int,
        sources: MutableMap<String, File>,
        bitmaps: MutableMap<String, Bitmap>,
        includeBackground: Boolean = true,
        elementIds: Set<String>? = null,
    ) {
        val canvas = Canvas(target)
        if (includeBackground) {
            renderBackground(
                canvas,
                project.pages[pageIndex].backgroundOverride ?: project.background,
                target.width,
                target.height,
                sources,
                bitmaps,
            )
        } else canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        project.renderElements().asSequence().filter { it.visible && (elementIds == null || it.id in elementIds) }
            .sortedBy { it.zIndex }.forEach { element ->
            if (element is CompositionElement.Video && element.pageIndex != pageIndex) return@forEach
            if (element.repeatOnPages.isNotEmpty() && pageIndex !in element.repeatOnPages) return@forEach
            val renderBounds = element.boundsOnPage(pageIndex)
            val rect = RectF(
                (renderBounds.x - pageIndex) * target.width,
                renderBounds.y * target.height,
                (renderBounds.right - pageIndex) * target.width,
                renderBounds.bottom * target.height,
            )
            renderElement(canvas, element, rect, sources, bitmaps, max(target.width, target.height))
        }
    }

    private suspend fun renderBackground(
        canvas: Canvas,
        background: CompositionBackground,
        width: Int,
        height: Int,
        sources: MutableMap<String, File>,
        bitmaps: MutableMap<String, Bitmap>,
    ) {
        when (background) {
            is CompositionBackground.Solid -> canvas.drawColor(background.argb.toInt())
            is CompositionBackground.Gradient -> {
                val radians = Math.toRadians(background.angleDegrees.toDouble())
                val dx = cos(radians).toFloat() * width / 2f
                val dy = sin(radians).toFloat() * height / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(width / 2f - dx, height / 2f - dy, width / 2f + dx, height / 2f + dy,
                        background.startArgb.toInt(), background.endArgb.toInt(), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            is CompositionBackground.Photo -> {
                val bitmap = loadBitmap(background.media, background.recipe?.recipe, sources, bitmaps, max(width, height) * 2)
                val destination = RectF(0f, 0f, width.toFloat(), height.toFloat())
                if (background.blurRadius > 0f) {
                    // The divisor comes from the radius, and is the same the preview uses. It was fixed
                    // at 24, so the blur always came out the same however much the user changed it —
                    // the slider would have no effect at all on the file.
                    val divisor = backgroundBlurDivisor(background.blurRadius)
                    val small = Bitmap.createScaledBitmap(bitmap, max(1, width / divisor), max(1, height / divisor), true)
                    drawCover(canvas, small, destination, background.crop, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    small.recycle()
                } else {
                    drawCover(canvas, bitmap, destination, background.crop, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
        }
    }

    private suspend fun renderElement(
        canvas: Canvas,
        element: CompositionElement,
        rect: RectF,
        sources: MutableMap<String, File>,
        bitmaps: MutableMap<String, Bitmap>,
        decodeEdge: Int,
    ) {
        if (element is CompositionElement.Video) return
        if (element is CompositionElement.MediaPlaceholder && element.frame.decoration.kind == eu.studio742.imago.core.composition.MediaFrameKind.NONE &&
            element.frame.cutout == eu.studio742.imago.core.composition.MediaCutout.RECTANGLE) return
        val checkpoint = canvas.save()
        val transform = element.transform
        canvas.rotate(transform.rotationDegrees, rect.centerX(), rect.centerY())
        canvas.scale(if (transform.mirrorHorizontal) -1f else 1f, if (transform.mirrorVertical) -1f else 1f, rect.centerX(), rect.centerY())
        val opacity = (transform.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        when (element) {
            is CompositionElement.Photo -> {
                // Outline and shadow are measured on a REFERENCE_PAGE_WIDTH page, like the stage.
                val unit = canvas.width / REFERENCE_PAGE_WIDTH
                val alphaLayer = if (opacity < 255) canvas.saveLayerAlpha(null, opacity) else canvas.save()
                drawContent(canvas, rect) { drawCompositionMediaBackground(element.frame, unit) }
                // The clip is only for the photo: the outline goes around it, outside, and a clip left on
                // would cut it.
                val photoClip = canvas.save()
                canvas.clipPath(mediaCutoutPath(element.frame, androidx.compose.ui.geometry.Size(rect.width(), rect.height())).asAndroidPath().apply { offset(rect.left, rect.top) })
                val bitmap = loadBitmap(element.media, element.recipe.recipe, sources, bitmaps, decodeEdge)
                drawCover(canvas, bitmap, rect, element.crop, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                canvas.restoreToCount(photoClip)
                drawFrameBorder(canvas, rect, element.frame, unit, 255)
                canvas.restoreToCount(alphaLayer)
            }
            is CompositionElement.Text -> {
                // The same painter as the stage, with the same scale: a model pixel is worth a fraction
                // of a REFERENCE_PAGE_WIDTH page, and not a pixel of this file.
                canvas.translate(rect.left, rect.top)
                AndroidTextPainter.draw(
                    canvas, element, rect.width(), rect.height(), canvas.width / REFERENCE_PAGE_WIDTH,
                    AndroidCompositionFonts.typeface(element.fontFamily, element.fontWeight), opacity,
                )
            }
            is CompositionElement.Shape -> drawShape(canvas, element, rect, opacity)
            is CompositionElement.Drawing -> drawStroke(canvas, element, rect, opacity)
            is CompositionElement.MediaPlaceholder -> drawContent(canvas, rect) {
                val unit = canvas.width / REFERENCE_PAGE_WIDTH
                drawCompositionMediaBackground(element.frame, unit, opacity / 255f)
                drawCompositionFrame(element.frame, unit, opacity / 255f)
            }
            else -> Unit
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawShape(canvas: Canvas, element: CompositionElement.Shape, rect: RectF, opacity: Int) {
        val unit = canvas.width / REFERENCE_PAGE_WIDTH
        drawContent(canvas, rect) { drawCompositionShape(element, unit, opacity / 255f) }
    }

    private fun drawStroke(canvas: Canvas, element: CompositionElement.Drawing, rect: RectF, opacity: Int) {
        if (element.points.size < 2 || element.kind == StrokeKind.ERASER) return
        if (element.id.startsWith("layout-decoration:")) {
            val unit = canvas.width / REFERENCE_PAGE_WIDTH
            drawContent(canvas, rect) { drawCompositionLayoutStroke(element, unit, element.transform.opacity) }
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.colorArgb.toInt()
            alpha = (opacity * (element.colorArgb ushr 24 and 255) / 255f * if (element.kind == StrokeKind.MARKER) .45f else 1f).roundToInt()
            style = Paint.Style.STROKE; strokeWidth = element.strokeWidth * canvas.width / REFERENCE_PAGE_WIDTH; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        // The same criterion as the stage, and for the same reason: a constant-pressure stroke is drawn
        // with a single `Path`. Both sides share `hasVariablePressure` and `strokeWidthAt` on purpose —
        // it is what keeps the exported file from coming out different from what was seen.
        if (element.points.hasVariablePressure()) {
            element.points.zipWithNext { from, to ->
                if (to.startsStroke) return@zipWithNext
                paint.strokeWidth = strokeWidthAt(element.strokeWidth * canvas.width / REFERENCE_PAGE_WIDTH, (from.pressure + to.pressure) / 2f)
                canvas.drawLine(
                    rect.left + from.x * rect.width(), rect.top + from.y * rect.height(),
                    rect.left + to.x * rect.width(), rect.top + to.y * rect.height(),
                    paint,
                )
            }
            return
        }
        val path = Path().apply {
            moveTo(rect.left + element.points.first().x * rect.width(), rect.top + element.points.first().y * rect.height())
            element.points.drop(1).forEach { if (it.startsStroke) moveTo(rect.left + it.x * rect.width(), rect.top + it.y * rect.height()) else lineTo(rect.left + it.x * rect.width(), rect.top + it.y * rect.height()) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawFrameShadow(canvas: Canvas, rect: RectF, frame: FrameStyle, radius: Float, unit: Float, opacity: Int) {
        if (frame.shadowRadius <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; alpha = opacity / 2; maskFilter = BlurMaskFilter(frame.shadowRadius * unit, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    /** The outline is a ring around the element: from its edge outwards, never over the photo. */
    private fun drawFrameBorder(canvas: Canvas, rect: RectF, frame: FrameStyle, unit: Float, opacity: Int) {
        drawContent(canvas, rect) { drawCompositionFrame(frame, unit, opacity / 255f) }
    }

    private fun drawContent(canvas: Canvas, rect: RectF, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        val checkpoint = canvas.save()
        canvas.translate(rect.left, rect.top)
        try {
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(androidx.compose.ui.unit.Density(1f),
                androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas),
                androidx.compose.ui.geometry.Size(rect.width(), rect.height()), block)
        } finally { canvas.restoreToCount(checkpoint) }
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, destination: RectF, crop: PlacementCrop, paint: Paint) {
        val image = placementImageRect(bitmap.width.toFloat(), bitmap.height.toFloat(), destination.width(), destination.height(), crop)
        canvas.drawBitmap(bitmap, null, RectF(destination.left + image.left, destination.top + image.top,
            destination.left + image.left + image.width, destination.top + image.top + image.height), paint)
    }

    private suspend fun loadBitmap(
        reference: MediaReference,
        recipe: EditRecipe?,
        sources: MutableMap<String, File>,
        bitmaps: MutableMap<String, Bitmap>,
        decodeEdge: Int,
    ): Bitmap {
        val key = "${reference.assetId}:${recipe?.updatedAt ?: "original"}"
        bitmaps[key]?.let { return it }
        val source = sources.getOrPut(reference.assetId) {
            File.createTempFile("composition-source-", ".asset", context.cacheDir)
        }
        if (source.length() == 0L) media.downloadOriginal(reference.assetId, source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > decodeEdge) sample *= 2
        val decoded = (BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })) ?: throw LocalizedException(uiText(Res.string.composer_decode_failed, reference.fileName))
        val oriented = applyExifOrientation(decoded, source)
        val mutable = if (oriented.isMutable) oriented else oriented.copy(Bitmap.Config.ARGB_8888, true).also { oriented.recycle() }
        // The same `renderRecipePreview` the library and the stage use: the exported composition has to
        // bring the photo with the look the element showed on screen.
        val rendered = if (recipe == null) {
            mutable
        } else {
            renderRecipePreview(mutable, recipe).also { if (it !== mutable) mutable.recycle() }
        }
        bitmaps[key] = rendered
        return rendered
    }

    private fun applyExifOrientation(bitmap: Bitmap, source: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return bitmap
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun publishAtomically(
        projectName: String,
        untitled: String,
        pageIndexes: List<Int>,
        files: List<File>,
        format: StaticExportFormat,
    ): CompositionExportResult {
        val resolver = context.contentResolver
        val safeName = projectName.lowercase().replace(Regex("[^a-z0-9à-ÿ]+"), "-").trim('-').ifBlank { untitled }
        val uris = mutableListOf<Uri>()
        val names = pageIndexes.map { "${safeName}_${(it + 1).toString().padStart(2, '0')}.${format.extension}" }
        try {
            files.zip(names).forEach { (file, name) ->
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$DEVICE_ALBUM_NAME")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                uris += uri
                checkNotNull(resolver.openOutputStream(uri, "w")).use { output -> file.inputStream().buffered().use { it.copyTo(output) } }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) uris.forEach { uri ->
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            return CompositionExportResult(uris, names, List(uris.size) { format.mimeType })
        } catch (error: Exception) {
            uris.forEach { resolver.delete(it, null, null) }
            throw error
        }
    }
}

