package eu.studio742.imago.feature.library.vectormap

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class VectorMapTest {
    @Test
    fun aTileDecodesIntoLayersWithPropertiesAndGeometry() {
        // A water polygon with a hole, and a city whose point comes twice — as the lowest zooms send it.
        val water = feature(type = 3, tags = intArrayOf(0, 0), geometry = polygon(intArrayOf(0, 0, 100, 0, 100, 100), intArrayOf(20, 20, 40, 40, 20, 40)))
        val city = feature(type = 1, tags = intArrayOf(1, 1, 2, 2), geometry = intArrayOf(command(1, 2), zz(10), zz(20), zz(4096), zz(0)))
        val tile = tile(
            layer("water", features = listOf(water), keys = listOf("class"), values = listOf(string("ocean"))),
            layer("place", features = listOf(city), keys = listOf("class", "name", "rank"), values = listOf(string("ocean"), string("Lisbon"), uint(3))),
        )

        val decoded = VectorTileDecoder.decode(tile)

        val polygon = decoded.layers.getValue("water").features.single()
        assertEquals(GeometryType.POLYGON, polygon.type)
        assertEquals(mapOf("class" to "ocean"), polygon.properties)
        assertEquals(listOf(listOf(0f, 0f, 100f, 0f, 100f, 100f), listOf(20f, 20f, 40f, 40f, 20f, 40f)), polygon.parts.map { it.toList() })
        val place = decoded.layers.getValue("place").features.single()
        assertEquals(mapOf("name" to "Lisbon", "rank" to 3.0), place.properties)
        assertEquals(listOf(10f, 20f, 4106f, 20f), place.parts.single().toList())
        assertEquals("MultiPoint", place.geometryTypeName)
        assertEquals(4096, decoded.layers.getValue("place").extent)
    }

    @Test
    fun theStylesExpressionsAnswerAsMapLibres() {
        val feature = TileFeature(GeometryType.POINT, mapOf("class" to "city", "rank" to 2.0, "name" to "Lisboa", "name_en" to "Lisbon"), listOf(floatArrayOf(1f, 1f)))
        fun eval(json: String, zoom: Double = 5.0) = Expressions.evaluate(Json.parseToJsonElement(json), EvaluationContext(zoom, feature))

        assertEquals(true, eval("""["all", ["match", ["geometry-type"], ["MultiPoint", "Point"], true, false], ["==", ["get", "class"], "city"], ["<=", ["get", "rank"], 3]]"""))
        assertEquals("Lisbon", eval("""["case", ["has", "name:nonlatin"], ["get", "name:nonlatin"], ["coalesce", ["get", "name_en"], ["get", "name"]]]"""))
        assertEquals("center", eval("""["step", ["zoom"], "left", 8, "center"]""", zoom = 9.0))
        assertEquals("left", eval("""["step", ["zoom"], "left", 8, "center"]""", zoom = 7.9))
        assertEquals(12.0, eval("""["interpolate", ["linear"], ["zoom"], 0, 10, 6, 12]""", zoom = 9.0))
        assertEquals(11.0, eval("""["interpolate", ["linear"], ["zoom"], 0, 10, 6, 12]""", zoom = 3.0) as Double, 1e-9)
        // Exponential: most of the change comes late.
        assertTrue((eval("""["interpolate", ["exponential", 2], ["zoom"], 0, 0, 2, 3]""", zoom = 1.0) as Double) < 1.5)
        assertEquals(Color(0.5f, 0.5f, 0.5f), eval("""["interpolate", ["linear"], ["zoom"], 0, "#000000", 10, "rgb(255,255,255)"]"""))
        assertFalse(Expressions.truthy(eval("""["!", ["has", "name"]]""")))
        assertEquals("2 Lisboa", eval("""["concat", ["to-string", ["get", "rank"]], " ", ["get", "name"]]"""))
    }

    @Test
    fun cssColoursAreRead() {
        assertEquals(Color(0xFF0C0C0C), Expressions.color("rgb(12,12,12)"))
        assertEquals(Color(27, 27, 29), Expressions.color("rgb(27 ,27 ,29)"))
        assertEquals(Color(0xFFAABBCC), Expressions.color("#abc"))
        assertEquals(Color(0x80112233), Expressions.color("#11223380"))
        assertEquals(Color.hsl(0f, 0f, 0.85f, 0.53f), Expressions.color("hsla(0,0%,85%,0.53)"))
    }

    // A vector tile, written by hand.

    /** Rings in absolute coordinates; the tile writes each point relative to the one before, across rings. */
    private fun polygon(vararg rings: IntArray): IntArray {
        val out = mutableListOf<Int>()
        var x = 0
        var y = 0
        for (ring in rings) {
            out += command(1, 1); out += zz(ring[0] - x); out += zz(ring[1] - y)
            x = ring[0]; y = ring[1]
            out += command(2, ring.size / 2 - 1)
            for (i in 2 until ring.size step 2) { out += zz(ring[i] - x); out += zz(ring[i + 1] - y); x = ring[i]; y = ring[i + 1] }
            out += command(7, 1)
        }
        return out.toIntArray()
    }

    private fun command(id: Int, count: Int) = (id and 0x7) or (count shl 3)

    private fun zz(value: Int) = (value shl 1) xor (value shr 31)

    private fun feature(type: Int, tags: IntArray, geometry: IntArray) = message {
        packed(2, tags); varint(3, type.toLong()); packed(4, geometry)
    }

    private fun layer(name: String, features: List<ByteArray>, keys: List<String>, values: List<ByteArray>) = message {
        varint(15, 2); bytes(1, name.toByteArray())
        features.forEach { bytes(2, it) }
        keys.forEach { bytes(3, it.toByteArray()) }
        values.forEach { bytes(4, it) }
        varint(5, 4096)
    }

    private fun string(value: String) = message { bytes(1, value.toByteArray()) }

    private fun uint(value: Long) = message { varint(5, value) }

    private fun tile(vararg layers: ByteArray) = message { layers.forEach { bytes(3, it) } }

    private class Writer {
        val out = ByteArrayOutputStream()
        fun raw(value: Long) {
            var v = value
            while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        fun varint(field: Int, value: Long) { raw((field shl 3).toLong()); raw(value) }
        fun bytes(field: Int, value: ByteArray) { raw(((field shl 3) or 2).toLong()); raw(value.size.toLong()); out.write(value) }
        fun packed(field: Int, values: IntArray) {
            val inner = Writer()
            values.forEach { inner.raw(it.toLong() and 0xFFFFFFFFL) }
            bytes(field, inner.out.toByteArray())
        }
    }

    private fun message(block: Writer.() -> Unit) = Writer().apply(block).out.toByteArray()
}
