package eu.studio742.imago.core.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelInputTest {
    @Test
    fun boxWeightsAverageWholeAndPartialPixels() {
        val halving = boxWeights(4, 2)
        assertEquals(0, halving[0].first)
        assertArrayEquals(doubleArrayOf(0.5, 0.5), halving[0].weights, 1e-12)
        assertEquals(2, halving[1].first)

        // Three pixels into two: the middle one is split between both outputs.
        val thirds = boxWeights(3, 2)
        assertArrayEquals(doubleArrayOf(2.0 / 3, 1.0 / 3), thirds[0].weights, 1e-12)
        assertEquals(1, thirds[1].first)
        assertArrayEquals(doubleArrayOf(1.0 / 3, 2.0 / 3), thirds[1].weights, 1e-12)
    }

    @Test
    fun boxWeightsRepeatPixelsWhenUpscaling() {
        val doubling = boxWeights(2, 4)
        assertEquals(listOf(0, 0, 1, 1), doubling.map { it.first })
        doubling.forEach { assertArrayEquals(doubleArrayOf(1.0), it.weights, 1e-12) }
    }

    @Test
    fun inputIsPlanarAndNormalisedPerChannel() {
        val image = RgbImage(3, 2, IntArray(6) { 0xff8040c0.toInt() })
        val input = modelInput(image, 2, 2)
        val expected = floatArrayOf(
            ((0x80 / 255.0 - 0.485) / 0.229).toFloat(),
            ((0x40 / 255.0 - 0.456) / 0.224).toFloat(),
            ((0xc0 / 255.0 - 0.406) / 0.225).toFloat(),
        )
        for (c in 0..2) for (i in 0..3) assertEquals(expected[c], input[c * 4 + i], 1e-6f)
    }

    @Test
    fun downscaleAveragesTheSourceArea() {
        // A 2 × 2 block of black and white averages to mid grey in one output pixel.
        val image = RgbImage(2, 2, intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffffffff.toInt(), 0xff000000.toInt()))
        val input = modelInput(image, 1, 1)
        assertEquals(((0.5 - 0.485) / 0.229).toFloat(), input[0], 1e-6f)
    }

    @Test
    fun skyInputKeepsTheAspectAtMultiplesOf32() {
        assertEquals(1152 to 768, MaskModel.SKY.inputSize(1280, 853))
        assertEquals(768 to 1152, MaskModel.SKY.inputSize(853, 1280))
        assertEquals(320 to 320, MaskModel.SUBJECT.inputSize(1280, 853))
    }

    @Test
    fun subjectSaliencyIsStretchedAndSkyIsNot() {
        val subject = generatedMask(MaskModel.SUBJECT, 2, 1, floatArrayOf(0.2f, 0.6f))
        assertEquals(0f, subject.weight(0, 0))
        assertEquals(1f, subject.weight(1, 0))
        val sky = generatedMask(MaskModel.SKY, 2, 1, floatArrayOf(0.2f, 0.6f))
        assertEquals(51, sky.weights[0].toInt() and 0xff)
        assertEquals(153, sky.weights[1].toInt() and 0xff)
    }
}
