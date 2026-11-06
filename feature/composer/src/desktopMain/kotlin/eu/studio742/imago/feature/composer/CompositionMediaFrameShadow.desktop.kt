package eu.studio742.imago.feature.composer

import androidx.compose.ui.graphics.asDesktopPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import eu.studio742.imago.core.composition.FrameStyle
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import kotlin.math.roundToInt

internal actual fun DrawScope.drawCompositionMediaShadow(frame: FrameStyle, unit: Float, opacity: Float) {
    val radius = frame.shadowRadius * unit
    if (radius <= 0f) return
    MaskFilter.makeBlur(FilterBlurMode.NORMAL, radius, true).use { mask ->
        Paint().use { paint ->
            paint.isAntiAlias = true; paint.color = 0xFF000000.toInt(); paint.alpha = (opacity * 127).roundToInt(); paint.maskFilter = mask
            drawContext.canvas.nativeCanvas.drawPath(mediaShadowPath(frame, size, unit).asDesktopPath(), paint)
        }
    }
}
