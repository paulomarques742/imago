package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.GENERATED_MASKS_PROCESS_VERSION
import eu.studio742.imago.core.model.GeneratedWeights
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedMasksTest {
    private val sky = 0xFF5B8FD6.toInt()
    private val grass = 0xFF4C8A3A.toInt()

    /** Sky on the left, grass on the right: the edge is at x = 0.5. */
    private val photo = PixelBuffer(200, 100).apply {
        for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = if (x < width / 2) sky else grass
    }

    /**
     * What a model at low resolution gives for it, as the sky model does at 1/8 of its input: sure on either
     * side and unsure across a pixel and a half around the edge. The filter turns that doubt into the
     * photo's own edge; a model that is sure and wrong it cannot correct, and is not asked to.
     */
    private val coarse: GeneratedWeights = run {
        val width = 96
        val height = 48
        val pixels = ByteArray(width * height) { index ->
            val x = (index % width + 0.5f) / width
            (((0.5f - x) / 0.016f + 0.5f).coerceIn(0f, 1f) * 255).toInt().toByte()
        }
        GeneratedWeights.of("test-sky", 1, width, height, pixels)
    }

    private fun recipe(shape: MaskShape, weights: GeneratedWeights?, processVersion: Int = GENERATED_MASKS_PROCESS_VERSION) = EditRecipe(
        assetId = "a",
        originalChecksum = "",
        createdAt = "",
        updatedAt = "",
        processVersion = processVersion,
        masks = listOf(
            LocalMask(id = "m", components = listOf(MaskComponent(shape = shape, generated = weights)), adjustments = LocalAdjustments(exposure = -1f)),
        ),
    )

    private fun rasterOf(shape: MaskShape) = checkNotNull(BrushRasters.of(recipe(shape, coarse).toRenderParameters(), photo)[0])

    /** The mean distance from the real edge, along the middle row near it: one on the sky, zero on the grass. */
    private fun errorNearTheEdge(weightAt: (Float) -> Float): Float =
        (0 until 1000).map { i ->
            val x = 0.4f + 0.2f * (i + 0.5f) / 1000
            abs(weightAt(x) - if (x < 0.5f) 1f else 0f)
        }.average().toFloat()

    @Test
    fun refinementMovesTheEdgeOntoThePhotosEdge() {
        val raster = rasterOf(MaskShape.SKY)
        val refined = errorNearTheEdge { x -> raster.sample(x, 0.5f) }
        val pixels = checkNotNull(coarse.pixels())
        val row = coarse.height / 2 * coarse.width
        val unrefined = errorNearTheEdge { x ->
            val position = (x * coarse.width - 0.5f).coerceIn(0f, coarse.width - 1f)
            val left = position.toInt().coerceAtMost(coarse.width - 2)
            val t = position - left
            ((pixels[row + left].toInt() and 0xff) * (1 - t) + (pixels[row + left + 1].toInt() and 0xff) * t) / 255f
        }
        // A guided filter aligns the model's doubt with the photo's edge; it does not decide for the model.
        // Measured: the error near the edge falls by about 40 % and the 10-90 % transition halves.
        assertTrue("refined $refined, unrefined $unrefined", refined < unrefined * 0.7f)
        assertTrue(raster.sample(0.495f, 0.5f) > 0.88f)
        assertTrue(raster.sample(0.505f, 0.5f) < 0.12f)
        // Well inside each side the mask is what the model was sure of.
        assertEquals(1f, raster.sample(0.2f, 0.5f), 0.02f)
        assertEquals(0f, raster.sample(0.8f, 0.5f), 0.02f)
    }

    @Test
    fun backgroundIsTheSameWeightsInverted() {
        val subject = rasterOf(MaskShape.SUBJECT)
        val background = rasterOf(MaskShape.BACKGROUND)
        for (x in listOf(0.1f, 0.45f, 0.5f, 0.55f, 0.9f)) {
            assertEquals(1f, subject.sample(x, 0.5f) + background.sample(x, 0.5f), 1e-3f)
        }
    }

    @Test
    fun aShapeNotGeneratedYetWeighsNothingAndTakesNoLayer() {
        val parameters = recipe(MaskShape.SKY, weights = null).toRenderParameters()
        val component = parameters.masks.single().components.single()
        assertFalse(component.hasRasterContent)
        assertEquals(-1, component.brushIndex)
    }

    @Test
    fun anOlderProcessDoesNotDrawGeneratedShapes() {
        val parameters = recipe(MaskShape.SKY, coarse, processVersion = GENERATED_MASKS_PROCESS_VERSION - 1).toRenderParameters()
        assertTrue(parameters.masks.isEmpty())
    }

    @Test
    fun weightsThatDoNotDecodeLeaveTheLayerEmpty() {
        val broken = coarse.copy(png = "bm90IGEgcG5n")
        val raster = checkNotNull(BrushRasters.of(recipe(MaskShape.SKY, broken).toRenderParameters(), photo)[0])
        assertEquals(0f, raster.sample(0.2f, 0.5f), 0f)
    }
}
