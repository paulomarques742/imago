package eu.studio742.imago.core.ml

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Our ONNX Runtime build on the device against masks the official runtime produced from the same input.
 * The model files come into the test APK's assets from `-Pimago.ml.models=<folder>`.
 */
@RunWith(AndroidJUnit4::class)
class MaskGeneratorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets

    @Test
    fun runtimeLoads() = assertTrue(MaskGenerator.isAvailable)

    @Test
    fun masksMatchTheOfficialRuntime() {
        assumeTrue("Set imago.ml.models to run the models", InstrumentationRegistry.getArguments().getString("modelsAvailable") == "true")
        for (model in MaskModel.entries) {
            // The runtime reads the model from a path, and assets are not files.
            val modelFile = File(instrumentation.targetContext.cacheDir, model.fileName)
            assets.open(model.fileName).use { input -> modelFile.outputStream().use { input.copyTo(it) } }
            for (photo in PHOTOS) {
                val bitmap = assets.open("$photo.jpg").use { BitmapFactory.decodeStream(it) }
                val argb = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val start = System.nanoTime()
                val mask = MaskGenerator.generate(model, modelFile.path, RgbImage(bitmap.width, bitmap.height, argb))
                val millis = (System.nanoTime() - start) / 1_000_000
                val expected = assets.open("masks/${photo}_${model.name.lowercase()}.png").use { BitmapFactory.decodeStream(it) }
                assertEquals(expected.width, mask.width)
                assertEquals(expected.height, mask.height)
                var sum = 0.0
                for (y in 0 until mask.height) for (x in 0 until mask.width) {
                    sum += abs(mask.weight(x, y) - (expected.getPixel(x, y) and 0xff) / 255.0)
                }
                val mean = sum / (mask.width * mask.height)
                Log.i("MaskGeneratorDeviceTest", "$photo ${model.name}: ${millis}ms, mean difference $mean")
                assertTrue("$photo ${model.name}: mean difference $mean", mean <= MAX_MEAN_DIFFERENCE)
            }
            modelFile.delete()
        }
    }
}

private val PHOTOS = listOf("recipe_sample_01", "recipe_sample_06", "recipe_sample_08")
private const val MAX_MEAN_DIFFERENCE = 0.003
