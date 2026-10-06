package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoEffectsTest {
    // --- clarity -------------------------------------------------------------------------------

    @Test
    fun clarityIsIdleAtZeroAndSymmetricInSign() {
        assertEquals(0f, PhotoEffects.clarityDelta(0.5f, 0.3f, 0f), 0f)
        val positive = PhotoEffects.clarityDelta(0.5f, 0.3f, 1f)
        val negative = PhotoEffects.clarityDelta(0.5f, 0.3f, -1f)
        assertTrue(positive > 0f)
        assertEquals(positive, -negative, 1e-6f)
    }

    @Test
    fun clarityProtectsTheEndsOfTheTonalRange() {
        // The midtone bias is what sets clarity apart from raw local contrast: neither crushed black
        // nor pure white can move.
        assertEquals(0f, PhotoEffects.clarityDelta(0f, 0.4f, 1f), 1e-6f)
        assertEquals(0f, PhotoEffects.clarityDelta(1f, 0.4f, 1f), 1e-6f)
        assertTrue(abs(PhotoEffects.clarityDelta(0.5f, 0.3f, 1f)) > abs(PhotoEffects.clarityDelta(0.9f, 0.7f, 1f)))
    }

    @Test
    fun clarityLimitsTheDetailSignalToAvoidHalos() {
        // An edge with a huge step cannot give a proportionally huge correction.
        val moderate = PhotoEffects.clarityDelta(0.5f, 0.2f, 1f)
        val extreme = PhotoEffects.clarityDelta(0.5f, 0.0f, 1f)
        assertTrue(extreme < moderate * 2f)
    }

    @Test
    fun clarityDoesNothingOnAFlatField() {
        assertEquals(0f, PhotoEffects.clarityDelta(0.5f, 0.5f, 1f), 1e-6f)
    }

    // --- colour grading (step 12) -------------------------------------------------------------

    private fun graded(
        red: Float,
        green: Float,
        blue: Float,
        grade: ColorGrade,
    ): FloatArray = floatArrayOf(red, green, blue).also { PhotoEffects.applyColorGrading(it, grade) }

    /** A wheel without saturation or luminance is not a colour: it is the absence of one. */
    @Test
    fun aWheelWithoutSaturationHasNoChroma() {
        val range = colorGradeRangeOf(hue = 210f, saturation = 0f, luminance = 0f)
        assertEquals(ColorGradeRange(), range)
        assertFalse(range.isActive)
    }

    /**
     * The schema's neutral is hue 0 — red — with saturation 0, and it has to reach the pipeline as
     * nothing. Without this reduction a never-edited recipe would stop being neutral.
     */
    @Test
    fun aNeutralRecipeYieldsTheNeutralGrade() {
        val grade = colorGradeOf(
            shadows = colorGradeRangeOf(0f, 0f, 0f),
            midtones = colorGradeRangeOf(0f, 0f, 0f),
            highlights = colorGradeRangeOf(0f, 0f, 0f),
            global = colorGradeRangeOf(0f, 0f, 0f),
            blending = 50f,
            balance = 0f,
        )
        assertEquals(ColorGrade(), grade)
        assertFalse(grade.isActive)
    }

    /** Blending and balance alone paint nothing, and cannot make the recipe active. */
    @Test
    fun blendingAndBalanceAloneStayNeutral() {
        val grade = colorGradeOf(
            shadows = ColorGradeRange(),
            midtones = ColorGradeRange(),
            highlights = ColorGradeRange(),
            global = ColorGradeRange(),
            blending = 90f,
            balance = -70f,
        )
        assertEquals(ColorGrade(), grade)
    }

    /** Each wheel's chroma is a luminance-free vector, by construction. */
    @Test
    fun theChromaOfAWheelCarriesNoLuminance() {
        listOf(0f, 45f, 120f, 210f, 300f, 359f).forEach { hue ->
            val range = colorGradeRangeOf(hue = hue, saturation = 100f, luminance = 0f)
            assertEquals(
                "the $hue° chroma touches the luminance",
                0f,
                PhotoEffects.luminance(range.chromaRed, range.chromaGreen, range.chromaBlue),
                1e-6f,
            )
        }
    }

    /** And that is why a middle grey gets colour and keeps the same lightness. */
    @Test
    fun tintingAMidGreyKeepsItsLuminance() {
        val grade = ColorGrade(global = colorGradeRangeOf(hue = 30f, saturation = 100f, luminance = 0f))
        val result = graded(0.5f, 0.5f, 0.5f, grade)
        assertTrue("the tint did not reach the pixel", result[0] > 0.5f)
        assertTrue(result[2] < 0.5f)
        assertEquals(0.5f, PhotoEffects.luminance(result[0], result[1], result[2]), 1e-5f)
    }

    /**
     * The additive tint exists for this: a closed shadow has to be able to receive colour. Multiplying
     * by the wheel's colour left it black forever.
     */
    @Test
    fun aClosedShadowStillTakesColour() {
        val grade = ColorGrade(shadows = colorGradeRangeOf(hue = 240f, saturation = 100f, luminance = 0f))
        val result = graded(0f, 0f, 0f, grade)
        assertTrue("the shadow stayed black", result[2] > 0.05f)
        assertEquals(0f, result[0], 1e-6f)
    }

    /** Each wheel paints its zone: the shadows' colour cannot appear in a highlight. */
    @Test
    fun eachWheelStaysInItsTonalRange() {
        val grade = ColorGrade(
            shadows = colorGradeRangeOf(hue = 240f, saturation = 100f, luminance = 0f),
            blending = 0f,
        )
        val dark = graded(0.05f, 0.05f, 0.05f, grade)
        val light = graded(0.95f, 0.95f, 0.95f, grade)
        assertTrue(dark[2] - dark[0] > 0.1f)
        assertTrue("the shadows' colour reached the highlight", light[2] - light[0] < 0.02f)
    }

    /** And blending is what lets it bleed into the others. */
    @Test
    fun blendingLetsTheColourBleedIntoTheOtherRanges() {
        val hue = colorGradeRangeOf(hue = 240f, saturation = 100f, luminance = 0f)
        val separated = graded(0.85f, 0.85f, 0.85f, ColorGrade(shadows = hue, blending = 0f))
        val merged = graded(0.85f, 0.85f, 0.85f, ColorGrade(shadows = hue, blending = 100f))
        assertTrue(merged[2] - merged[0] > separated[2] - separated[0])
    }

    /**
     * Balance decides who gets the range. Pushed towards the highlights, a midtone pixel counts more
     * for the light side than it did.
     */
    @Test
    fun balanceMovesThePivotBetweenTheEnds() {
        val hue = colorGradeRangeOf(hue = 120f, saturation = 100f, luminance = 0f)
        val neutral = graded(0.5f, 0.5f, 0.5f, ColorGrade(highlights = hue, balance = 0f))
        val towardHighlights = graded(0.5f, 0.5f, 0.5f, ColorGrade(highlights = hue, balance = 100f))
        assertTrue(towardHighlights[1] > neutral[1])
    }

    /** The global wheel has no tonal zone: it paints dark and light alike. */
    @Test
    fun theGlobalWheelIgnoresTheTonalRanges() {
        val grade = ColorGrade(global = colorGradeRangeOf(hue = 300f, saturation = 60f, luminance = 0f))
        val dark = graded(0.15f, 0.15f, 0.15f, grade)
        val light = graded(0.6f, 0.6f, 0.6f, grade)
        assertEquals(dark[0] - 0.15f, light[0] - 0.6f, 1e-5f)
    }

    /** A wheel's luminance changes lightness without inventing colour. */
    @Test
    fun theLuminanceSliderMovesBrightnessOnly() {
        val grade = ColorGrade(global = ColorGradeRange(luminance = 60f))
        val result = graded(0.4f, 0.4f, 0.4f, grade)
        assertTrue(PhotoEffects.luminance(result[0], result[1], result[2]) > 0.4f)
        assertEquals(result[0], result[1], 1e-6f)
        assertEquals(result[1], result[2], 1e-6f)
    }

    /** An entirely neutral grade cannot move a single pixel. */
    @Test
    fun theNeutralGradeLeavesThePixelAlone() {
        val result = graded(0.2f, 0.45f, 0.7f, ColorGrade())
        assertEquals(0.2f, result[0], 1e-6f)
        assertEquals(0.45f, result[1], 1e-6f)
        assertEquals(0.7f, result[2], 1e-6f)
    }

    /** The weights always add up to one: no luminance can be left without a wheel covering it. */
    @Test
    fun everyLuminanceIsCoveredByTheWeights() {
        listOf(0f, 100f).forEach { blending ->
            listOf(-100f, 0f, 100f).forEach { balance ->
                val width = PhotoEffects.colorGradeWidth(blending)
                val midtone = PhotoEffects.colorGradeMidtone(balance)
                for (step in 0..100) {
                    val value = step / 100f
                    val total = listOf(0f, midtone, 1f).sumOf { centre ->
                        maxOf(1f - abs(value - centre) / width, 0f).toDouble()
                    }
                    assertTrue(
                        "luminance $value without weight with blending $blending and balance $balance",
                        total > 0.0,
                    )
                }
            }
        }
    }

    // --- texture -------------------------------------------------------------------------------

    @Test
    fun textureRespondsToMidFrequencyAndIgnoresFlatAreas() {
        assertEquals(0f, PhotoEffects.textureDelta(0.5f, 0.5f, 0.5f, 1f), 1e-6f)
        assertTrue(PhotoEffects.textureDelta(0.55f, 0.5f, 0.5f, 1f) > 0f)
    }

    @Test
    fun textureIsAttenuatedOverTheEdgesOfLargeSubjects() {
        // Same detail band, but once in the middle of a surface and once on a contrast boundary —
        // that is where reinforcing detail would produce a halo.
        val flat = PhotoEffects.textureDelta(fine = 0.55f, band = 0.5f, coarse = 0.5f, amount = 1f)
        val onEdge = PhotoEffects.textureDelta(fine = 0.55f, band = 0.5f, coarse = 0.9f, amount = 1f)
        assertTrue(onEdge < flat * 0.25f)
    }

    @Test
    fun negativeTextureSmoothsInsteadOfSharpening() {
        assertTrue(PhotoEffects.textureDelta(0.55f, 0.5f, 0.5f, -1f) < 0f)
    }

    @Test
    fun detailCombinesBothToolsWithoutOrderDependence() {
        val value = 0.5f
        val combined = PhotoEffects.detailLuminance(value, 0.55f, 0.5f, 0.35f, texture = 0.5f, clarity = 0.5f)
        val expected = value +
            PhotoEffects.textureDelta(0.55f, 0.5f, 0.35f, 0.5f) +
            PhotoEffects.clarityDelta(value, 0.35f, 0.5f)
        assertEquals(expected, combined, 1e-6f)
    }

    // --- dehaze --------------------------------------------------------------------------------

    @Test
    fun transmissionFallsAsTheDarkChannelRises() {
        assertEquals(1f, PhotoEffects.transmission(darkChannel = 0f, airlight = 1f), 1e-6f)
        assertTrue(PhotoEffects.transmission(0.3f, 1f) < PhotoEffects.transmission(0.1f, 1f))
        assertEquals(0f, PhotoEffects.transmission(1f, 1f), 0.06f)
    }

    @Test
    fun dehazeRecoversASyntheticallyHazedScene() {
        // Direct haze model: I = J·t + A·(1-t). Positive dehaze has to approach J.
        val scene = 0.25f
        val airlight = 0.85f
        val transmission = 0.55f
        val hazed = scene * transmission + airlight * (1f - transmission)

        val recovered = PhotoEffects.dehazeChannel(hazed, airlight, transmission, amount = 1f)

        assertTrue(abs(recovered - scene) < abs(hazed - scene))
    }

    @Test
    fun negativeDehazeAddsHazeTowardsTheAtmosphericLight() {
        val airlight = 0.9f
        val hazier = PhotoEffects.dehazeChannel(0.2f, airlight, transmission = 0.4f, amount = -1f)
        assertTrue(hazier > 0.2f)
        assertTrue(hazier < airlight)
    }

    @Test
    fun dehazeLeavesFullyTransmittingPixelsAlone() {
        assertEquals(0.4f, PhotoEffects.dehazeChannel(0.4f, 0.9f, transmission = 1f, amount = 1f), 1e-5f)
        assertEquals(0.4f, PhotoEffects.dehazeChannel(0.4f, 0.9f, transmission = 1f, amount = -1f), 1e-5f)
    }

    // --- vinheta -------------------------------------------------------------------------------

    @Test
    fun vignetteIsAbsentAtTheCentreAndCompleteAtTheCorners() {
        val centre = PhotoEffects.vignetteFalloff(0.5f, 0.5f, 1.5f, midpoint = 50f, roundness = 0f, feather = 50f)
        val corner = PhotoEffects.vignetteFalloff(0f, 0f, 1.5f, midpoint = 50f, roundness = 0f, feather = 50f)
        assertEquals(0f, centre, 1e-6f)
        assertEquals(1f, corner, 1e-6f)
    }

    @Test
    fun aHigherMidpointPushesTheVignetteOutwards() {
        val near = PhotoEffects.vignetteFalloff(0.25f, 0.5f, 1.5f, midpoint = 10f, roundness = 0f, feather = 50f)
        val far = PhotoEffects.vignetteFalloff(0.25f, 0.5f, 1.5f, midpoint = 90f, roundness = 0f, feather = 50f)
        assertTrue(near > far)
    }

    @Test
    fun negativeRoundnessMakesTheShapeMoreRectangular() {
        // On the diagonal, a more rectangular shape leaves more of the image undarkened than the
        // ellipse; on the horizontal axis the opposite happens.
        val ellipse = PhotoEffects.vignetteFalloff(0.15f, 0.15f, 1f, midpoint = 50f, roundness = 0f, feather = 50f)
        val rectangle = PhotoEffects.vignetteFalloff(0.15f, 0.15f, 1f, midpoint = 50f, roundness = -100f, feather = 50f)
        assertTrue(rectangle < ellipse)
    }

    @Test
    fun negativeAmountDarkensAndPositiveAmountOpens() {
        assertTrue(PhotoEffects.vignetteChannel(0.6f, amount = -80f, falloff = 1f) < 0.6f)
        assertTrue(PhotoEffects.vignetteChannel(0.6f, amount = 80f, falloff = 1f) > 0.6f)
        assertEquals(0.6f, PhotoEffects.vignetteChannel(0.6f, amount = -80f, falloff = 0f), 1e-6f)
    }

    // --- grain ---------------------------------------------------------------------------------

    /** Samples the field on a grid and returns the mean and the standard deviation. */
    private fun grainStatistics(
        size: Float,
        roughness: Float,
        renderScale: Float = 1f,
        side: Int = 160,
    ): Pair<Double, Double> {
        val step = PhotoEffects.grainCellSize(size) * renderScale
        var total = 0.0
        var squares = 0.0
        for (y in 0 until side) {
            for (x in 0 until side) {
                val value = PhotoEffects.grainField(
                    x = (x + 0.5f) / step,
                    y = (y + 0.5f) / step,
                    roughness = roughness,
                    pixelInCells = 1f / step,
                )
                total += value
                squares += value.toDouble() * value
            }
        }
        val count = (side * side).toDouble()
        val mean = total / count
        return mean to kotlin.math.sqrt(squares / count - mean * mean)
    }

    /** The field sampled pixel by pixel of the image, which is where granularity is read. */
    private fun grainGrid(size: Float, roughness: Float, side: Int = 160): Array<FloatArray> {
        val step = PhotoEffects.grainCellSize(size)
        return Array(side) { y ->
            FloatArray(side) { x ->
                PhotoEffects.grainField((x + 0.5f) / step, (y + 0.5f) / step, roughness, 1f / step)
            }
        }
    }

    @Test
    fun grainIsDeterministicAndCentredOnZero() {
        assertEquals(
            PhotoEffects.grainField(12.5f, 7.25f, 50f, 0.3f),
            PhotoEffects.grainField(12.5f, 7.25f, 50f, 0.3f),
            0f,
        )
        // Grain with a non-zero mean was a disguised exposure change.
        val (mean, _) = grainStatistics(size = 50f, roughness = 0f)
        assertEquals(0.0, mean, 0.02)
    }

    @Test
    fun grainVariesAcrossTheFrame() {
        assertNotEquals(
            PhotoEffects.grainField(3.5f, 3.5f, 50f, 0.3f),
            PhotoEffects.grainField(9.5f, 21.5f, 50f, 0.3f),
        )
    }

    @Test
    fun grainFadesInCrushedBlacksAndEasesOffInHighlights() {
        assertEquals(0f, PhotoEffects.grainWeight(0f), 1e-6f)
        assertTrue(PhotoEffects.grainWeight(0.45f) > PhotoEffects.grainWeight(0.98f))
        assertTrue(PhotoEffects.grainWeight(0.45f) > PhotoEffects.grainWeight(0.03f))
    }

    /**
     * The cell is the granularity in image pixels, and the slider takes it from sub-pixel to almost
     * ten — from a fine film to a pushed 3200.
     */
    @Test
    fun theGrainCellIsMeasuredInImagePixels() {
        assertEquals(1.2f, PhotoEffects.grainCellSize(size = 0f), 1e-4f)
        assertEquals(4.17f, PhotoEffects.grainCellSize(size = 50f), 1e-4f)
        assertEquals(7.14f, PhotoEffects.grainCellSize(size = 100f), 1e-4f)
    }

    /**
     * The distance at which grain still resembles itself grows with the slider: three image pixels
     * away, fine grain has already forgotten where it came from and coarse grain is still the same
     * grain.
     *
     * It is the property grid noise also had — enlarging also stretches the correlation. What sets
     * it apart from a magnifier is the amplitude, and the next test deals with that.
     */
    @Test
    fun theSizeSliderChangesHowFarTheGrainStaysCorrelated() {
        fun correlation(size: Float, lag: Int): Float {
            val grid = grainGrid(size, roughness = 50f)
            var product = 0f
            var energy = 0f
            for (row in grid) {
                for (x in 0 until row.size - lag) {
                    product += row[x] * row[x + lag]
                    energy += row[x] * row[x]
                }
            }
            return product / energy
        }
        assertTrue(correlation(25f, lag = 3) < 0.2f)
        assertTrue(correlation(100f, lag = 3) > 0.5f)
    }

    /**
     * And it has to change the amplitude, which grid noise did not: larger particles are fewer in the
     * same area, and the fluctuation that follows is larger. Without this the slider looked like a
     * magnifier over the same texture.
     */
    @Test
    fun coarserGrainCarriesMoreContrastThanFinerGrain() {
        val fine = PhotoEffects.grainSizeGain(0f)
        val middle = PhotoEffects.grainSizeGain(25f)
        val coarse = PhotoEffects.grainSizeGain(100f)
        assertEquals(1f, middle, 1e-6f)
        assertTrue(fine < 0.7f)
        assertTrue(coarse > 1.6f)
    }

    /**
     * Roughness changes the character and not the volume: the field's standard deviation stays where
     * it was, and what changes is the irregularity. A roughness slider that raised the noise would be
     * a second intensity slider.
     */
    @Test
    fun roughnessChangesTheCharacterAndNotTheVolume() {
        val deviations = listOf(0f, 50f, 100f).map { grainStatistics(size = 100f, roughness = it).second }
        deviations.forEach { assertTrue("deviation outside the band: $it", it in 0.24..0.33) }
        // And irregular means the grain does not have the same strength everywhere: it is measured by
        // the spread of local contrast from clump to clump, which is what the eye reads as roughness.
        fun unevenness(roughness: Float): Double {
            val grid = grainGrid(size = 100f, roughness = roughness)
            val patch = 12
            val locals = mutableListOf<Double>()
            for (top in 0..grid.size - patch step patch) {
                for (left in 0..grid.size - patch step patch) {
                    var total = 0.0
                    var squares = 0.0
                    for (y in top until top + patch) {
                        for (x in left until left + patch) {
                            total += grid[y][x]
                            squares += grid[y][x].toDouble() * grid[y][x]
                        }
                    }
                    val count = (patch * patch).toDouble()
                    val mean = total / count
                    locals += kotlin.math.sqrt(squares / count - mean * mean)
                }
            }
            val mean = locals.average()
            return kotlin.math.sqrt(locals.sumOf { (it - mean) * (it - mean) } / locals.size) / mean
        }
        assertTrue(unevenness(100f) > 1.5 * unevenness(0f))
    }

    /**
     * Sub-pixel grain fades instead of flickering.
     *
     * With the photo fitted, a particle smaller than the screen pixel cannot be shown: sampled
     * straight it would give full-contrast noise dancing on every redraw. The kernel spreads and
     * weakens it, which is what the pixel's average would do.
     */
    @Test
    fun grainBelowThePixelOfTheTargetFadesInsteadOfAliasing() {
        val full = grainStatistics(size = 25f, roughness = 50f, renderScale = 1f).second
        val half = grainStatistics(size = 25f, roughness = 50f, renderScale = 0.5f).second
        val quarter = grainStatistics(size = 25f, roughness = 50f, renderScale = 0.25f).second
        val eighth = grainStatistics(size = 25f, roughness = 50f, renderScale = 0.125f).second
        assertTrue(full > half)
        assertTrue(half > quarter)
        assertTrue(quarter > eighth)
        assertTrue("a fitted preview's grain still flickers: $eighth", eighth < full / 4)
    }

    /**
     * The four draws come from the same hash, in eight-bit slices, and have to come out independent:
     * while the x offset followed the radius, large particles were all pushed to the same side of the
     * cell and the grid showed in the image.
     */
    /**
     * The hash is the same number on the JVM and the GPU, and is only really the same if these three
     * values are: a multiplication with another sign, or an arithmetic shift where it should be
     * logical, goes unnoticed by any statistical test — it is still noise, just different noise.
     */
    @Test
    fun theCellHashIsTheSameNumberOnBothSides() {
        assertEquals(-215834403, PhotoEffects.grainHash(0, 0))
        assertEquals(1948490361, PhotoEffects.grainHash(1, 0))
        assertEquals(1338278971, PhotoEffects.grainHash(12, -7))
    }

    @Test
    fun theSlicesOfTheCellHashAreIndependent() {
        var sumJitter = 0.0
        var sumRadius = 0.0
        var sumProduct = 0.0
        var sumJitterSquare = 0.0
        var sumRadiusSquare = 0.0
        var count = 0
        for (y in 0 until 120) {
            for (x in 0 until 120) {
                val hash = PhotoEffects.grainHash(x, y)
                val jitter = (hash and 0xFF) / 255.0
                val radius = (hash ushr 16 and 0xFF) / 255.0
                sumJitter += jitter
                sumRadius += radius
                sumProduct += jitter * radius
                sumJitterSquare += jitter * jitter
                sumRadiusSquare += radius * radius
                count++
            }
        }
        val n = count.toDouble()
        val covariance = sumProduct / n - (sumJitter / n) * (sumRadius / n)
        val spreadJitter = kotlin.math.sqrt(sumJitterSquare / n - (sumJitter / n) * (sumJitter / n))
        val spreadRadius = kotlin.math.sqrt(sumRadiusSquare / n - (sumRadius / n) * (sumRadius / n))
        assertTrue(
            "the hash slices are correlated",
            kotlin.math.abs(covariance / (spreadJitter * spreadRadius)) < 0.05,
        )
        assertEquals(0.5, sumJitter / n, 0.02)
    }

    // --- kernels -------------------------------------------------------------------------------

    @Test
    fun halfGaussianKernelIsNormalisedOverBothSides() {
        val weights = PhotoEffects.halfGaussianKernel(3f)
        val total = weights[0] + 2f * weights.drop(1).sum()
        assertEquals(1f, total, 1e-5f)
        assertTrue(weights.toList().zipWithNext().all { (near, far) -> far < near })
        assertTrue(weights.lastIndex <= PhotoEffects.MAX_BLUR_RADIUS)
    }

    @Test
    fun hugeSigmasStayInsideTheRadiusTheShaderCanLoopOver() {
        assertEquals(PhotoEffects.MAX_BLUR_RADIUS, PhotoEffects.halfGaussianKernel(400f).lastIndex)
    }
}
