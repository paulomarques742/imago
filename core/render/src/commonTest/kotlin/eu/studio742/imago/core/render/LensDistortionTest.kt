package eu.studio742.imago.core.render

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.ImageSize
import eu.studio742.imago.core.model.Perspective
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * The Distortion slider, through every path that reads it: the lens model and its inverse, the frame
 * in both directions, the cover of a constrained crop, and the Android CPU's sampling.
 */
class LensDistortionTest {
    private val radiusSquared = LensDistortion.radiusSquared(1.5)

    @Test fun correctingUndoesSampling() {
        val sampled = DoubleArray(2)
        val back = DoubleArray(2)
        for (slider in listOf(-100f, -40f, 25f, 100f)) {
            val k = LensDistortion.coefficient(slider)
            for ((x, y) in listOf(0.0 to 0.0, 0.3 to -0.2, -0.75 to 0.5, 0.7 to 0.45, 0.01 to 0.0)) {
                assertTrue(LensDistortion.sample(x, y, k, radiusSquared, sampled))
                assertTrue(LensDistortion.correct(sampled[0], sampled[1], k, radiusSquared, back))
                assertEquals("x, slider $slider", x, back[0], 1e-9)
                assertEquals("y, slider $slider", y, back[1], 1e-9)
            }
        }
    }

    @Test fun positiveTakesBarrelOutAndTheCentreStays() {
        val out = DoubleArray(2)
        // Taking barrel out samples the edges nearer the centre, which spreads them out.
        LensDistortion.sample(0.7, 0.4, LensDistortion.coefficient(60f), radiusSquared, out)
        assertTrue(abs(out[0]) < 0.7 && abs(out[1]) < 0.4)
        LensDistortion.sample(0.0, 0.0, LensDistortion.coefficient(60f), radiusSquared, out)
        assertArrayEquals(doubleArrayOf(0.0, 0.0), out, 0.0)
    }

    /** Past the radius where the barrel correction folds back, nothing is sampled — never inside the photo. */
    @Test fun theFoldIsBeyondThePhoto() {
        val k = LensDistortion.coefficient(100f)
        val out = DoubleArray(2)
        assertTrue("the corner", LensDistortion.sample(0.75, 0.5, k, radiusSquared, out))
        assertFalse("far beyond it", LensDistortion.sample(1.5, 1.0, k, radiusSquared, out))
    }

    /**
     * A straight line the lens bent — barrel, as an ultra-wide draws it — comes out straight with the
     * slider that matches the lens, and only with it.
     */
    @Test fun aLineTheLensBentComesOutStraight() {
        val lensK = -0.1
        val aspect = 1.5
        val radius2 = LensDistortion.radiusSquared(aspect)
        // A horizontal line near the top, as the lens drew it, in the original image's coordinates.
        val drawn = (0..20).map { step ->
            val x = -0.7 + step * 0.07
            val out = DoubleArray(2)
            LensDistortion.sample(x, -0.4, lensK, radius2, out)
            (out[0] / aspect + 0.5).toFloat() to (out[1] + 0.5).toFloat()
        }
        fun bow(slider: Float): Float {
            val geometry = FrameGeometry(4500, 3000, perspective = PerspectiveParameters(distortion = slider, constrainCrop = false))
            val point = FloatArray(2)
            val ys = drawn.map { (u, v) -> geometry.framedFromImage(u, v, point); point[1] }
            return ys.max() - ys.min()
        }
        val matched = (-lensK / LensDistortion.MAX_COEFFICIENT * 100).toFloat()
        assertTrue("barrel is taken out on the positive side", matched > 0f)
        assertTrue("left as drawn it bows: ${bow(0f)}", bow(0f) > 0.01f)
        assertTrue("with the slider at $matched it is straight: ${bow(matched)}", bow(matched) < 1e-4f)
    }

    private val geometries = buildList {
        for (turns in 0..3) {
            for (perspective in listOf(
                PerspectiveParameters(distortion = 70f),
                PerspectiveParameters(distortion = -100f),
                PerspectiveParameters(distortion = -45f, vertical = -30f, horizontal = 15f, offsetX = 20f),
                PerspectiveParameters(distortion = 100f, aspect = 20f, scale = 10f),
                PerspectiveParameters(distortion = -60f, scale = -30f, constrainCrop = false),
            )) {
                add(FrameGeometry(4000, 3000, cropX = 0.1f, cropY = 0.05f, cropWidth = 0.8f, cropHeight = 0.7f, straighten = 5f, quarterTurns = turns, mirrorH = turns == 2, perspective = perspective))
            }
        }
    }

    /** What masks and guides rely on: from the frame to the image and back lands where it started. */
    @Test fun theTwoDirectionsCancelOutWithADistortion() {
        val there = FloatArray(2)
        val back = FloatArray(2)
        for (geometry in geometries) {
            for (u in listOf(0f, 0.2f, 0.5f, 0.9f, 1f)) {
                for (v in listOf(0f, 0.35f, 0.5f, 1f)) {
                    geometry.imageFromFramed(u, v, there)
                    if (there[0].isNaN()) continue
                    geometry.framedFromImage(there[0], there[1], back)
                    assertEquals("u on $geometry", u, back[0], 1e-4f)
                    assertEquals("v on $geometry", v, back[1], 1e-4f)
                }
            }
        }
    }

    /**
     * With the crop constrained, nothing past the photo shows — not even where taking pincushion out
     * bows the photo's sides. Taking pincushion out, the frame also touches the photo somewhere, so the
     * cover is no bigger than it has to be; taking barrel out the photo spreads past its own rectangle,
     * the cover is measured against the rectangle, and so it may crop a little more than it must.
     */
    @Test fun theConstrainedFrameStaysOnThePhoto() {
        val point = FloatArray(2)
        for (slider in listOf(-100f, -50f, 30f, 100f)) {
            for (perspective in listOf(
                PerspectiveParameters(distortion = slider),
                PerspectiveParameters(distortion = slider, vertical = -40f, horizontal = 20f),
            )) {
                val geometry = FrameGeometry(4500, 3000, straighten = 4f, perspective = perspective)
                var closest = Float.MAX_VALUE
                for (i in 0..20) {
                    for ((u, v) in listOf(i / 20f to 0f, i / 20f to 1f, 0f to i / 20f, 1f to i / 20f)) {
                        geometry.imageFromFramed(u, v, point)
                        val case = "$perspective at ($u, $v): (${point[0]}, ${point[1]})"
                        assertTrue(case, point[0] in -1e-4f..1.0001f && point[1] in -1e-4f..1.0001f)
                        closest = minOf(closest, point[0], point[1], 1f - point[0], 1f - point[1])
                    }
                }
                if (slider < 0f) assertEquals("$perspective", 0f, closest, 3e-3f)
            }
        }
    }

    /** The loupe's matrix is exact where it looks and within a hair around it. */
    @Test fun theLoupesMatrixIsExactWhereItLooks() {
        val geometry = FrameGeometry(4000, 3000, perspective = PerspectiveParameters(distortion = -80f, vertical = -20f))
        val matrix = geometry.framedFromImagePixels(0.8f, 0.2f)
        val exact = FloatArray(2)
        val drawn = FloatArray(2)
        for ((u, v) in listOf(0.8f to 0.2f, 0.81f to 0.205f, 0.79f to 0.19f)) {
            geometry.framedFromImage(u, v, exact)
            matrix.map(u * 4000, v * 3000, drawn)
            val error = max(abs(exact[0] - drawn[0]), abs(exact[1] - drawn[1]))
            assertTrue("($u, $v) off by $error", error < if (u == 0.8f) 1e-5f else 2e-4f)
        }
    }

    /** The Android CPU path, band by band, samples as the frame does in one go. */
    @Test fun samplingInBandsIsSamplingAtOnce() {
        val source = PixelBuffer(60, 40, IntArray(60 * 40) { (0xFF shl 24) or (it * 2654435761L).toInt().and(0xFFFFFF) })
        val frame = FrameGeometry(60, 40, straighten = 3f, perspective = PerspectiveParameters(distortion = 50f, vertical = 10f))
        val whole = IntArray(60 * 40)
        RecipePixels.sampleFrameRows(source, frame, 0, 40, whole)
        val banded = IntArray(60 * 40)
        val band = IntArray(60 * 7)
        var row = 0
        while (row < 40) {
            val until = minOf(row + 7, 40)
            RecipePixels.sampleFrameRows(source, frame, row, until, band)
            band.copyInto(banded, row * 60, 0, (until - row) * 60)
            row = until
        }
        assertArrayEquals(whole, banded)
    }

    @Test fun onlyProcess14DrawsTheDistortion() {
        fun recipe(version: Int) = EditRecipe(
            assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "", processVersion = version,
            geometry = Geometry(perspective = Perspective(distortion = 40f, vertical = 10f)),
        )
        assertEquals(0f, recipe(13).toRenderParameters().perspective.distortion)
        assertEquals(10f, recipe(13).toRenderParameters().perspective.vertical)
        assertEquals(40f, recipe(14).toRenderParameters().perspective.distortion)
    }

    @Test fun aDistortionIsNothingImmichCanShow() {
        val recipe = EditRecipe(
            assetId = "a", originalChecksum = "", createdAt = "", updatedAt = "", processVersion = 14,
            geometry = Geometry(rotation = 90, perspective = Perspective(distortion = 30f)),
        )
        assertTrue(recipe.immichEdits(ImageSize(4000, 3000)).isEmpty())
    }
}
