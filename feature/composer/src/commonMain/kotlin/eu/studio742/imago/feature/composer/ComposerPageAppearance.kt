package eu.studio742.imago.feature.composer

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect

/** Runs before the artwork's rotation layer, keeping the page edge fixed in stage coordinates. */
internal fun Modifier.composerPageAppearance(
    pageBounds: Rect,
    viewportBounds: Rect,
    outsideAlpha: Float,
): Modifier = drawWithCache {
    val outsidePaint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        alpha = outsideAlpha.coerceIn(0f, 1f)
    }
    onDrawWithContent {
        clipRect(pageBounds.left, pageBounds.top, pageBounds.right, pageBounds.bottom) {
            this@onDrawWithContent.drawContent()
        }
        clipRect(pageBounds.left, pageBounds.top, pageBounds.right, pageBounds.bottom, ClipOp.Difference) {
            // The viewport bounds include rotated corners and outside strokes. Element-sized layer
            // bounds would crop them before the page mask could be applied.
            drawContext.canvas.saveLayer(viewportBounds, outsidePaint)
            try { this@onDrawWithContent.drawContent() }
            finally { drawContext.canvas.restore() }
        }
    }
}
