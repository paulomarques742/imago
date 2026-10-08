package eu.studio742.imago.feature.library.vectormap

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow

/**
 * A MapLibre style, read for drawing: the layers in order, each with the source layer it draws, its
 * filter and its properties. Only what the OpenFreeMap styles use is understood — background, fill,
 * line and symbol layers, and the expressions in them.
 */
class MapStyle(
    val layers: List<StyleLayer>,
    /** The vector source's TileJSON, which says where the tiles are. */
    val vectorSourceUrl: String?,
    val spriteUrl: String?,
) {
    companion object {
        fun parse(text: String): MapStyle {
            val root = Json.parseToJsonElement(text).jsonObject
            val sources = root["sources"]?.jsonObject.orEmpty()
            val vector = sources.values.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.content == "vector" }
            val layers = root["layers"]?.jsonArray.orEmpty().map { element ->
                val layer = element.jsonObject
                StyleLayer(
                    id = layer.string("id").orEmpty(),
                    type = layer.string("type").orEmpty(),
                    sourceLayer = layer.string("source-layer"),
                    minZoom = layer["minzoom"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    maxZoom = layer["maxzoom"]?.jsonPrimitive?.doubleOrNull ?: 24.0,
                    filter = layer["filter"],
                    paint = layer["paint"]?.jsonObject.orEmpty(),
                    layout = layer["layout"]?.jsonObject.orEmpty(),
                )
            }
            return MapStyle(layers, vector?.string("url"), root.string("sprite"))
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

class StyleLayer(
    val id: String,
    val type: String,
    val sourceLayer: String?,
    val minZoom: Double,
    val maxZoom: Double,
    val filter: JsonElement?,
    val paint: Map<String, JsonElement>,
    val layout: Map<String, JsonElement>,
) {
    val visible: Boolean get() = (layout["visibility"] as? JsonPrimitive)?.content != "none"

    fun showsAt(zoom: Double) = visible && zoom >= minZoom && zoom < maxZoom

    fun accepts(feature: TileFeature, zoom: Double): Boolean =
        filter == null || Expressions.truthy(Expressions.evaluate(filter, EvaluationContext(zoom, feature)))

    fun paint(key: String, context: EvaluationContext): Any? = paint[key]?.let { Expressions.evaluate(it, context) }

    fun layout(key: String, context: EvaluationContext): Any? = layout[key]?.let { Expressions.evaluate(it, context) }
}

class EvaluationContext(val zoom: Double, val feature: TileFeature?)

/**
 * The style expressions the OpenFreeMap styles use. Values come out as Kotlin values: Double for
 * numbers, String, Boolean, [Color] where a colour was interpolated, and List for arrays.
 */
object Expressions {
    fun evaluate(element: JsonElement, context: EvaluationContext): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            else -> element.doubleOrNull
        }
        is JsonObject -> null
        is JsonArray -> array(element, context)
    }

    private fun array(element: JsonArray, context: EvaluationContext): Any? {
        val op = (element.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return element.map { evaluate(it, context) }
        val args = element.drop(1)
        fun arg(index: Int) = evaluate(args[index], context)
        val properties = context.feature?.properties.orEmpty()
        return when (op) {
            "get" -> properties[arg(0) as? String]
            "has" -> (arg(0) as? String)?.let(properties::containsKey) ?: false
            "!" -> !truthy(arg(0))
            "==" -> same(arg(0), arg(1))
            "!=" -> !same(arg(0), arg(1))
            ">", "<", ">=", "<=" -> {
                val a = (arg(0) as? Number)?.toDouble()
                val b = (arg(1) as? Number)?.toDouble()
                if (a == null || b == null) false else when (op) {
                    ">" -> a > b
                    "<" -> a < b
                    ">=" -> a >= b
                    else -> a <= b
                }
            }
            "all" -> args.all { truthy(evaluate(it, context)) }
            "any" -> args.any { truthy(evaluate(it, context)) }
            "none" -> args.none { truthy(evaluate(it, context)) }
            "in" -> { val needle = arg(0); args.drop(1).any { same(needle, evaluate(it, context)) } }
            "zoom" -> context.zoom
            "geometry-type" -> context.feature?.geometryTypeName
            "literal" -> evaluate(args[0], context)
            "to-string" -> arg(0)?.let(::text).orEmpty()
            "to-number" -> (arg(0) as? Number)?.toDouble() ?: (arg(0) as? String)?.toDoubleOrNull() ?: 0.0
            "concat" -> args.joinToString("") { evaluate(it, context)?.let(::text).orEmpty() }
            "coalesce" -> args.firstNotNullOfOrNull { evaluate(it, context) }
            "upcase" -> arg(0)?.let(::text)?.uppercase()
            "downcase" -> arg(0)?.let(::text)?.lowercase()
            "case" -> {
                var i = 0
                while (i + 1 < args.size) {
                    if (truthy(evaluate(args[i], context))) return evaluate(args[i + 1], context)
                    i += 2
                }
                if (i < args.size) evaluate(args[i], context) else null
            }
            "match" -> {
                val input = arg(0)
                var i = 1
                while (i + 1 < args.size) {
                    val labels = args[i]
                    val hit = if (labels is JsonArray) labels.any { same(input, evaluate(it, context)) } else same(input, evaluate(labels, context))
                    if (hit) return evaluate(args[i + 1], context)
                    i += 2
                }
                if (i < args.size) evaluate(args[i], context) else null
            }
            "step" -> {
                val input = (arg(0) as? Number)?.toDouble() ?: return arg(1)
                var result = arg(1)
                var i = 2
                while (i + 1 < args.size) {
                    val stop = (evaluate(args[i], context) as? Number)?.toDouble() ?: break
                    if (input < stop) break
                    result = evaluate(args[i + 1], context)
                    i += 2
                }
                result
            }
            "interpolate" -> interpolate(args, context)
            else -> element.map { evaluate(it, context) }
        }
    }

    private fun interpolate(args: List<JsonElement>, context: EvaluationContext): Any? {
        val kind = args[0].jsonArray
        val base = if ((kind[0] as JsonPrimitive).content == "exponential") (kind[1] as JsonPrimitive).doubleOrNull ?: 1.0 else 1.0
        val input = (evaluate(args[1], context) as? Number)?.toDouble() ?: return null
        val stops = args.drop(2).chunked(2).map { (stop, value) -> (evaluate(stop, context) as Number).toDouble() to evaluate(value, context) }
        if (input <= stops.first().first) return stops.first().second
        if (input >= stops.last().first) return stops.last().second
        val upper = stops.indexOfFirst { it.first > input }
        val (z0, v0) = stops[upper - 1]
        val (z1, v1) = stops[upper]
        val t = if (base == 1.0) (input - z0) / (z1 - z0) else (base.pow(input - z0) - 1) / (base.pow(z1 - z0) - 1)
        return blend(v0, v1, t)
    }

    private fun blend(a: Any?, b: Any?, t: Double): Any? = when {
        a is Number && b is Number -> a.toDouble() + (b.toDouble() - a.toDouble()) * t
        color(a) != null && color(b) != null -> {
            val from = color(a)!!
            val to = color(b)!!
            Color(
                red = (from.red + (to.red - from.red) * t).toFloat(),
                green = (from.green + (to.green - from.green) * t).toFloat(),
                blue = (from.blue + (to.blue - from.blue) * t).toFloat(),
                alpha = (from.alpha + (to.alpha - from.alpha) * t).toFloat(),
            )
        }
        a is List<*> && b is List<*> && a.size == b.size -> a.indices.map { blend(a[it], b[it], t) }
        else -> if (t < 0.5) a else b
    }

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        else -> true
    }

    private fun same(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

    private fun text(value: Any): String = when (value) {
        is Double -> if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    /** A CSS colour as styles write them: #rgb, #rrggbb, rgb(), rgba(), hsl(), hsla(). */
    fun color(value: Any?): Color? = when (value) {
        is Color -> value
        is String -> parseColor(value)
        else -> null
    }

    private val colors = java.util.concurrent.ConcurrentHashMap<String, Color>()

    private fun parseColor(text: String): Color? = colors[text] ?: runCatching {
        val value = text.trim()
        val parsed = when {
            value.startsWith("#") -> {
                val hex = value.drop(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
                val argb = hex.toLong(16)
                if (hex.length == 8) Color((argb and 0xFFFFFF00).ushr(8) or ((argb and 0xFF) shl 24)) else Color(0xFF000000 or argb)
            }
            value.startsWith("rgb") -> {
                val parts = value.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
                Color(parts[0].toFloat() / 255f, parts[1].toFloat() / 255f, parts[2].toFloat() / 255f, parts.getOrNull(3)?.toFloat() ?: 1f)
            }
            value.startsWith("hsl") -> {
                val parts = value.substringAfter('(').substringBefore(')').split(',').map { it.trim().removeSuffix("%") }
                Color.hsl(parts[0].toFloat().mod(360f), parts[1].toFloat() / 100f, parts[2].toFloat() / 100f, parts.getOrNull(3)?.toFloat() ?: 1f)
            }
            else -> null
        }
        parsed?.also { colors[text] = it }
    }.getOrNull()
}
