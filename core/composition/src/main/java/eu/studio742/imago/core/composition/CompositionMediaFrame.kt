package eu.studio742.imago.core.composition

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable enum class MediaCutout { RECTANGLE, ELLIPSE, CIRCLE, DIAMOND, HEXAGON, STAR, HEART, TORN_PAPER }
@Serializable enum class MediaFrameKind { NONE, CLASSIC, DOUBLE_LINE, POLAROID, TORN_PAPER }

/** Original procedural artwork: no downloaded assets or third-party frame licences are needed. */
@Serializable
data class MediaFrameDecoration(
    val kind: MediaFrameKind = MediaFrameKind.NONE,
    val width: Float = 24f,
    val colorArgb: Long = 0xFFFFFFFF,
    val opacity: Float = 1f,
    val bottomWeight: Float = 2f,
    val roughness: Float = .4f,
    val seed: Int = 742,
)

data class FramePoint(val x: Float, val y: Float)
data class FrameInsets(val left: Float, val top: Float, val right: Float, val bottom: Float)

fun FrameStyle.decorationInsets(unit: Float = 1f): FrameInsets {
    val width = if (decoration.kind == MediaFrameKind.NONE) 0f else decoration.width.coerceIn(0f, 120f) * unit
    val bottom = width * if (decoration.kind == MediaFrameKind.POLAROID) 1f + decoration.bottomWeight.coerceIn(0f, 4f) else 1f
    return FrameInsets(width, width, width, bottom)
}

/** Fixed sample counts and explicit integer PRNG keep torn edges identical at every export size. */
fun tornFramePolygon(left: Float, top: Float, width: Float, height: Float, seed: Int, roughness: Float): List<FramePoint> {
    var state = seed
    fun random(): Float {
        state = state * 1664525 + 1013904223
        return (state ushr 8).toFloat() / 16777216f
    }
    val amount = min(width, height) * .045f * roughness.coerceIn(0f, 1f)
    return buildList {
        repeat(32) { i -> add(FramePoint(left + width * i / 32f, top + random() * amount)) }
        repeat(32) { i -> add(FramePoint(left + width - random() * amount, top + height * i / 32f)) }
        repeat(32) { i -> add(FramePoint(left + width * (1f - i / 32f), top + height - random() * amount)) }
        repeat(32) { i -> add(FramePoint(left + random() * amount, top + height * (1f - i / 32f))) }
    }
}

fun MediaCutout.polygon(width: Float, height: Float, decoration: MediaFrameDecoration): List<FramePoint> {
    val cx = width / 2f; val cy = height / 2f; val radius = min(width, height) / 2f
    fun regular(points: Int, angle: Double = -PI / 2) = List(points) { i ->
        val a = angle + 2 * PI * i / points
        FramePoint(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat())
    }
    return when (this) {
        MediaCutout.RECTANGLE -> listOf(FramePoint(0f, 0f), FramePoint(width, 0f), FramePoint(width, height), FramePoint(0f, height))
        MediaCutout.ELLIPSE -> List(128) { i -> val a = 2 * PI * i / 128; FramePoint(cx + cx * cos(a).toFloat(), cy + cy * sin(a).toFloat()) }
        MediaCutout.CIRCLE -> regular(128)
        MediaCutout.DIAMOND -> regular(4)
        MediaCutout.HEXAGON -> regular(6)
        MediaCutout.STAR -> List(10) { i ->
            val a = -PI / 2 + PI * i / 5; val r = radius * if (i % 2 == 0) 1f else .45f
            FramePoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }
        MediaCutout.HEART -> List(128) { i ->
            val t = 2 * PI * i / 128
            val x = sin(t).pow(3)
            val y = (13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)) / 17
            FramePoint(cx + radius * x.toFloat(), cy - radius * y.toFloat())
        }
        MediaCutout.TORN_PAPER -> tornFramePolygon(0f, 0f, width, height, decoration.seed, decoration.roughness)
    }
}

/** Shared cover geometry: pan is the fraction of the source's spare area, not a page translation. */
data class PlacementImageRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun placementImageRect(sourceWidth: Float, sourceHeight: Float, width: Float, height: Float, crop: PlacementCrop): PlacementImageRect {
    val cover = max(width / sourceWidth.coerceAtLeast(1f), height / sourceHeight.coerceAtLeast(1f)) * crop.scale.coerceAtLeast(1f)
    val w = sourceWidth * cover; val h = sourceHeight * cover
    return PlacementImageRect((width - w) / 2f - crop.offsetX.coerceIn(-1f, 1f) * (w - width) / 2f,
        (height - h) / 2f - crop.offsetY.coerceIn(-1f, 1f) * (h - height) / 2f, w, h)
}

fun CompositionElement.mediaFrame(): FrameStyle? = when (this) {
    is CompositionElement.Photo -> frame
    is CompositionElement.Video -> frame
    is CompositionElement.MediaPlaceholder -> frame
    else -> null
}

fun CompositionElement.withMediaFrame(frame: FrameStyle): CompositionElement = when (this) {
    is CompositionElement.Photo -> copy(frame = frame)
    is CompositionElement.Video -> copy(frame = frame)
    is CompositionElement.MediaPlaceholder -> copy(frame = frame)
    else -> this
}
