package eu.studio742.imago.feature.composer

import org.jetbrains.skia.*
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asDesktopPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.studio742.imago.core.composition.*
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.*

/**
 * One renderer for preview, still export and every video frame. Media are resolved before drawing.
 *
 * A video is drawn when [render] gets a frame for it, and only then: when each clip is on screen is
 * decided by the page's `PageVideoPlan`, the same one the phone encodes from.
 */
class CompositionRenderer {
    /** [size], when given, is the exact output — a video's, which has to be even on both sides. */
    fun render(project: CompositionProject, pageIndex: Int, media: Map<String, ByteArray>,
        maxSide: Int = Int.MAX_VALUE, size: Pair<Int, Int>? = null): ByteArray {
        require(pageIndex in project.pages.indices)
        val scale = min(1f, maxSide.toFloat() / max(project.format.pixelWidth, project.format.pixelHeight))
        val width = size?.first ?: (project.format.pixelWidth * scale).roundToInt().coerceAtLeast(1)
        val height = size?.second ?: (project.format.pixelHeight * scale).roundToInt().coerceAtLeast(1)
        return Surface.makeRasterN32Premul(width, height).use { surface ->
            val canvas = surface.canvas
            canvas.scale(width.toFloat() / project.format.pixelWidth, height.toFloat() / project.format.pixelHeight)
            val w = project.format.pixelWidth.toFloat(); val h = project.format.pixelHeight.toFloat()
            val bounds = Rect.makeWH(w, h)
            background(canvas, project.pages[pageIndex].backgroundOverride ?: project.background, bounds, media)
            project.renderElements().filter { it.visible }.sortedBy { it.zIndex }.forEach { element ->
                val repeated = pageIndex in element.repeatOnPages
                if (element.repeatOnPages.isNotEmpty() && !repeated) return@forEach
                if (element is CompositionElement.Video && element.pageIndex != pageIndex) return@forEach
                val placement = element.boundsOnPage(pageIndex)
                val rect = Rect.makeXYWH((placement.x - pageIndex.toFloat()) * w,
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
                        is CompositionElement.Photo -> photo(canvas, media[element.id] ?: media[element.media.assetId], local, element.crop, element.frame, paint, w / REFERENCE_PAGE_WIDTH)
                        is CompositionElement.Video -> media[element.id]?.let { frame ->
                            photo(canvas, frame, local, element.crop, element.frame, paint, w / REFERENCE_PAGE_WIDTH)
                        }
                        is CompositionElement.MediaPlaceholder -> {
                            if (element.frame.decoration.kind == MediaFrameKind.NONE && element.frame.cutout == MediaCutout.RECTANGLE) {
                                paint.color = 0xFF333333.toInt(); paint.alpha = (element.transform.opacity * 255).roundToInt()
                                val radius = element.frame.cornerRadius.coerceIn(0f, .5f) * min(local.width, local.height)
                                canvas.drawRRect(RRect.makeXYWH(0f, 0f, local.width, local.height, radius), paint)
                            } else drawContent(canvas, local) { drawCompositionMediaBackground(element.frame, w / REFERENCE_PAGE_WIDTH, element.transform.opacity) }
                            drawContent(canvas, local) { drawCompositionFrame(element.frame, w / REFERENCE_PAGE_WIDTH, element.transform.opacity) }
                        }
                        is CompositionElement.Shape -> drawContent(canvas, local) {
                            drawCompositionShape(element, w / REFERENCE_PAGE_WIDTH, element.transform.opacity)
                        }
                        is CompositionElement.Text -> drawText(canvas, element, local, w, paint)
                        is CompositionElement.Drawing -> {
                            paint.color = element.colorArgb.toInt(); paint.mode = PaintMode.STROKE; paint.strokeCap = PaintStrokeCap.ROUND
                            paint.alpha = (element.transform.opacity.coerceIn(0f, 1f) * (element.colorArgb ushr 24 and 255)).roundToInt()
                            if (element.kind == StrokeKind.ERASER) paint.blendMode = BlendMode.CLEAR
                            if (element.kind == StrokeKind.MARKER) paint.alpha = (paint.alpha * .45f).toInt()
                            if (!element.points.hasVariablePressure() && element.points.isNotEmpty()) {
                                paint.strokeWidth = element.strokeWidth * w / REFERENCE_PAGE_WIDTH
                                paint.strokeJoin = PaintStrokeJoin.ROUND
                                Path().use { path ->
                                    path.moveTo(element.points.first().x * local.width, element.points.first().y * local.height)
                                    element.points.drop(1).forEach { point ->
                                        if (point.startsStroke) path.moveTo(point.x * local.width, point.y * local.height)
                                        else path.lineTo(point.x * local.width, point.y * local.height)
                                    }
                                    canvas.drawPath(path, paint)
                                }
                            } else element.points.zipWithNext().forEach { (a, b) ->
                                if (!b.startsStroke) {
                                    paint.strokeWidth = strokeWidthAt(element.strokeWidth * w / REFERENCE_PAGE_WIDTH, (a.pressure + b.pressure) / 2)
                                    canvas.drawLine(a.x * local.width, a.y * local.height, b.x * local.width, b.y * local.height, paint)
                                }
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
    private fun photo(canvas: Canvas, bytes: ByteArray?, rect: Rect, crop: PlacementCrop, frame: FrameStyle, paint: Paint, unit: Float = 1f) {
        if (bytes == null) {
            // Only a preview gets here without the media: exports are strict and fail earlier. The
            // place stays marked, without text — the stage is what says what is missing.
            paint.color = 0xFF3B3633.toInt(); canvas.drawRect(rect, paint)
            return
        }
        val alpha = paint.alpha
        val checkpoint = if (alpha < 255) Paint().use { layer -> layer.alpha = alpha; canvas.saveLayer(null, layer) } else canvas.save()
        paint.alpha = 255
        drawContent(canvas, rect) { drawCompositionMediaBackground(frame, unit) }
        Image.makeFromEncoded(bytes).use { image ->
            canvas.save()
            canvas.clipPath(mediaCutoutPath(frame, Size(rect.width, rect.height)).asDesktopPath())
            val placement = placementImageRect(image.width.toFloat(), image.height.toFloat(), rect.width, rect.height, crop)
            canvas.drawImageRect(image, Rect.makeXYWH(placement.left, placement.top, placement.width, placement.height), paint)
            canvas.restore()
        }
        // A ring around the photo, outside it: a stroke centred half a width out from the edge.
        drawContent(canvas, rect) { drawCompositionFrame(frame, unit) }
        canvas.restoreToCount(checkpoint)
        paint.alpha = alpha
    }
    private fun drawContent(canvas: Canvas, rect: Rect, block: DrawScope.() -> Unit) =
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas.asComposeCanvas(), Size(rect.width, rect.height), block)
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
