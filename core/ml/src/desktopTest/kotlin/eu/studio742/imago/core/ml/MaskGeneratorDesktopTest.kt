package eu.studio742.imago.core.ml

import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Our ONNX Runtime build against masks the official runtime produced from the same input
 * (`src/testFixtures/resources/masks`). Runs when `-Pimago.ml.models=<folder>` holds the model files.
 */
class MaskGeneratorDesktopTest {
    private val models = System.getProperty("imago.ml.models")?.let(::File)
    private val photos = File(checkNotNull(System.getProperty("imago.ml.photos")))

    @Test
    fun runtimeLoads() = assertTrue(MaskGenerator.isAvailable)

    @Test
    fun masksMatchTheOfficialRuntime() {
        assumeTrue("Set imago.ml.models to run the models", models != null)
        for (photo in PHOTOS) for (model in MaskModel.entries) {
            val image = ImageIO.read(File(photos, "$photo.jpg"))
            val rgb = RgbImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
            val mask = MaskGenerator.generate(model, File(models, model.fileName).path, rgb)
            val expected = ImageIO.read(javaClass.getResourceAsStream("/masks/${photo}_${model.kind}.png"))
            assertEquals(expected.width, mask.width)
            assertEquals(expected.height, mask.height)
            var sum = 0.0
            for (y in 0 until mask.height) for (x in 0 until mask.width) {
                sum += abs(mask.weight(x, y) - (expected.raster.getSample(x, y, 0) / 255.0))
            }
            val mean = sum / (mask.width * mask.height)
            assertTrue("$photo ${model.kind}: mean difference $mean", mean <= MAX_MEAN_DIFFERENCE)
        }
    }
}

internal val MaskModel.kind get() = name.lowercase()
private val PHOTOS = listOf("recipe_sample_01", "recipe_sample_06", "recipe_sample_08")
private const val MAX_MEAN_DIFFERENCE = 0.003
