package eu.studio742.imago.core.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Effects
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.Hsl
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.RadialMask
import eu.studio742.imago.core.model.Tone

/**
 * Splitting the image across cores cannot change a single pixel.
 *
 * The first pass is per pixel and would not change it; the second reads the 3×3 neighbourhood, and
 * that is where a strip could see a row another one already wrote. The sequential processor is the
 * reference: a PixelSurface that is not a PixelBuffer runs in a single strip, exactly like Android's
 * Bitmap.
 */
class ParallelProcessingTest {
    /** The same image, hidden behind the interface so the processor does not split it. */
    private class SingleBand(val buffer: PixelBuffer) : PixelSurface by buffer

    private fun gradient(width: Int, height: Int) = PixelBuffer(width, height).apply {
        for (y in 0 until height) for (x in 0 until width) {
            // Fine texture on purpose: the 3×3 tent only shows where neighbouring rows differ.
            val noise = ((x * 73856093) xor (y * 19349663)) and 0x3F
            pixels[y * width + x] = (0xFF shl 24) or ((x * 255 / width) shl 16) or
                ((y * 255 / height) shl 8) or (90 + noise)
        }
    }

    @Test
    fun `in parallel strips the result is the same pixel for pixel`() {
        val recipe = EditRecipe(
            assetId = "parallel", originalChecksum = "", createdAt = "", updatedAt = "",
            tone = Tone(exposure = 0.4f, contrast = 25f, highlights = -60f, shadows = 45f, whites = 10f, blacks = -15f),
            presence = Presence(texture = 40f, clarity = 35f, dehaze = 30f, vibrance = 20f, saturation = -10f),
            hsl = Hsl(orange = HslBand(hue = 10f, saturation = 20f, luminance = -15f)),
            effects = Effects(vignetteAmount = -30f, grainAmount = 25f),
            geometry = Geometry(rotation = 90),
            masks = listOf(
                LocalMask(
                    id = "radial",
                    components = listOf(MaskComponent(MaskShape.RADIAL, radial = RadialMask(x = 0.4f, y = 0.6f))),
                    adjustments = LocalAdjustments(exposure = -0.8f, texture = -50f, clarity = 60f, dehaze = -20f),
                ),
            ),
        )
        val parameters = recipe.toRenderParameters()
        val source = gradient(640, 480)
        val geometry = parameters.frameGeometry(source.width, source.height)
        assertTrue("the test image has to be split", rowsRunInParallel(source))

        val sequential = BitmapPhotoProcessor.renderInPlace(SingleBand(source.copy()), parameters, geometry).buffer
        val parallel = BitmapPhotoProcessor.renderInPlace(source.copy(), parameters, geometry)

        assertArrayEquals(sequential.pixels, parallel.pixels)
    }
}
