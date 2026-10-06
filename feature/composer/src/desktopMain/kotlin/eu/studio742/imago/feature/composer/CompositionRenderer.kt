package eu.studio742.imago.feature.composer

import org.jetbrains.skia.*
import eu.studio742.imago.core.composition.*
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.*

/** One renderer for preview, still export and every video frame. Media are resolved before drawing. */
class CompositionRenderer {
    fun render(project: CompositionProject, pageIndex: Int, media: Map<String, ByteArray>,
        maxSide: Int = Int.MAX_VALUE, timeMs: Long = 0): ByteArray {
        require(pageIndex in project.pages.indices)
        val scale = min(1f, maxSide.toFloat() / max(project.format.pixelWidth, project.format.pixelHeight))
        val width = (project.format.pixelWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (project.format.pixelHeight * scale).roundToInt().coerceAtLeast(1)
        return Surface.makeRasterN32Premul(width, height).use { surface ->
            val canvas = surface.canvas
            canvas.scale(scale, scale)
            val w = project.format.pixelWidth.toFloat(); val h = project.format.pixelHeight.toFloat()
            val bounds = Rect.makeWH(w, h)
            background(canvas, project.pages[pageIndex].backgroundOverride ?: project.background, bounds, media)
            project.elements.filter { it.visible }.sortedBy { it.zIndex }.forEach { element ->
                val placement = element.transform.bounds
                val repeated = pageIndex in element.repeatOnPages
                if (element is CompositionElement.Video && element.pageIndex != pageIndex) return@forEach
                if (!repeated && placement.intersectionWithPage(pageIndex) == null) return@forEach
                val rect = Rect.makeXYWH((placement.x - if (repeated) floor(placement.x) else pageIndex.toFloat()) * w,
                    placement.y * h, placement.width * w, placement.height * h)
                canvas.save()
                canvas.clipRect(bounds)
                canvas.translate(rect.left + rect.width / 2, rect.top + rect.height / 2)
                canvas.rotate(element.transform.rotationDegrees)
                canvas.scale(if (element.transform.mirrorHorizontal) -1f else 1f, if (element.transform.mirrorVertical) -1f else 1f)
                canvas.translate(-rect.width / 2, -rect.height / 2)
                val local = Rect.makeWH(rect.width, rect.height)
                Paint().use { paint ->
                    paint.isAntiAlias = true
                    paint.alpha = (element.transform.opacity.coerceIn(0f, 1f) * 255).roundToInt()
                    when (element) {
                        is CompositionElement.Photo -> photo(canvas, media[element.id] ?: media[element.media.assetId], local, element.crop, element.frame, paint)
                        is CompositionElement.Video -> {
                            val elapsed = timeMs - element.timing.startOffsetMs
                            val duration = element.timing.trimEndMs - element.timing.trimStartMs
                            if (elapsed >= 0 && (element.timing.loop || elapsed < duration))
                                photo(canvas, media[element.id] ?: media[element.media.assetId], local, element.crop, element.frame, paint)
                        }
                        is CompositionElement.MediaPlaceholder -> { paint.color = 0xFF333333.toInt(); canvas.drawRect(local, paint) }
                        is CompositionElement.Shape -> {
                            paint.color = element.fillArgb.toInt(); paint.alpha = (element.transform.opacity * ((element.fillArgb ushr 24) and 255)).toInt()
                            drawShape(canvas, element.kind, local, paint)
                            if (element.strokeWidth > 0) {
                                paint.color = element.strokeArgb.toInt(); paint.mode = PaintMode.STROKE; paint.strokeWidth = element.strokeWidth
                                drawShape(canvas, element.kind, local, paint)
                            }
                        }
                        is CompositionElement.Text -> drawText(canvas, element, local, w, paint)
                        is CompositionElement.Drawing -> {
                            paint.color = element.colorArgb.toInt(); paint.mode = PaintMode.STROKE; paint.strokeCap = PaintStrokeCap.ROUND
                            if (element.kind == StrokeKind.ERASER) paint.blendMode = BlendMode.CLEAR
                            if (element.kind == StrokeKind.MARKER) paint.alpha = (paint.alpha * .45f).toInt()
                            element.points.zipWithNext().forEach { (a, b) ->
                                paint.strokeWidth = strokeWidthAt(element.strokeWidth, (a.pressure + b.pressure) / 2)
                                canvas.drawLine(a.x * local.width, a.y * local.height, b.x * local.width, b.y * local.height, paint)
                            }
                        }
                    }
                }
                canvas.restore()
            }
            surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
        }
    }
    private fun background(canvas: Canvas, background: CompositionBackground, rect: Rect, media: Map<String, ByteArray>) {
        Paint().use { paint -> when (background) {
            is CompositionBackground.Solid -> { paint.color = background.argb.toInt(); canvas.drawRect(rect, paint) }
            is CompositionBackground.Gradient -> {
                val angle = Math.toRadians(background.angleDegrees.toDouble())
                val dx = cos(angle).toFloat() * rect.width / 2; val dy = sin(angle).toFloat() * rect.height / 2
                Shader.makeLinearGradient(rect.width / 2 - dx, rect.height / 2 - dy, rect.width / 2 + dx, rect.height / 2 + dy,
                    intArrayOf(background.startArgb.toInt(), background.endArgb.toInt())).use { shader ->
                    paint.shader = shader; canvas.drawRect(rect, paint)
                }
            }
            is CompositionBackground.Photo -> {
                if (background.blurRadius > 0) paint.imageFilter = ImageFilter.makeBlur(background.blurRadius, background.blurRadius, FilterTileMode.CLAMP)
                photo(canvas, media[background.media.assetId], rect, background.crop, FrameStyle(), paint)
                paint.imageFilter?.close()
            }
        } }
    }
    private fun photo(canvas: Canvas, bytes: ByteArray?, rect: Rect, crop: PlacementCrop, frame: FrameStyle, paint: Paint) {
        if (bytes == null) {
            // Only a preview gets here without the media: exports are strict and fail earlier. The
            // place stays marked, without text — the stage is what says what is missing.
            paint.color = 0xFF3B3633.toInt(); canvas.drawRect(rect, paint)
            return
        }
        Image.makeFromEncoded(bytes).use { image ->
            canvas.save()
            canvas.clipRRect(RRect.makeXYWH(rect.left, rect.top, rect.width, rect.height, frame.cornerRadius))
            val cover = max(rect.width / image.width, rect.height / image.height) * crop.scale.coerceAtLeast(1f)
            val width = image.width * cover; val height = image.height * cover
            val left = (rect.width - width) / 2 + crop.offsetX * rect.width
            val top = (rect.height - height) / 2 + crop.offsetY * rect.height
            canvas.drawImageRect(image, Rect.makeXYWH(left, top, width, height), paint)
            canvas.restore()
        }
        if (frame.borderWidth > 0) Paint().use { border ->
            border.color = frame.borderArgb.toInt(); border.mode = PaintMode.STROKE; border.strokeWidth = frame.borderWidth
            canvas.drawRRect(RRect.makeXYWH(rect.left, rect.top, rect.width, rect.height, frame.cornerRadius), border)
        }
    }
    private fun drawShape(canvas: Canvas, kind: ShapeKind, rect: Rect, paint: Paint) {
        when (kind) {
            ShapeKind.RECTANGLE -> canvas.drawRect(rect, paint)
            ShapeKind.ELLIPSE -> canvas.drawOval(rect, paint)
            ShapeKind.LINE -> canvas.drawLine(0f, rect.height / 2, rect.width, rect.height / 2, paint)
            ShapeKind.ARROW -> {
                canvas.drawLine(0f, rect.height / 2, rect.width, rect.height / 2, paint)
                canvas.drawLine(rect.width * .75f, 0f, rect.width, rect.height / 2, paint)
                canvas.drawLine(rect.width * .75f, rect.height, rect.width, rect.height / 2, paint)
            }
        }
    }
    private fun drawText(canvas: Canvas, text: CompositionElement.Text, rect: Rect, pageWidth: Float, paint: Paint) {
        // The catalogue font, loaded before drawing and shared with the stage. If for some reason it is
        // not loaded, the painter uses the system's: better that than a missing text.
        // The scale is the stage's and Android's: a model pixel is worth a fraction of a
        // REFERENCE_PAGE_WIDTH page. The size used to be used raw, and on a page wider than 1080 the
        // text came out smaller than what was seen.
        SkiaTextPainter.draw(
            canvas, text, rect.width, rect.height, pageWidth / REFERENCE_PAGE_WIDTH,
            SkiaCompositionFonts.loaded(text.fontFamily, text.fontWeight), paint.alpha,
        )
    }
}
