package eu.studio742.imago.feature.composer

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.studio742.imago.core.composition.*
import kotlin.math.min

internal fun framePolygonPath(points: List<FramePoint>): Path = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

internal fun mediaCutoutPath(frame: FrameStyle, size: Size): Path = if (frame.cutout == MediaCutout.RECTANGLE) Path().apply {
    addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(frame.cornerRadius.coerceIn(0f, .5f) * min(size.width, size.height))))
} else framePolygonPath(frame.cutout.polygon(size.width, size.height, frame.decoration))

internal fun mediaPaperPath(frame: FrameStyle, size: Size, unit: Float): Path {
    val insets = frame.decorationInsets(unit)
    val rect = Rect(-insets.left, -insets.top, size.width + insets.right, size.height + insets.bottom)
    return if (frame.decoration.kind == MediaFrameKind.TORN_PAPER) {
        val maximum = insets.left * .6f / (min(rect.width, rect.height) * .045f).coerceAtLeast(.001f)
        framePolygonPath(tornFramePolygon(rect.left, rect.top, rect.width, rect.height, frame.decoration.seed,
            min(frame.decoration.roughness, maximum)))
    } else Path().apply { addRect(rect) }
}

internal fun mediaShadowPath(frame: FrameStyle, size: Size, unit: Float): Path =
    if (frame.decoration.kind == MediaFrameKind.NONE || frame.decoration.width <= 0f) mediaCutoutPath(frame, size) else mediaPaperPath(frame, size, unit)

internal fun mediaPaintBounds(frame: FrameStyle, size: Size, unit: Float): Rect {
    val paper = frame.decorationInsets(unit)
    val border = frame.borderWidth * unit
    val shadow = frame.shadowRadius * unit * 3f
    return Rect(-maxOf(paper.left, border) - shadow, -maxOf(paper.top, border) - shadow,
        size.width + maxOf(paper.right, border) + shadow, size.height + maxOf(paper.bottom, border) + shadow)
}

internal data class CompositionMediaShape(val frame: FrameStyle) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(mediaCutoutPath(frame, size))
}

internal expect fun DrawScope.drawCompositionMediaShadow(frame: FrameStyle, unit: Float, opacity: Float)

internal fun DrawScope.drawCompositionMediaBackground(frame: FrameStyle, unit: Float, opacity: Float = 1f) {
    if (frame.shadowRadius > 0f) drawCompositionMediaShadow(frame, unit, opacity)
    if (frame.decoration.kind != MediaFrameKind.NONE && frame.decoration.width > 0f) {
        val insets = frame.decorationInsets(unit)
        val colorAlpha = (frame.decoration.colorArgb ushr 24 and 255) / 255f
        val layer = Paint().apply { alpha = frame.decoration.opacity * opacity * colorAlpha }
        val opaque = frame.copy(decoration = frame.decoration.copy(opacity = 1f, colorArgb = frame.decoration.colorArgb or 0xFF000000))
        drawContext.canvas.saveLayer(Rect(-insets.left, -insets.top, size.width + insets.right, size.height + insets.bottom), layer)
        try {
            drawPath(mediaPaperPath(opaque, size, unit), Color(opaque.decoration.colorArgb))
            drawMediaDecorationDetails(opaque, unit, 1f)
        } finally { drawContext.canvas.restore() }
    }
}

internal fun DrawScope.drawMediaCutoutOutline(frame: FrameStyle, unit: Float, opacity: Float) {
    val width = frame.borderWidth.coerceAtLeast(0f) * unit
    if (width <= 0f) return
    val path = mediaCutoutPath(frame, size)
    clipPath(path, ClipOp.Difference) {
        drawPath(path, Color(frame.borderArgb), alpha = opacity, style = Stroke(width * 2f, join = StrokeJoin.Round))
    }
}

internal fun DrawScope.drawMediaDecorationDetails(frame: FrameStyle, unit: Float, opacity: Float) {
    val decoration = frame.decoration
    if (decoration.width <= 0f) return
    val width = decoration.width * unit
    val color = Color(decoration.colorArgb)
    val alpha = decoration.opacity * opacity
    when (decoration.kind) {
        MediaFrameKind.DOUBLE_LINE -> {
            val dark = color.copy(red = color.red * .55f, green = color.green * .55f, blue = color.blue * .55f)
            listOf(.2f, .75f).forEach { fraction ->
                val offset = width * fraction
                drawRect(dark, Offset(-offset, -offset), Size(size.width + offset * 2f, size.height + offset * 2f),
                    alpha = alpha, style = Stroke((width * .06f).coerceAtLeast(.5f * unit)))
            }
        }
        MediaFrameKind.CLASSIC -> {
            val light = color.copy(red = color.red + (1f - color.red) * .35f,
                green = color.green + (1f - color.green) * .35f, blue = color.blue + (1f - color.blue) * .35f)
            val dark = color.copy(red = color.red * .65f, green = color.green * .65f, blue = color.blue * .65f)
            val stroke = width * .2f
            drawLine(light, Offset(-width + stroke / 2f, -width + stroke / 2f), Offset(size.width + width - stroke / 2f, -width + stroke / 2f), stroke, alpha = alpha)
            drawLine(light, Offset(-width + stroke / 2f, -width), Offset(-width + stroke / 2f, size.height + width), stroke, alpha = alpha)
            drawLine(dark, Offset(-width, size.height + width - stroke / 2f), Offset(size.width + width, size.height + width - stroke / 2f), stroke, alpha = alpha)
            drawLine(dark, Offset(size.width + width - stroke / 2f, -width), Offset(size.width + width - stroke / 2f, size.height + width), stroke, alpha = alpha)
        }
        else -> Unit
    }
}
