package eu.studio742.imago.core.model

import java.util.Base64
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedWeightsTest {
    private val smooth = ByteArray(96 * 144) { i -> ((i % 96) * 255 / 95).toByte() }

    @Test
    fun pngComesBackByteForByte() {
        val noisy = ByteArray(37 * 23).also { Random(7).nextBytes(it) }
        for ((width, height, pixels) in listOf(Triple(96, 144, smooth), Triple(37, 23, noisy), Triple(1, 1, byteArrayOf(-1)))) {
            val (w, h, back) = checkNotNull(GrayPng.decode(GrayPng.encode(width, height, pixels)))
            assertEquals(width, w)
            assertEquals(height, h)
            assertArrayEquals(pixels, back)
        }
    }

    @Test
    fun aSmoothMaskIsSmall() {
        assertTrue(GrayPng.encode(96, 144, smooth).size < 2_000)
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(GrayPng.decode(ByteArray(0)))
        assertNull(GrayPng.decode("not a png".toByteArray()))
        val png = GrayPng.encode(96, 144, smooth)
        assertNull(GrayPng.decode(png.copyOf(png.size / 2)))
        // An RGB header: the only kind read is the 8-bit grey it writes.
        val rgb = png.copyOf().also { it[8 + 8 + 9] = 2 }
        assertNull(GrayPng.decode(rgb))
    }

    @Test
    fun weightsKeepTheirSizeAndRefuseAnotherOne() {
        val weights = GeneratedWeights.of("u2netp", 1, 96, 144, smooth)
        assertArrayEquals(smooth, weights.pixels())
        assertNull(weights.copy(width = 95).pixels())
        assertNull(weights.copy(png = Base64.getEncoder().encodeToString("x".toByteArray())).pixels())
        assertNull(weights.copy(png = "%%%").pixels())
    }

    @Test
    fun weightsTravelInTheRecipesJson() {
        val component = MaskComponent(shape = MaskShape.SKY, generated = GeneratedWeights.of("sky", 1, 96, 144, smooth))
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(MaskComponent.serializer(), json.encodeToString(MaskComponent.serializer(), component))
        assertEquals(component, back)
        assertArrayEquals(smooth, back.generated?.pixels())
    }

    @Test
    fun leavingThePhotoDropsTheWeightsAndKeepsTheShapes() {
        val weights = GeneratedWeights.of("u2netp", 1, 96, 144, smooth)
        val masks = listOf(
            LocalMask(id = "a", components = listOf(MaskComponent(shape = MaskShape.SUBJECT, generated = weights), MaskComponent(shape = MaskShape.BRUSH))),
            LocalMask(id = "b", components = listOf(MaskComponent(shape = MaskShape.LINEAR, linear = LinearMask()))),
        )
        val portable = masks.withoutGeneratedWeights()
        assertEquals(listOf(MaskShape.SUBJECT, MaskShape.BRUSH), portable[0].components.map { it.shape })
        assertNull(portable[0].components[0].generated)
        assertEquals(masks[1], portable[1])
        // Without anything generated, the list itself comes back.
        assertSame(portable, portable.withoutGeneratedWeights())
    }
}
