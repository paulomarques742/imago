package eu.studio742.imago.feature.library.vectormap

/**
 * A Mapbox Vector Tile, decoded: the layers OpenMapTiles fills (water, roads, places…), each with
 * its features in tile coordinates, 0 to [TileLayer.extent] across.
 */
class VectorTile(val layers: Map<String, TileLayer>)

class TileLayer(val name: String, val extent: Int, val features: List<TileFeature>)

/**
 * One feature. [parts] holds its geometry as flat x, y pairs: one array per point group, line or
 * ring. A polygon's rings come in the tile's order, outer rings clockwise and holes the other way.
 */
class TileFeature(val type: GeometryType, val properties: Map<String, Any>, val parts: List<FloatArray>) {
    /** What `["geometry-type"]` answers in a style: the multi forms when there is more than one. */
    val geometryTypeName: String by lazy {
        when (type) {
            GeometryType.POINT -> if (parts.sumOf { it.size / 2 } > 1) "MultiPoint" else "Point"
            GeometryType.LINESTRING -> if (parts.size > 1) "MultiLineString" else "LineString"
            GeometryType.POLYGON -> if (parts.count { signedArea(it) > 0 } > 1) "MultiPolygon" else "Polygon"
            GeometryType.UNKNOWN -> "Unknown"
        }
    }
}

enum class GeometryType { UNKNOWN, POINT, LINESTRING, POLYGON }

/** Twice the area, positive for a ring that turns clockwise on the screen, where y grows down. */
internal fun signedArea(ring: FloatArray): Double {
    var sum = 0.0
    var i = 0
    while (i < ring.size) {
        val j = (i + 2) % ring.size
        sum += ring[i].toDouble() * ring[j + 1] - ring[j].toDouble() * ring[i + 1]
        i += 2
    }
    return sum
}

/** The protobuf of the vector tile specification 2.1, read by hand: it is four messages. */
object VectorTileDecoder {
    fun decode(bytes: ByteArray): VectorTile {
        val layers = mutableMapOf<String, TileLayer>()
        val reader = ProtoReader(bytes, 0, bytes.size)
        while (reader.hasMore()) {
            val (field, wire) = reader.tag()
            if (field == 3 && wire == 2) {
                val (start, end) = reader.lengthDelimited()
                val layer = layer(ProtoReader(bytes, start, end))
                layers[layer.name] = layer
            } else {
                reader.skip(wire)
            }
        }
        return VectorTile(layers)
    }

    private fun layer(reader: ProtoReader): TileLayer {
        var name = ""
        var extent = 4096
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any>()
        val rawFeatures = mutableListOf<Pair<Int, Int>>()
        while (reader.hasMore()) {
            val (field, wire) = reader.tag()
            when {
                field == 1 && wire == 2 -> name = reader.string()
                field == 2 && wire == 2 -> rawFeatures += reader.lengthDelimited()
                field == 3 && wire == 2 -> keys += reader.string()
                field == 4 && wire == 2 -> {
                    val (start, end) = reader.lengthDelimited()
                    values += value(ProtoReader(reader.bytes, start, end))
                }
                field == 5 && wire == 0 -> extent = reader.varint().toInt()
                else -> reader.skip(wire)
            }
        }
        val features = rawFeatures.map { (start, end) -> feature(ProtoReader(reader.bytes, start, end), keys, values) }
        return TileLayer(name, extent, features)
    }

    private fun value(reader: ProtoReader): Any {
        var result: Any = ""
        while (reader.hasMore()) {
            val (field, wire) = reader.tag()
            result = when (field) {
                1 -> reader.string()
                2 -> java.lang.Float.intBitsToFloat(reader.fixed32()).toDouble()
                3 -> java.lang.Double.longBitsToDouble(reader.fixed64())
                4 -> reader.varint().toDouble()
                5 -> reader.varint().toDouble()
                6 -> zigZag(reader.varint()).toDouble()
                7 -> reader.varint() != 0L
                else -> { reader.skip(wire); result }
            }
        }
        return result
    }

    private fun feature(reader: ProtoReader, keys: List<String>, values: List<Any>): TileFeature {
        var type = GeometryType.UNKNOWN
        var tags = IntArray(0)
        var geometry = IntArray(0)
        while (reader.hasMore()) {
            val (field, wire) = reader.tag()
            when {
                field == 2 && wire == 2 -> tags = reader.packedVarints()
                field == 3 && wire == 0 -> type = GeometryType.entries.getOrElse(reader.varint().toInt()) { GeometryType.UNKNOWN }
                field == 4 && wire == 2 -> geometry = reader.packedVarints()
                else -> reader.skip(wire)
            }
        }
        val properties = HashMap<String, Any>(tags.size / 2)
        var t = 0
        while (t + 1 < tags.size) {
            val key = keys.getOrNull(tags[t])
            val value = values.getOrNull(tags[t + 1])
            if (key != null && value != null) properties[key] = value
            t += 2
        }
        return TileFeature(type, properties, geometryParts(geometry, type))
    }

    /** MoveTo starts a part, LineTo continues it, ClosePath ends a ring; coordinates are zig-zag deltas. */
    private fun geometryParts(commands: IntArray, type: GeometryType): List<FloatArray> {
        val parts = mutableListOf<FloatArray>()
        var current = FloatArrayBuilder()
        var x = 0
        var y = 0
        var i = 0
        while (i < commands.size) {
            val command = commands[i] and 0x7
            val count = commands[i] ushr 3
            i++
            when (command) {
                1 -> repeat(count) {
                    if (type != GeometryType.POINT && current.size > 0) { parts += current.build(); current = FloatArrayBuilder() }
                    x += zigZag(commands[i].toLong()).toInt(); y += zigZag(commands[i + 1].toLong()).toInt(); i += 2
                    current.add(x.toFloat(), y.toFloat())
                }
                2 -> repeat(count) {
                    x += zigZag(commands[i].toLong()).toInt(); y += zigZag(commands[i + 1].toLong()).toInt(); i += 2
                    current.add(x.toFloat(), y.toFloat())
                }
                7 -> { parts += current.build(); current = FloatArrayBuilder() }
                else -> return parts
            }
        }
        if (current.size > 0) parts += current.build()
        return parts
    }

    private fun zigZag(value: Long): Long = (value ushr 1) xor -(value and 1)
}

private class FloatArrayBuilder {
    private var data = FloatArray(16)
    var size = 0
        private set

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }

    fun build(): FloatArray = data.copyOf(size)
}

internal class ProtoReader(val bytes: ByteArray, private var position: Int, private val end: Int) {
    fun hasMore() = position < end

    fun tag(): Pair<Int, Int> {
        val key = varint().toInt()
        return (key ushr 3) to (key and 0x7)
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    fun fixed32(): Int {
        var result = 0
        for (k in 0 until 4) result = result or ((bytes[position + k].toInt() and 0xFF) shl (8 * k))
        position += 4
        return result
    }

    fun fixed64(): Long {
        var result = 0L
        for (k in 0 until 8) result = result or ((bytes[position + k].toLong() and 0xFF) shl (8 * k))
        position += 8
        return result
    }

    fun lengthDelimited(): Pair<Int, Int> {
        val length = varint().toInt()
        val start = position
        position += length
        return start to position
    }

    fun string(): String {
        val (start, stop) = lengthDelimited()
        return String(bytes, start, stop - start, Charsets.UTF_8)
    }

    fun packedVarints(): IntArray {
        val (start, stop) = lengthDelimited()
        val inner = ProtoReader(bytes, start, stop)
        val out = ArrayList<Int>()
        while (inner.hasMore()) out += inner.varint().toInt()
        return out.toIntArray()
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> position += 8
            2 -> lengthDelimited()
            5 -> position += 4
            else -> error("Unknown protobuf wire type $wire")
        }
    }
}
