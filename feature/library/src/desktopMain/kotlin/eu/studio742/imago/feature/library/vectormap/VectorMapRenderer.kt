package eu.studio742.imago.feature.library.vectormap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import eu.studio742.imago.feature.library.TILE_SIZE
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the map looks, as fractions of the world (0 to 1 from the west and from the top) and a zoom. */
data class Viewport(val centerX: Double, val centerY: Double, val zoom: Double)

/**
 * Draws OpenFreeMap's vector tiles the way their MapLibre style says: the fills and lines layer by
 * layer across the tiles, then the names and icons, placed so that none covers another.
 *
 * What a tile needs to be drawn at a zoom — its features grouped into one path per colour and width —
 * is worked out once per quarter of a zoom level and kept with the tile.
 */
class VectorMapRenderer(
    private val setup: MapSetup,
    private val tiles: TileStore,
    private val textMeasurer: TextMeasurer,
) {
    private val style = setup.style
    private val layouts = object : LinkedHashMap<LabelKey, TextLayoutResult>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LabelKey, TextLayoutResult>) = size > 1024
    }
    private val patterns = HashMap<String, ImageBitmap?>()

    fun draw(scope: DrawScope, viewport: Viewport) = with(scope) {
        val density = density
        val worldPx = TILE_SIZE * density * 2.0.pow(viewport.zoom)
        val tileZoom = floor(viewport.zoom).toInt().coerceIn(0, setup.maxZoom)
        val bucket = (viewport.zoom * 4).roundToInt()
        val styleZoom = bucket / 4.0
        val count = 1 shl tileZoom
        val tilePx = worldPx / count
        val canvas = size
        val left = viewport.centerX * worldPx - canvas.width / 2
        val top = viewport.centerY * worldPx - canvas.height / 2
        val visible = buildList {
            for (ty in floor(top / tilePx).toInt().coerceAtLeast(0)..floor((top + canvas.height) / tilePx).toInt().coerceAtMost(count - 1)) {
                for (tx in floor(left / tilePx).toInt()..floor((left + canvas.width) / tilePx).toInt()) {
                    val key = TileKey(tileZoom, Math.floorMod(tx, count), ty)
                    val rect = Rect((tx * tilePx - left).toFloat(), (ty * tilePx - top).toFloat(), ((tx + 1) * tilePx - left).toFloat(), ((ty + 1) * tilePx - top).toFloat())
                    add(placed(key, rect))
                }
            }
        }

        for ((index, layer) in style.layers.withIndex()) {
            if (!layer.showsAt(viewport.zoom)) continue
            when (layer.type) {
                "background" -> {
                    val context = EvaluationContext(styleZoom, null)
                    val color = Expressions.color(layer.paint("background-color", context)) ?: Color.Black
                    drawRect(color, alpha = number(layer.paint("background-opacity", context), 1.0).toFloat())
                }
                "fill", "line" -> for (tile in visible) {
                    val entry = tile.entry ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val batches = entry.drawCache.getOrPut(index to bucket) { batches(entry.tile, layer, styleZoom) } as List<Batch>
                    if (batches.isEmpty()) continue
                    val extent = entry.tile.layers[layer.sourceLayer]?.extent ?: 4096
                    val scale = tile.drawn.width / extent
                    clipRect(tile.clip.left, tile.clip.top, tile.clip.right, tile.clip.bottom) {
                        withTransform({ translate(tile.drawn.left, tile.drawn.top); scale(scale, scale, Offset.Zero) }) {
                            batches.forEach { drawBatch(it, scale, density) }
                        }
                    }
                }
            }
        }
        drawSymbols(visible.filter { it.entry != null && it.drawn == it.clip }, viewport, bucket, styleZoom)
    }

    /** A visible slot of the grid, drawn with its own tile or, while that one loads, with an ancestor's. */
    private class PlacedTile(val entry: TileEntry?, val drawn: Rect, val clip: Rect)

    private fun placed(key: TileKey, rect: Rect): PlacedTile {
        tiles.get(key)?.let { return PlacedTile(it, rect, rect) }
        tiles.request(key)
        var ancestor = key.parent()
        var depth = 1
        while (ancestor != null && depth <= FALLBACK_LEVELS) {
            tiles.get(ancestor)?.let { entry ->
                val factor = 1 shl depth
                val size = rect.width * factor
                val offsetX = (key.x - ancestor!!.x * factor) * rect.width
                val offsetY = (key.y - ancestor!!.y * factor) * rect.height
                return PlacedTile(entry, Rect(Offset(rect.left - offsetX, rect.top - offsetY), Size(size, size)), rect)
            }
            ancestor = ancestor.parent()
            depth++
        }
        return PlacedTile(null, rect, rect)
    }

    private sealed interface Batch
    private class FillBatch(val path: Path, val color: Color, val opacity: Float, val pattern: String?, val outline: Color?) : Batch
    private class LineBatch(
        val path: Path, val color: Color, val opacity: Float, val width: Float, val dash: List<Float>?,
        val cap: StrokeCap, val join: StrokeJoin,
    ) : Batch

    private fun batches(tile: VectorTile, layer: StyleLayer, zoom: Double): List<Batch> {
        val source = tile.layers[layer.sourceLayer] ?: return emptyList()
        val groups = LinkedHashMap<List<Any?>, Pair<Path, () -> Batch>>()
        for (feature in source.features) {
            if (!layer.accepts(feature, zoom)) continue
            val context = EvaluationContext(zoom, feature)
            if (layer.type == "fill") {
                if (feature.type != GeometryType.POLYGON) continue
                val color = Expressions.color(layer.paint("fill-color", context)) ?: Color.Black
                val opacity = number(layer.paint("fill-opacity", context), 1.0).toFloat()
                val pattern = layer.paint("fill-pattern", context) as? String
                val outline = Expressions.color(layer.paint("fill-outline-color", context))
                val key = listOf(color, opacity, pattern, outline)
                val path = groups.getOrPut(key) {
                    val path = Path().apply { fillType = PathFillType.EvenOdd }
                    path to { FillBatch(path, color, opacity, pattern, outline) }
                }.first
                feature.parts.forEach { path.addPart(it, close = true) }
            } else {
                if (feature.type == GeometryType.POINT) continue
                val color = Expressions.color(layer.paint("line-color", context)) ?: Color.Black
                val opacity = number(layer.paint("line-opacity", context), 1.0).toFloat()
                val width = number(layer.paint("line-width", context), 1.0).toFloat()
                @Suppress("UNCHECKED_CAST")
                val dash = (layer.paint("line-dasharray", context) as? List<Any?>)?.mapNotNull { (it as? Number)?.toFloat() }
                    ?.takeIf { it.size >= 2 && it.any { value -> value > 0f } && it.drop(1).any { value -> value > 0f } }
                val cap = when (layer.layout("line-cap", context)) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt }
                val join = when (layer.layout("line-join", context)) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter }
                val key = listOf(color, opacity, width, dash, cap, join)
                val path = groups.getOrPut(key) {
                    val path = Path()
                    path to { LineBatch(path, color, opacity, width, dash, cap, join) }
                }.first
                feature.parts.forEach { path.addPart(it, close = feature.type == GeometryType.POLYGON) }
            }
        }
        return groups.values.map { it.second() }
    }

    private fun Path.addPart(points: FloatArray, close: Boolean) {
        if (points.size < 2) return
        moveTo(points[0], points[1])
        var i = 2
        while (i + 1 < points.size) { lineTo(points[i], points[i + 1]); i += 2 }
        if (close) close()
    }

    private fun DrawScope.drawBatch(batch: Batch, scale: Float, density: Float) {
        when (batch) {
            is FillBatch -> {
                val brush = batch.pattern?.let(::patternBrush)
                if (brush != null) drawPath(batch.path, brush, alpha = batch.opacity)
                else drawPath(batch.path, batch.color, alpha = batch.opacity)
                batch.outline?.let { drawPath(batch.path, it, alpha = batch.opacity, style = Stroke(width = density / scale)) }
            }
            is LineBatch -> {
                val widthPx = batch.width * density
                if (widthPx <= 0f) return
                val stroke = widthPx / scale
                drawPath(
                    batch.path, batch.color, alpha = batch.opacity,
                    style = Stroke(
                        width = stroke, cap = batch.cap, join = batch.join,
                        pathEffect = batch.dash?.let { dash -> PathEffect.dashPathEffect(dash.map { (it * stroke).coerceAtLeast(0.01f) }.toFloatArray()) },
                    ),
                )
            }
        }
    }

    /** A sprite pattern as a repeating brush; the wood of the parks. */
    private fun patternBrush(name: String): ShaderBrush? {
        val image = patterns.getOrPut(name) {
            val sprite = setup.sprite ?: return@getOrPut null
            val icon = sprite.icons[name] ?: return@getOrPut null
            val bitmap = ImageBitmap(icon.width, icon.height)
            androidx.compose.ui.graphics.Canvas(bitmap).drawImageRect(
                sprite.image, IntOffset(icon.x, icon.y), IntSize(icon.width, icon.height), IntOffset.Zero, IntSize(icon.width, icon.height),
                androidx.compose.ui.graphics.Paint(),
            )
            bitmap
        } ?: return null
        return ShaderBrush(ImageShader(image, TileMode.Repeated, TileMode.Repeated))
    }

    // Names and icons.

    private class Label(
        val text: String?,
        val icon: String?,
        val iconSize: Float,
        /** Where it hangs, in tile units; along a line, the middle of its longest stretch. */
        val x: Float,
        val y: Float,
        /** Along a line, the stretch it follows, in tile units, to rotate with it. */
        val along: FloatArray?,
        val alongMap: Boolean,
        val size: Float,
        val color: Color,
        val halo: Color?,
        val haloWidth: Float,
        val anchor: String,
        val offsetX: Float,
        val offsetY: Float,
        val wrap: Boolean,
        val iconAlongEvery: Float?,
        val iconRotate: Float,
        val line: FloatArray?,
    )

    private fun DrawScope.drawSymbols(visible: List<PlacedTile>, viewport: Viewport, bucket: Int, zoom: Double) {
        val taken = ArrayList<Rect>()
        val bounds = Rect(0f, 0f, size.width, size.height)
        val placedLabels = ArrayList<() -> Unit>()
        // Higher layers are placed first: they are the ones the style wants seen when two collide.
        for ((index, layer) in style.layers.withIndex().reversed()) {
            if (layer.type != "symbol" || !layer.showsAt(viewport.zoom)) continue
            for (tile in visible) {
                val entry = tile.entry ?: continue
                @Suppress("UNCHECKED_CAST")
                val labels = entry.drawCache.getOrPut(index to bucket) { labels(entry.tile, layer, zoom) } as List<Label>
                val extent = entry.tile.layers[layer.sourceLayer]?.extent ?: 4096
                val scale = tile.drawn.width / extent
                for (label in labels) placeLabel(label, tile, scale, taken, bounds)?.let(placedLabels::add)
            }
        }
        placedLabels.asReversed().forEach { it() }
    }

    private fun labels(tile: VectorTile, layer: StyleLayer, zoom: Double): List<Label> {
        val source = tile.layers[layer.sourceLayer] ?: return emptyList()
        val extent = source.extent.toFloat()
        return source.features.flatMap { feature ->
            if (!layer.accepts(feature, zoom)) return@flatMap emptyList()
            val context = EvaluationContext(zoom, feature)
            val transform = layer.layout("text-transform", context) as? String
            val text = (layer.layout("text-field", context) as? String)?.trim()?.takeIf(String::isNotEmpty)
                ?.let { if (transform == "uppercase") it.uppercase() else if (transform == "lowercase") it.lowercase() else it }
            val icon = (layer.layout("icon-image", context) as? String)?.takeIf(String::isNotEmpty)
            if (text == null && icon == null) return@flatMap emptyList()
            val onLine = layer.layout("symbol-placement", context) == "line" && feature.type == GeometryType.LINESTRING
            @Suppress("UNCHECKED_CAST")
            val offset = (layer.layout("text-offset", context) as? List<Any?>)?.map { (it as? Number)?.toFloat() ?: 0f } ?: listOf(0f, 0f)
            val sizeValue = number(layer.layout("text-size", context), 16.0).toFloat()
            val common = { x: Float, y: Float, along: FloatArray?, line: FloatArray? ->
                Label(
                    text = text, icon = icon,
                    iconSize = number(layer.layout("icon-size", context), 1.0).toFloat(),
                    x = x, y = y, along = along,
                    alongMap = layer.layout("text-rotation-alignment", context) != "viewport",
                    size = sizeValue,
                    color = Expressions.color(layer.paint("text-color", context)) ?: Color.Black,
                    halo = Expressions.color(layer.paint("text-halo-color", context)),
                    haloWidth = number(layer.paint("text-halo-width", context), 0.0).toFloat(),
                    anchor = layer.layout("text-anchor", context) as? String ?: "center",
                    offsetX = offset.getOrElse(0) { 0f }, offsetY = offset.getOrElse(1) { 0f },
                    wrap = !onLine,
                    iconAlongEvery = if (onLine && text == null) number(layer.layout("symbol-spacing", context), 250.0).toFloat() else null,
                    iconRotate = number(layer.layout("icon-rotate", context), 0.0).toFloat(),
                    line = line,
                )
            }
            if (onLine) {
                val line = feature.parts.maxByOrNull { it.size } ?: return@flatMap emptyList()
                if (text == null) return@flatMap listOf(common(line[0], line[1], null, line))
                var best = -1f
                var at = 0
                var i = 0
                while (i + 3 < line.size) {
                    val length = hypot(line[i + 2] - line[i], line[i + 3] - line[i + 1])
                    if (length > best) { best = length; at = i }
                    i += 2
                }
                if (best <= 0f) return@flatMap emptyList()
                val segment = floatArrayOf(line[at], line[at + 1], line[at + 2], line[at + 3])
                listOf(common((segment[0] + segment[2]) / 2, (segment[1] + segment[3]) / 2, segment, null))
            } else {
                // A point in the tile's buffer belongs to the neighbour, which draws it. At the lowest
                // zooms one place comes as several points, one per copy of the world.
                feature.parts.flatMap { part -> (0 until part.size / 2).map { part[2 * it] to part[2 * it + 1] } }
                    .filter { (x, y) -> x >= 0 && y >= 0 && x < extent && y < extent }
                    .map { (x, y) -> common(x, y, null, null) }
            }
        }
    }

    private fun DrawScope.placeLabel(label: Label, tile: PlacedTile, scale: Float, taken: MutableList<Rect>, bounds: Rect): (() -> Unit)? {
        val density = density
        if (label.line != null) return placeLineIcons(label, tile, scale, taken, bounds)
        val anchorPoint = Offset(tile.drawn.left + label.x * scale, tile.drawn.top + label.y * scale)
        var angle = 0f
        val layout = label.text?.let { layout(it, label, density) }
        if (label.along != null) {
            val (x0, y0, x1, y1) = label.along.let { listOf(it[0], it[1], it[2], it[3]) }
            // A name longer than the stretch of road it would sit on would hang off it.
            val stretch = hypot(x1 - x0, y1 - y0) * scale
            if (stretch < MIN_LINE_LABEL_PX * density || stretch < (layout?.size?.width ?: 0)) return null
            if (label.alongMap) {
                angle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
                if (angle > 90f) angle -= 180f
                if (angle < -90f) angle += 180f
            }
        }
        val sizePx = label.size * density
        val textSize = layout?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size.Zero
        val iconBitmap = label.icon?.let { name -> setup.sprite?.let { sprite -> sprite.icons[name]?.let { sprite to it } } }
        val iconSize = iconBitmap?.let { (sprite, icon) ->
            Size(icon.width * label.iconSize * density / sprite.pixelRatio, icon.height * label.iconSize * density / sprite.pixelRatio)
        }
        val offset = Offset(label.offsetX * sizePx, label.offsetY * sizePx)
        val textTopLeft = anchorPoint + offset + when (label.anchor) {
            "left" -> Offset(0f, -textSize.height / 2)
            "right" -> Offset(-textSize.width, -textSize.height / 2)
            "top" -> Offset(-textSize.width / 2, 0f)
            "bottom" -> Offset(-textSize.width / 2, -textSize.height)
            "top-left" -> Offset.Zero
            "top-right" -> Offset(-textSize.width, 0f)
            "bottom-left" -> Offset(0f, -textSize.height)
            "bottom-right" -> Offset(-textSize.width, -textSize.height)
            else -> Offset(-textSize.width / 2, -textSize.height / 2)
        }
        val textRect = if (layout != null) rotatedBounds(Rect(textTopLeft, textSize), anchorPoint, angle).inflate(LABEL_PADDING * density) else null
        val iconRect = iconSize?.let { Rect(Offset(anchorPoint.x - it.width / 2, anchorPoint.y - it.height / 2), it) }
        val rects = listOfNotNull(textRect, iconRect)
        if (rects.isEmpty() || rects.none { it.overlaps(bounds) }) return null
        if (rects.any { rect -> taken.any(rect::overlaps) }) return null
        taken += rects
        return {
            iconBitmap?.let { (sprite, icon) ->
                val target = iconRect!!
                drawImage(sprite.image, IntOffset(icon.x, icon.y), IntSize(icon.width, icon.height),
                    IntOffset(target.left.roundToInt(), target.top.roundToInt()), IntSize(target.width.roundToInt().coerceAtLeast(1), target.height.roundToInt().coerceAtLeast(1)))
            }
            if (layout != null) {
                rotate(angle, anchorPoint) {
                    if (label.halo != null && label.haloWidth > 0f) {
                        drawText(layout, color = label.halo, topLeft = textTopLeft, drawStyle = Stroke(width = label.haloWidth * 2 * density, join = StrokeJoin.Round))
                    }
                    drawText(layout, color = label.color, topLeft = textTopLeft)
                }
            }
        }
    }

    /** Icons along a line, every so many pixels — the one-way arrows. */
    private fun DrawScope.placeLineIcons(label: Label, tile: PlacedTile, scale: Float, taken: MutableList<Rect>, bounds: Rect): (() -> Unit)? {
        val sprite = setup.sprite ?: return null
        val icon = sprite.icons[label.icon ?: return null] ?: return null
        val line = label.line ?: return null
        val every = (label.iconAlongEvery ?: return null) * density
        val width = icon.width * label.iconSize * density / sprite.pixelRatio
        val height = icon.height * label.iconSize * density / sprite.pixelRatio
        val spots = ArrayList<Pair<Offset, Float>>()
        var travelled = every / 2
        var i = 0
        while (i + 3 < line.size) {
            val a = Offset(tile.drawn.left + line[i] * scale, tile.drawn.top + line[i + 1] * scale)
            val b = Offset(tile.drawn.left + line[i + 2] * scale, tile.drawn.top + line[i + 3] * scale)
            val length = (b - a).getDistance()
            var at = travelled
            while (at <= length) {
                val t = at / length
                val point = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                val rect = Rect(Offset(point.x - width / 2, point.y - height / 2), Size(width, height))
                if (rect.overlaps(bounds) && tile.clip.contains(point) && taken.none(rect::overlaps)) {
                    taken += rect
                    spots += point to (Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat() + label.iconRotate)
                }
                at += every
            }
            travelled = at - length
            i += 2
        }
        if (spots.isEmpty()) return null
        return {
            for ((point, angle) in spots) {
                rotate(angle, point) {
                    drawImage(sprite.image, IntOffset(icon.x, icon.y), IntSize(icon.width, icon.height),
                        IntOffset((point.x - width / 2).roundToInt(), (point.y - height / 2).roundToInt()),
                        IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)))
                }
            }
        }
    }

    private data class LabelKey(val text: String, val size: Float, val wrap: Boolean)

    /** Names wrap at ten ems, as MapLibre's do by default; the colour is given when drawing. */
    private fun layout(text: String, label: Label, density: Float): TextLayoutResult {
        val key = LabelKey(text, label.size, label.wrap)
        return layouts.getOrPut(key) {
            textMeasurer.measure(
                text,
                TextStyle(fontSize = label.size.sp, textAlign = TextAlign.Center),
                constraints = if (label.wrap) Constraints(maxWidth = (label.size * density * MAX_WIDTH_EMS).roundToInt()) else Constraints(),
            )
        }
    }

    private fun rotatedBounds(rect: Rect, pivot: Offset, degrees: Float): Rect {
        if (degrees == 0f) return rect
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val corners = listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).map { corner ->
            val d = corner - pivot
            Offset(pivot.x + d.x * c - d.y * s, pivot.y + d.x * s + d.y * c)
        }
        return Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    private fun number(value: Any?, default: Double) = (value as? Number)?.toDouble() ?: default

    private companion object {
        /** How far up a tile that has not arrived is drawn from one that has. */
        const val FALLBACK_LEVELS = 4
        const val MAX_WIDTH_EMS = 10f
        const val LABEL_PADDING = 2f
        const val MIN_LINE_LABEL_PX = 24f
    }
}
