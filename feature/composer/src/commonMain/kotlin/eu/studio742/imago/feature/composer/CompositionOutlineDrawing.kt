package eu.studio742.imago.feature.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.FrameStyle
import eu.studio742.imago.core.composition.ShapeKind
import kotlin.math.min

/** Content geometry shared by the stage, thumbnails, Android exports and Windows exports. */
internal fun DrawScope.drawCompositionShape(element: CompositionElement.Shape, unit: Float, opacity: Float = 1f) {
    val fill = Color(element.fillArgb)
    val stroke = Color(element.strokeArgb)
    val width = (element.strokeWidth * unit).coerceAtLeast(0f)
    val radius = element.cornerRadius.coerceIn(0f, .5f) * min(size.width, size.height)
    when (element.kind) {
        ShapeKind.RECTANGLE -> {
            drawRoundRect(fill, cornerRadius = CornerRadius(radius), alpha = opacity)
            drawOutsideOutline(stroke, width, radius, opacity)
        }
        ShapeKind.ELLIPSE -> {
            drawOval(fill, alpha = opacity)
            drawOutsideOutline(stroke, width, 0f, opacity, ellipse = true)
        }
        ShapeKind.LINE, ShapeKind.ARROW -> if (width > 0f) {
            drawLine(stroke, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), width, StrokeCap.Round, alpha = opacity)
            if (element.kind == ShapeKind.ARROW) {
                val head = min(size.width, size.height) * .22f
                val tip = Offset(size.width, size.height / 2f)
                drawLine(stroke, tip, tip + Offset(-head, -head), width, StrokeCap.Round, alpha = opacity)
                drawLine(stroke, tip, tip + Offset(-head, head), width, StrokeCap.Round, alpha = opacity)
            }
        }
    }
}

internal fun DrawScope.drawCompositionFrame(frame: FrameStyle, unit: Float, opacity: Float = 1f) {
    if (frame.cutout == eu.studio742.imago.core.composition.MediaCutout.RECTANGLE) {
        drawOutsideOutline(Color(frame.borderArgb), frame.borderWidth * unit,
            frame.cornerRadius.coerceIn(0f, .5f) * min(size.width, size.height), opacity)
    } else drawMediaCutoutOutline(frame, unit, opacity)
}

/** A filled ring avoids rounded stroke joins and preserves a genuinely empty interior. */
private fun DrawScope.drawOutsideOutline(color: Color, width: Float, radius: Float, opacity: Float, ellipse: Boolean = false) {
    if (width <= 0f) return
    val inside = Rect(Offset.Zero, size)
    val outside = Rect(-width, -width, size.width + width, size.height + width)
    val ring = Path().apply {
        fillType = PathFillType.EvenOdd
        if (ellipse) {
            addOval(outside); addOval(inside)
        } else {
            // Zero means square on both sides of the ring, regardless of the outline's width.
            addRoundRect(RoundRect(outside, CornerRadius(if (radius > 0f) radius + width else 0f)))
            addRoundRect(RoundRect(inside, CornerRadius(radius)))
        }
    }
    drawPath(ring, color, alpha = opacity)
}

private data class CompositionCornerShape(val fraction: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(Rect(Offset.Zero, size), CornerRadius(fraction.coerceIn(0f, .5f) * min(size.width, size.height))))
}

@Composable
internal fun Modifier.compositionFrameDecoration(frame: FrameStyle, unit: Float, opacity: Float = 1f): Modifier = drawWithContent {
    // The layer includes outside paper and shadows; an element-sized graphicsLayer would crop them.
    if (opacity < 1f) drawContext.canvas.saveLayer(mediaPaintBounds(frame, size, unit), androidx.compose.ui.graphics.Paint().apply { alpha = opacity })
    else drawContext.canvas.save()
    try {
        drawCompositionMediaBackground(frame, unit)
        clipPath(mediaCutoutPath(frame, size)) { this@drawWithContent.drawContent() }
        drawCompositionFrame(frame, unit)
    } finally { drawContext.canvas.restore() }
}
