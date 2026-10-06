package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape
import org.junit.Test

class LocalMaskFieldTest {
    private val aspect = 4f / 3f

    private fun radial(
        x: Float = 0.5f,
        y: Float = 0.5f,
        radiusX: Float = 0.3f,
        radiusY: Float = 0.3f,
        feather: Float = 50f,
        roundness: Float = 0f,
        op: MaskOp = MaskOp.ADD,
        inverted: Boolean = false,
    ) = MaskRenderComponent(
        shape = MaskShape.RADIAL,
        op = op,
        inverted = inverted,
        centreX = x,
        centreY = y,
        radiusX = radiusX,
        radiusY = radiusY,
        feather = feather,
        roundness = roundness,
    )

    private fun linear(y: Float = 0.5f, width: Float = 0.5f) = MaskRenderComponent(
        shape = MaskShape.LINEAR,
        centreY = y,
        width = width,
    )

    @Test
    fun aRadialIsFullInTheMiddleAndEmptyOutside() {
        val mask = MaskRenderSpec(components = listOf(radial()))

        assertEquals(1f, LocalMaskField.weightOf(mask, 0.5f, 0.5f, aspect), 1e-4f)
        assertEquals(0f, LocalMaskField.weightOf(mask, 0.99f, 0.99f, aspect), 1e-4f)
        assertEquals(0f, LocalMaskField.weightOf(mask, 0.01f, 0.01f, aspect), 1e-4f)
    }

    /** Without the aspect correction, an equal radius on both axes would give an ellipse. */
    @Test
    fun aRadialWithEqualRadiiIsRound() {
        val mask = MaskRenderSpec(components = listOf(radial(radiusX = 0.2f, radiusY = 0.2f)))
        // A step of 0.2 in units of height: in y it is 0.2 of the side; in x it is 0.2 / aspect.
        val below = LocalMaskField.weightOf(mask, 0.5f, 0.5f + 0.1f, aspect)
        val beside = LocalMaskField.weightOf(mask, 0.5f + 0.1f / aspect, 0.5f, aspect)

        assertEquals(below, beside, 1e-4f)
    }

    @Test
    fun aLinearIsHalfwayOnItsOwnLine() {
        val mask = MaskRenderSpec(components = listOf(linear(y = 0.5f, width = 0.5f)))

        assertEquals(0.5f, LocalMaskField.weightOf(mask, 0.3f, 0.5f, aspect), 1e-4f)
        assertEquals(0f, LocalMaskField.weightOf(mask, 0.3f, 0.1f, aspect), 1e-4f)
        assertEquals(1f, LocalMaskField.weightOf(mask, 0.3f, 0.9f, aspect), 1e-4f)
    }

    @Test
    fun invertingSwapsInsideForOutside() {
        val plain = MaskRenderSpec(components = listOf(radial()))
        val inverted = plain.copy(inverted = true)

        assertEquals(0f, LocalMaskField.weightOf(inverted, 0.5f, 0.5f, aspect), 1e-4f)
        assertEquals(1f, LocalMaskField.weightOf(inverted, 0.99f, 0.99f, aspect), 1e-4f)
    }

    /** A large radial minus a small one is a ring: full in the band, empty in the middle and outside. */
    @Test
    fun subtractingAComponentHollowsOutTheMask() {
        val ring = MaskRenderSpec(
            components = listOf(
                radial(radiusX = 0.4f, radiusY = 0.4f, feather = 5f),
                radial(radiusX = 0.15f, radiusY = 0.15f, feather = 5f, op = MaskOp.SUBTRACT),
            ),
        )

        assertEquals(0f, LocalMaskField.weightOf(ring, 0.5f, 0.5f, aspect), 1e-3f)
        assertEquals(1f, LocalMaskField.weightOf(ring, 0.5f, 0.5f + 0.25f, aspect), 1e-3f)
        assertEquals(0f, LocalMaskField.weightOf(ring, 0.5f, 0.99f, aspect), 1e-3f)
    }

    /**
     * The first component seeds the accumulator. If it combined with nothing instead, a mask that
     * started by subtracting would stay at zero for good.
     */
    @Test
    fun theFirstComponentSeedsTheMaskWhateverItsOperation() {
        val mask = MaskRenderSpec(components = listOf(radial(op = MaskOp.SUBTRACT)))

        assertEquals(1f, LocalMaskField.weightOf(mask, 0.5f, 0.5f, aspect), 1e-4f)
    }

    /**
     * Two overlapping masks add up in parameter space. It is the property that makes composition
     * predictable, and the reason outputs are not mixed.
     */
    @Test
    fun twoOverlappingMasksAddTheirAdjustments() {
        val parameters = RenderParameters(
            exposure = 0.5f,
            masks = listOf(
                MaskRenderSpec(
                    components = listOf(radial(feather = 1f)),
                    adjustments = LocalAdjustments(exposure = 1f, clarity = 10f),
                ),
                MaskRenderSpec(
                    components = listOf(radial(feather = 1f)),
                    adjustments = LocalAdjustments(exposure = 2f),
                ),
            ),
        )
        val field = LocalMaskField.build(800, 600, parameters, FrameGeometry(4000, 3000))
        val tone = EffectiveTone()
        val weights = FloatArray(4)

        field.resolve(parameters, 0.5f, 0.5f, weights, tone)
        assertEquals(0.5f + 1f + 2f, tone.exposure, 1e-3f)
        assertEquals(10f, tone.clarity, 1e-3f)

        field.resolve(parameters, 0.02f, 0.02f, weights, tone)
        assertEquals(0.5f, tone.exposure, 1e-3f)
        assertEquals(0f, tone.clarity, 1e-3f)
    }

    /** Exposure is the only one that does not limit itself inside the functions that consume it. */
    @Test
    fun theEffectiveExposureIsClamped() {
        val parameters = RenderParameters(
            exposure = 4f,
            masks = listOf(
                MaskRenderSpec(
                    components = listOf(radial(feather = 1f)),
                    adjustments = LocalAdjustments(exposure = 5f),
                ),
            ),
        )
        val field = LocalMaskField.build(800, 600, parameters, FrameGeometry(4000, 3000))
        val tone = EffectiveTone()

        field.resolve(parameters, 0.5f, 0.5f, FloatArray(4), tone)

        assertEquals(MAX_LOCAL_EXPOSURE, tone.exposure, 1e-4f)
    }

    /**
     * The test that validates the decision to keep masks in image coordinates: cropping moves the frame
     * around the mask, and the mask stays over the same content.
     *
     * The chosen image point — the radial's centre — falls in the middle of the uncropped frame and at
     * the top-left corner of the cropped frame, and in both cases the weight is the same.
     */
    @Test
    fun aMaskStaysOnItsContentWhenTheFrameChanges() {
        val parameters = RenderParameters(
            masks = listOf(
                MaskRenderSpec(
                    components = listOf(radial(x = 0.5f, y = 0.5f, radiusX = 0.1f, radiusY = 0.1f)),
                    adjustments = LocalAdjustments(exposure = 1f),
                ),
            ),
        )
        val whole = FrameGeometry(4000, 3000)
        val cropped = FrameGeometry(
            sourceWidth = 4000,
            sourceHeight = 3000,
            cropX = 0.5f,
            cropY = 0.5f,
            cropWidth = 0.5f,
            cropHeight = 0.5f,
        )

        val wholeField = LocalMaskField.build(4000, 3000, parameters, whole)
        val croppedField = LocalMaskField.build(2000, 1500, parameters, cropped)
        val out = FloatArray(4)

        wholeField.at(0.5f, 0.5f, out)
        val beforeCrop = out[0]
        // The same image point, now at the origin of the cropped frame.
        croppedField.at(0f, 0f, out)
        val afterCrop = out[0]

        assertEquals(1f, beforeCrop, 1e-3f)
        assertEquals(beforeCrop, afterCrop, 1e-3f)
    }

    /**
     * Preset thumbnails come from a 220 px source. At one eighth they would be 27 texels, and the mask
     * the thumbnail showed would not be the one the photo has.
     */
    @Test
    fun theFieldHasAFloorSoPresetThumbnailsDoNotLie() {
        val parameters = RenderParameters(
            masks = listOf(
                MaskRenderSpec(
                    components = listOf(radial(radiusX = 0.08f, radiusY = 0.08f, feather = 10f)),
                    adjustments = LocalAdjustments(exposure = 1f),
                ),
            ),
        )
        val field = LocalMaskField.build(220, 165, parameters, FrameGeometry(220, 165))
        val out = FloatArray(4)

        field.at(0.5f, 0.5f, out)
        assertTrue("the radial's centre should be full, it came ${out[0]}", out[0] > 0.9f)
        field.at(0.5f, 0.05f, out)
        assertTrue("far from the radial it should be empty, it came ${out[0]}", out[0] < 0.1f)
    }

    /** A mask with every adjustment at neutral does not even get built. */
    @Test
    fun aNeutralMaskDoesNotAskForTheField() {
        val parameters = RenderParameters(
            masks = listOf(MaskRenderSpec(components = listOf(radial()))),
        )

        assertTrue(parameters.needsMaskField)
        assertTrue(RenderParameters().needsMaskField.not())
    }

    /** A local adjustment counts as much as a global one in deciding which passes run. */
    @Test
    fun aLocalDetailAdjustmentStillBuildsThePyramid() {
        val parameters = RenderParameters(
            masks = listOf(
                MaskRenderSpec(
                    components = listOf(radial()),
                    adjustments = LocalAdjustments(clarity = 40f),
                ),
            ),
        )

        assertTrue(parameters.needsDetailStage)
        assertTrue(parameters.needsClarityBlur)
        assertTrue(parameters.needsDehazeStats.not())
    }

    /** With active masks the recipe is not neutral, and the export cannot return the original. */
    @Test
    fun aMaskedRecipeIsNeitherNeutralNorColourNeutral() {
        val parameters = RenderParameters(
            masks = listOf(
                MaskRenderSpec(
                    components = listOf(radial()),
                    adjustments = LocalAdjustments(exposure = 1f),
                ),
            ),
        )

        assertTrue(parameters.isNeutral.not())
        assertTrue(parameters.isColorNeutral.not())
    }
}
