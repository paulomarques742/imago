package eu.studio742.imago.feature.composer

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import eu.studio742.imago.core.composition.FrameStyle
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private data class ShadowKey(val frame: FrameStyle, val width: Int, val height: Int, val unit: Float)
private val frameShadows = object : LruCache<ShadowKey, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: ShadowKey, value: Bitmap) = value.allocationByteCount
}

internal actual fun DrawScope.drawCompositionMediaShadow(frame: FrameStyle, unit: Float, opacity: Float) {
    val radius = frame.shadowRadius * unit
    if (radius <= 0f) return
    val path = mediaShadowPath(frame, size, unit).asAndroidPath()
    val canvas = drawContext.canvas.nativeCanvas
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); alpha = (opacity * 127).roundToInt()
        maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    }
    if (!canvas.isHardwareAccelerated) { canvas.drawPath(path, paint); return }
    // Hardware Canvas ignores MaskFilter. Rasterising only the shadow keeps the native video hardware-backed.
    val bounds = RectF().also { path.computeBounds(it, true) }.apply { inset(-radius * 3f, -radius * 3f) }
    val scale = min(1f, 1024f / max(bounds.width(), bounds.height()))
    val key = ShadowKey(frame, ceil(size.width).toInt(), ceil(size.height).toInt(), unit)
    val bitmap = frameShadows.get(key) ?: Bitmap.createBitmap(ceil(bounds.width() * scale).toInt().coerceAtLeast(1),
        ceil(bounds.height() * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { image ->
        val rasterPath = android.graphics.Path(path).apply {
            offset(-bounds.left, -bounds.top)
            transform(Matrix().apply { setScale(scale, scale) })
        }
        val rasterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); alpha = 127; maskFilter = BlurMaskFilter(radius * scale, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(image).drawPath(rasterPath, rasterPaint)
        frameShadows.put(key, image)
    }
    canvas.drawBitmap(bitmap, null, bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (opacity * 255).roundToInt() })
}
