package eu.studio742.imago.feature.composer

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionFonts
import eu.studio742.imago.core.composition.CompositionTextLayout

/**
 * Drawing a text on Android, the same for the stage and for the exporter.
 *
 * The order of the layers is the same as on desktop: background, shadow, outline and, on top, the fill.
 * The shadow is drawn by hand, with the letter offset and blurred, and not with `setShadowLayer`.
 * `setShadowLayer` draws no shadow at all with radius zero, and a hard shadow is a shadow too.
 */
internal object AndroidTextPainter {

    /**
     * Draws [element] in a box of [width] by [height] with the corner at (0, 0). [alpha] is the
     * element's opacity. It is applied to the whole text in a layer, like the stage's `graphicsLayer`,
     * so the outline does not show through the fill.
     */
    fun draw(canvas: Canvas, element: CompositionElement.Text, width: Float, height: Float, scale: Float, face: Typeface, alpha: Int = 255) {
        if (alpha <= 0) return
        val checkpoint = if (alpha < 255) canvas.saveLayerAlpha(null, alpha) else canvas.save()
        val fontSize = element.fontSize * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = fontSize
            letterSpacing = element.letterSpacing
        }
        element.backgroundArgb?.let { argb ->
            val corner = element.backgroundCornerRadius * scale
            canvas.drawRoundRect(0f, 0f, width, height, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb.toInt() })
        }
        val metrics = paint.fontMetrics
        val lines = CompositionTextLayout.layout(
            element, width, height, scale,
            CompositionTextLayout.Metrics(metrics.ascent, metrics.descent),
            paint::measureText,
        )
        val strokeWidth = element.strokeWidth * fontSize
        element.shadowArgb?.let { argb ->
            paint.color = argb.toInt()
            paint.style = if (element.strokeArgb != null) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.strokeWidth = strokeWidth
            paint.strokeJoin = Paint.Join.ROUND
            val radius = element.shadowRadius * scale
            paint.maskFilter = if (radius > 0f) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
            val dx = element.shadowOffsetX * scale
            val dy = element.shadowOffsetY * scale
            lines.forEach { canvas.drawText(it.text, it.x + dx, it.baseline + dy, paint) }
            paint.maskFilter = null
        }
        element.strokeArgb?.let { argb ->
            paint.color = argb.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.strokeJoin = Paint.Join.ROUND
            lines.forEach { canvas.drawText(it.text, it.x, it.baseline, paint) }
        }
        paint.color = element.colorArgb.toInt()
        paint.style = Paint.Style.FILL
        lines.forEach { canvas.drawText(it.text, it.x, it.baseline, paint) }
        canvas.restoreToCount(checkpoint)
    }
}

internal actual fun DrawScope.drawCompositionText(element: CompositionElement.Text, scale: Float, fontBytes: ByteArray?) {
    val font = CompositionFonts.resolve(element.fontFamily)
    val face = fontBytes?.let { AndroidCompositionFonts.typeface(font, font.weight(element.fontWeight), it) } ?: Typeface.DEFAULT
    drawIntoCanvas { AndroidTextPainter.draw(it.nativeCanvas, element, size.width, size.height, scale, face) }
}
