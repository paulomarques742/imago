package eu.studio742.imago.feature.composer

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.CompositionTextLayout
import java.util.concurrent.ConcurrentHashMap

/**
 * Drawing a text in Skia, the same for the desktop stage and for the export renderer.
 *
 * Each line is a single-line `Paragraph`, and not a `drawString`. `drawString` applies no kerning and
 * has no letter spacing, and the letters would come out in other positions than on Android.
 * `Paragraph` models the text like Android, adds the spacing to every letter and fetches from the
 * system the characters the font does not have, like emoji. The layout is [CompositionTextLayout]'s;
 * `Paragraph` never breaks lines.
 */
internal object SkiaTextPainter {
    /** A collection per typeface, with it registered under a name of its own and the system as fallback. */
    private val collections = ConcurrentHashMap<Typeface, FontCollection>()
    private const val Family = "composition"

    /** The system font, only for when the catalogue's is not loaded yet. A single one, because it is a key of [collections]. */
    private val systemFace: Typeface? by lazy { FontMgr.default.matchFamilyStyle(null, org.jetbrains.skia.FontStyle.NORMAL) }

    private fun collection(face: Typeface): FontCollection = collections.getOrPut(face) {
        FontCollection().apply {
            setAssetFontManager(TypefaceFontProvider().registerTypeface(face, Family))
            setDefaultFontManager(FontMgr.default)
        }
    }

    private fun paragraph(text: String, face: Typeface, fontSize: Float, letterSpacing: Float, paint: Paint): Paragraph {
        val style = TextStyle()
            .setFontFamilies(arrayOf(Family))
            .setFontSize(fontSize)
            .setLetterSpacing(letterSpacing)
            .setForeground(paint)
        val paragraphStyle = ParagraphStyle().apply { textStyle = style }
        return ParagraphBuilder(paragraphStyle, collection(face)).use { builder ->
            builder.pushStyle(style).addText(text).popStyle().build()
        }.layout(Float.POSITIVE_INFINITY)
    }

    /** Draws [element] in a box of [width] by [height] with the corner at (0, 0), like [AndroidTextPainter]. */
    fun draw(canvas: Canvas, element: CompositionElement.Text, width: Float, height: Float, scale: Float, face: Typeface?, alpha: Int = 255) {
        if (alpha <= 0) return
        val typeface = face ?: systemFace ?: return
        val checkpoint = if (alpha < 255) {
            Paint().use { layer -> layer.alpha = alpha; canvas.saveLayer(null, layer) }
        } else canvas.save()
        val fontSize = element.fontSize * scale
        val spacing = element.letterSpacing * fontSize
        element.backgroundArgb?.let { argb ->
            val corner = element.backgroundCornerRadius * scale
            Paint().use { paint ->
                paint.isAntiAlias = true
                paint.color = argb.toInt()
                canvas.drawRRect(RRect.makeXYWH(0f, 0f, width, height, corner), paint)
            }
        }
        val metrics = Font(typeface, fontSize).use { it.metrics }
        val measurePaint = Paint()
        val lines = CompositionTextLayout.layout(
            element, width, height, scale,
            CompositionTextLayout.Metrics(metrics.ascent, metrics.descent),
        ) { text -> paragraph(text, typeface, fontSize, spacing, measurePaint).use { it.maxIntrinsicWidth } }
        measurePaint.close()
        val strokeWidth = element.strokeWidth * fontSize

        fun pass(argb: Long, mode: PaintMode, dx: Float = 0f, dy: Float = 0f, blur: Float = 0f) = Paint().use { paint ->
            paint.isAntiAlias = true
            paint.color = argb.toInt()
            paint.mode = mode
            paint.strokeWidth = strokeWidth
            paint.strokeJoin = PaintStrokeJoin.ROUND
            val mask = if (blur > 0f) MaskFilter.makeBlur(FilterBlurMode.NORMAL, blurSigma(blur), true) else null
            paint.maskFilter = mask
            lines.forEach { line ->
                paragraph(line.text, typeface, fontSize, spacing, paint).use { p ->
                    p.paint(canvas, line.x + dx, line.baseline + dy - p.alphabeticBaseline)
                }
            }
            mask?.close()
        }

        element.shadowArgb?.let { argb ->
            pass(
                argb,
                if (element.strokeArgb != null) PaintMode.STROKE_AND_FILL else PaintMode.FILL,
                element.shadowOffsetX * scale, element.shadowOffsetY * scale, element.shadowRadius * scale,
            )
        }
        element.strokeArgb?.let { pass(it, PaintMode.STROKE) }
        pass(element.colorArgb, PaintMode.FILL)
        canvas.restoreToCount(checkpoint)
    }
}

internal actual fun DrawScope.drawCompositionText(element: CompositionElement.Text, scale: Float, fontBytes: ByteArray?) {
    val font = CompositionFonts.resolve(element.fontFamily)
    val face = fontBytes?.let { SkiaCompositionFonts.typeface(font, font.weight(element.fontWeight), it) }
    drawIntoCanvas { SkiaTextPainter.draw(it.nativeCanvas, element, size.width, size.height, scale, face) }
}
