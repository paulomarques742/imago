package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.studio742.imago.core.model.CURRENT_RAW_ENGINE
import eu.studio742.imago.core.model.CURRENT_RAW_RENDERING
import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RawDevelopment
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long opening and exporting a RAW take on a device, phase by phase, written to the log under
 * [TAG]. It needs real originals, which do not belong in the repository: pushed to the app's external
 * files folder, they are measured; without them the test is skipped.
 */
@RunWith(AndroidJUnit4::class)
class RawPipelineBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun report(line: String) { Log.i(TAG, line) }

    private inline fun <T> phase(label: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { report("  $label ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    private fun recipe() = EditRecipe(
        assetId = "benchmark", originalChecksum = "", createdAt = "", updatedAt = "",
        detail = Detail(sharpenAmount = 40f),
        rawDevelopment = RawDevelopment(CURRENT_RAW_ENGINE, CURRENT_RAW_RENDERING, 0.5f, lensCorrection = true),
    )

    @Test fun phases() = runBlocking {
        val files = context.getExternalFilesDir(null)?.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("raf", "arw", "dng", "cr3", "nef") }
        assumeTrue("No originals to measure.", files.isNotEmpty())
        val arguments = InstrumentationRegistry.getArguments()
        // `-e serial true` measures the render on one thread, as it was.
        parallelRows = arguments.getString("serial") != "true"
        val rounds = arguments.getString("rounds")?.toInt() ?: 2
        try {
            for (file in files.sortedBy { it.name }) repeat(rounds) { round -> withContext(Dispatchers.Default) { measure(file, round) } }
        } finally {
            parallelRows = true
        }
    }

    private suspend fun measure(file: File, round: Int) {
        val parameters = recipe().toRenderParameters()
        report("${file.name} round $round: opening in the editor")
        val preview = phase("develop 2048") { requireNotNull(decodeRawEditorPreview(file, 2048, baselineExposure = true)) }.pixels
        phase("match the camera's exposure") { CameraExposureMatch.of(file, preview) }
        phase("bitmap") { preview.toPixelBuffer().toPlatformBitmap().recycle() }
        phase("noise profile") { RawNoiseReduction.profile(preview) }
        val clean = phase("noise reduction") { RawNoiseReduction.apply(preview, parameters, consume = false) }
        phase("rest of the CPU render") { RecipePixels.renderFloat(clean.copy(), parameters.copy(noiseLuminance = 0f, noiseColor = 0f), consumeSource = true) }
        report("${file.name} round $round: exporting")
        val full = phase("develop full") { requireNotNull(decodeRawPhoto(file, baselineExposure = true)) }
        report("  size ${full.width}x${full.height}")
        phase("noise profile") { RawNoiseReduction.profile(full) }
        phase("noise reduction") { RawNoiseReduction.apply(full, parameters, consume = true) }
        val rendered = phase("rest of the render") { RecipePixels.renderFloat(full, parameters.copy(noiseLuminance = 0f, noiseColor = 0f), consumeSource = true) }
        val pixels = phase("to 8 bits") { rendered.toPixelBuffer() }
        rendered.rgb.release()
        val bitmap = phase("bitmap") { pixels.toPlatformBitmap() }
        phase("JPEG") { ByteArrayOutputStream(16 shl 20).also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.size() }
        bitmap.recycle()
    }

    private companion object {
        const val TAG = "imago-bench"
    }
}
