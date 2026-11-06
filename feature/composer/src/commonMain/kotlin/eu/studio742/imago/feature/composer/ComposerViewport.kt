package eu.studio742.imago.feature.composer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import eu.studio742.imago.core.composition.NormalizedRect

/** Screen-space navigation is transient and independent of artwork and changing toolbars. */
@Stable
internal class ComposerViewport {
    var scale by mutableFloatStateOf(0f)
        private set
    var origin by mutableStateOf(Offset.Zero)
        private set
    var navigating by mutableStateOf(false)
    private var previousSize = Size.Zero

    fun resize(size: Size, fitScale: Float, page: Int, pageSize: Size, top: Float, bottom: Float) {
        if (scale == 0f) fit(size, fitScale, page, pageSize, top, bottom)
        else origin += Offset((size.width - previousSize.width) / 2f, (size.height - previousSize.height) / 2f)
        previousSize = size
    }

    fun fit(size: Size, fitScale: Float, page: Int, pageSize: Size, top: Float, bottom: Float) {
        scale = fitScale
        origin = Offset((size.width - pageSize.width * scale) / 2f - page * pageSize.width * scale,
            top + (size.height - top - bottom - pageSize.height * scale) / 2f)
    }

    fun pan(delta: Offset) {
        if (delta.x.isFinite() && delta.y.isFinite()) origin += delta
    }

    fun transform(factor: Float, pivot: Offset, delta: Offset, fitScale: Float) {
        if (!factor.isFinite() || factor <= 0f || scale <= 0f) return
        val next = (scale * factor).coerceIn(fitScale * .25f, fitScale * 8f)
        origin = pivot + (origin - pivot) * (next / scale)
        scale = next
        pan(delta)
    }

    fun reveal(bounds: NormalizedRect, pageSize: Size, size: Size, top: Float, bottom: Float, margin: Float) {
        val width = pageSize.width * scale
        val height = pageSize.height * scale
        val left = origin.x + bounds.x * width
        val right = origin.x + bounds.right * width
        val first = origin.y + bounds.y * height
        val last = origin.y + bounds.bottom * height
        if (left >= margin && right <= size.width - margin && first >= top + margin && last <= size.height - bottom - margin) return
        scale = minOf(scale, (size.width - margin * 2).coerceAtLeast(1f) / (bounds.width * pageSize.width),
            (size.height - top - bottom - margin * 2).coerceAtLeast(1f) / (bounds.height * pageSize.height))
        origin = Offset(size.width / 2f - (bounds.x + bounds.width / 2f) * pageSize.width * scale,
            top + (size.height - top - bottom) / 2f - (bounds.y + bounds.height / 2f) * pageSize.height * scale)
    }
}
