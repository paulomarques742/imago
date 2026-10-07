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
import eu.studio742.imago.core.composition.hasVariablePressure
import eu.studio742.imago.core.composition.strokeWidthAt
import eu.studio742.imago.core.composition.backgroundBlurDivisor
import eu.studio742.imago.core.composition.FrameStyle
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
        project.elements.asSequence().filter { it.visible && (elementIds == null || it.id in elementIds) }
            .sortedWith(compareBy<CompositionElement> { it.zIndex }.thenBy { it.id }).forEach { element ->
            val renderBounds = when {
                element.repeatOnPages.isEmpty() -> element.transform.bounds
                pageIndex !in element.repeatOnPages -> return@forEach
                else -> {
                    val originalPage = floor(element.transform.bounds.x).toInt()
                    element.transform.bounds.copy(x = pageIndex + element.transform.bounds.x - originalPage)
                }
            }
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
                val bitmap = loadBitmap(background.media, null, sources, bitmaps, max(width, height) * 2)
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
        if (element is CompositionElement.MediaPlaceholder || element is CompositionElement.Video) return
        val checkpoint = canvas.save()
        val transform = element.transform
        canvas.rotate(transform.rotationDegrees, rect.centerX(), rect.centerY())
        canvas.scale(if (transform.mirrorHorizontal) -1f else 1f, if (transform.mirrorVertical) -1f else 1f, rect.centerX(), rect.centerY())
        val opacity = (transform.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        when (element) {
            is CompositionElement.Photo -> {
                drawFrameShadow(canvas, rect, element.frame, opacity)
                val radius = element.frame.cornerRadius.coerceAtLeast(0f) * min(rect.width(), rect.height())
                canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
                val bitmap = loadBitmap(element.media, element.recipe.recipe, sources, bitmaps, decodeEdge)
                drawCover(canvas, bitmap, rect, element.crop, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = opacity })
                drawFrameBorder(canvas, rect, element.frame, opacity)
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
            else -> Unit
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawShape(canvas: Canvas, element: CompositionElement.Shape, rect: RectF, opacity: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = element.fillArgb.toInt(); alpha = opacity; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.strokeArgb.toInt(); alpha = opacity; style = Paint.Style.STROKE
            // The same convention as the text size, now explicit here too: the thickness is measured
            // on a page REFERENCE_PAGE_WIDTH wide, not in pixels of this canvas.
            strokeWidth = element.strokeWidth * canvas.width / REFERENCE_PAGE_WIDTH
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        when (element.kind) {
            ShapeKind.RECTANGLE -> { canvas.drawRect(rect, fill); canvas.drawRect(rect, stroke) }
            ShapeKind.ELLIPSE -> { canvas.drawOval(rect, fill); canvas.drawOval(rect, stroke) }
            ShapeKind.LINE -> canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), stroke)
            ShapeKind.ARROW -> {
                canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), stroke)
                val head = min(rect.width(), rect.height()) * .22f
                canvas.drawLine(rect.right, rect.centerY(), rect.right - head, rect.centerY() - head, stroke)
                canvas.drawLine(rect.right, rect.centerY(), rect.right - head, rect.centerY() + head, stroke)
            }
        }
    }

    private fun drawStroke(canvas: Canvas, element: CompositionElement.Drawing, rect: RectF, opacity: Int) {
        if (element.points.size < 2 || element.kind == StrokeKind.ERASER) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.colorArgb.toInt(); alpha = if (element.kind == StrokeKind.MARKER) opacity / 2 else opacity
            style = Paint.Style.STROKE; strokeWidth = element.strokeWidth; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        // The same criterion as the stage, and for the same reason: a constant-pressure stroke is drawn
        // with a single `Path`. Both sides share `hasVariablePressure` and `strokeWidthAt` on purpose —
        // it is what keeps the exported file from coming out different from what was seen.
        if (element.points.hasVariablePressure()) {
            element.points.zipWithNext { from, to ->
                paint.strokeWidth = strokeWidthAt(element.strokeWidth, (from.pressure + to.pressure) / 2f)
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
            element.points.drop(1).forEach { lineTo(rect.left + it.x * rect.width(), rect.top + it.y * rect.height()) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawFrameShadow(canvas: Canvas, rect: RectF, frame: FrameStyle, opacity: Int) {
        if (frame.shadowRadius <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; alpha = opacity / 2; maskFilter = BlurMaskFilter(frame.shadowRadius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(rect, frame.cornerRadius, frame.cornerRadius, paint)
    }

    private fun drawFrameBorder(canvas: Canvas, rect: RectF, frame: FrameStyle, opacity: Int) {
        if (frame.borderWidth <= 0f) return
        canvas.drawRoundRect(rect, frame.cornerRadius, frame.cornerRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frame.borderArgb.toInt(); alpha = opacity; style = Paint.Style.STROKE; strokeWidth = frame.borderWidth
        })
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap, destination: RectF, crop: PlacementCrop, paint: Paint) {
        val scale = max(destination.width() / bitmap.width, destination.height() / bitmap.height) * crop.scale.coerceAtLeast(.01f)
        val visibleW = destination.width() / scale
        val visibleH = destination.height() / scale
        val maxX = max(0f, bitmap.width - visibleW)
        val maxY = max(0f, bitmap.height - visibleH)
        val centerX = bitmap.width / 2f + crop.offsetX.coerceIn(-1f, 1f) * maxX / 2f
        val centerY = bitmap.height / 2f + crop.offsetY.coerceIn(-1f, 1f) * maxY / 2f
        val source = Rect(
            (centerX - visibleW / 2f).roundToInt().coerceIn(0, bitmap.width - 1),
            (centerY - visibleH / 2f).roundToInt().coerceIn(0, bitmap.height - 1),
            (centerX + visibleW / 2f).roundToInt().coerceIn(1, bitmap.width),
            (centerY + visibleH / 2f).roundToInt().coerceIn(1, bitmap.height),
        )
        canvas.drawBitmap(bitmap, source, destination, paint)
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

