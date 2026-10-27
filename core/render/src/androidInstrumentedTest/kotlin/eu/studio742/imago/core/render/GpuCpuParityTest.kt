package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Effects
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.WhiteBalance

/**
 * The library's large previews are drawn by the GPU and its thumbnails and exports by the CPU: the two
 * have to give the same photo. A JVM test cannot run the shaders, so this one runs on the device and
 * compares the two renders of the same recipe, one adjustment family at a time, so that a failure says
 * which one drifted.
 *
 * Every comparison is written to the log under [TAG], whether it passes or not: the tolerances were
 * set before a first run and are meant to be tightened from what a device measures.
 */
@RunWith(AndroidJUnit4::class)
class GpuCpuParityTest {
    private val source = scene(WIDTH, HEIGHT)

    private fun recipe(name: String, change: EditRecipe.() -> EditRecipe) =
        name to EditRecipe(assetId = "parity", originalChecksum = "", createdAt = "", updatedAt = name).change()

    private val cases = listOf(
        recipe("tone") { copy(tone = Tone(exposure = 0.6f, contrast = 30f, highlights = -40f, shadows = 40f, whites = 10f, blacks = -10f)) },
        recipe("white balance") { copy(whiteBalance = WhiteBalance(temp = 20f, tint = -10f)) },
        recipe("colour") { copy(presence = Presence(vibrance = 30f, saturation = -20f)) },
        recipe("detail") { copy(presence = Presence(texture = 40f, clarity = 40f, dehaze = 20f)) },
        recipe("curve") {
            copy(toneCurve = ToneCurve(rgb = listOf(CurvePoint(0, 0), CurvePoint(64, 48), CurvePoint(192, 210), CurvePoint(255, 255))))
        },
        recipe("vignette") { copy(effects = Effects(vignetteAmount = -40f)) },
        recipe("rotation and crop") { copy(geometry = Geometry(rotation = 90, cropRect = CropRect(0.1f, 0.1f, 0.8f, 0.7f))) },
        recipe("mirror") { copy(geometry = Geometry(rotation = 270, mirrorH = true)) },
        recipe("straighten") { copy(geometry = Geometry(straighten = 8f)) },
    )

    @Test
    fun theGpuPreviewIsTheCpuRender() {
        val failures = mutableListOf<String>()
        OffscreenRecipeRenderer(source).use { gpu ->
            for ((name, recipe) in cases) {
                val cpu = renderRecipePreview(source, recipe)
                val shown = gpu.render(source, recipe.toRenderParameters(), max(WIDTH, HEIGHT))
                val difference = compare(cpu, shown)
                Log.i(TAG, "$name: $difference")
                if (!difference.withinTolerance) failures += "$name: $difference"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private data class Difference(val widthDelta: Int, val heightDelta: Int, val meanLevels: Double, val farShare: Double) {
        val withinTolerance: Boolean
            get() = widthDelta <= 1 && heightDelta <= 1 && meanLevels <= MEAN_TOLERANCE && farShare <= FAR_SHARE_TOLERANCE

        override fun toString() =
            "size ±$widthDelta×±$heightDelta, mean ${"%.2f".format(meanLevels)} levels, " +
                "${"%.3f".format(farShare * 100)}% of pixels over $FAR_LEVELS"
    }

    /** Over the area both share: the two may round the crop to a pixel apart. */
    private fun compare(cpu: Bitmap, gpu: Bitmap): Difference {
        val width = min(cpu.width, gpu.width)
        val height = min(cpu.height, gpu.height)
        val a = IntArray(width * height).also { cpu.getPixels(it, 0, width, 0, 0, width, height) }
        val b = IntArray(width * height).also { gpu.getPixels(it, 0, width, 0, 0, width, height) }
        var total = 0L
        var far = 0
        for (i in a.indices) {
            var worst = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val delta = abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
                total += delta
                worst = max(worst, delta)
            }
            if (worst > FAR_LEVELS) far++
        }
        return Difference(
            widthDelta = abs(cpu.width - gpu.width),
            heightDelta = abs(cpu.height - gpu.height),
            meanLevels = total.toDouble() / (a.size * 3),
            farShare = far.toDouble() / a.size,
        )
    }

    private companion object {
        const val TAG = "GpuCpuParity"
        const val WIDTH = 1200
        const val HEIGHT = 900
        const val MEAN_TOLERANCE = 2.5
        const val FAR_LEVELS = 16
        const val FAR_SHARE_TOLERANCE = 0.01

        /**
         * Hue across, light down, and a fine checker in one corner: the colour adjustments see every
         * hue, the tone ones every level, and texture and clarity have edges to work on.
         */
        fun scene(width: Int, height: Int): Bitmap {
            val pixels = IntArray(width * height)
            val hsv = FloatArray(3)
            for (y in 0 until height) for (x in 0 until width) {
                pixels[y * width + x] = if (x < width / 4 && y < height / 4) {
                    if ((x / 4 + y / 4) % 2 == 0) Color.rgb(230, 225, 215) else Color.rgb(35, 40, 50)
                } else {
                    hsv[0] = 360f * x / width
                    hsv[1] = 0.25f + 0.6f * (1f - y.toFloat() / height)
                    hsv[2] = 0.1f + 0.85f * (1f - y.toFloat() / height)
                    Color.HSVToColor(hsv)
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
