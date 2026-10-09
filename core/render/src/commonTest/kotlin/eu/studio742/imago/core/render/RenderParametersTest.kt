package eu.studio742.imago.core.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RenderParametersTest {
    @Test fun defaultsAreNeutral() = assertTrue(RenderParameters().isNeutral)
    @Test fun exposureMakesParametersNonNeutral() = assertFalse(RenderParameters(exposure = 1f).isNeutral)
    @Test fun geometryIsNotNeutralButDoesNotRequireColorProcessing() {
        val parameters = RenderParameters(cropWidth = 0.8f, straighten = 2.5f, rotation = 90)
        assertFalse(parameters.isNeutral)
        assertTrue(parameters.isColorNeutral)
    }


    @Test fun straightenCoverScaleKeepsTheWholeCanvasCovered() {
        assertEquals(1f, straightenCoverScale(3f / 2f, 0f), 0.0001f)
        assertEquals(kotlin.math.sqrt(2f), straightenCoverScale(1f, 45f), 0.0001f)
        assertTrue(straightenCoverScale(3f / 2f, 30f) > 1f)
    }

    @Test fun identityCurveHasAllExpectedSamples() {
        val curve = identityToneCurve()
        assertEquals(256, curve.size)
        assertEquals(0f, curve.first())
        assertEquals(1f, curve.last())
    }

    @Test fun toneCurveInterpolatesControlPoints() {
        val curve = buildToneCurveLut(listOf(0 to 0, 128 to 192, 255 to 255))
        assertEquals(192f / 255f, curve[128], 0.0001f)
        assertTrue(curve.zipWithNext().all { (left, right) -> right >= left })
    }

    @Test fun freedEndPointsHoldTheCurveFlatBeyondThem() {
        // The black point in to 20 and the white point in to 235: Lightroom clips both ends.
        val points = listOf(20 to 0, 235 to 255)
        listOf(ToneCurveInterpolation.LINEAR, ToneCurveInterpolation.PCHIP).forEach { interpolation ->
            val curve = buildToneCurveLut(points, interpolation, flatBeyondEnds = true)
            assertTrue(curve.subList(0, 21).all { it == 0f })
            assertTrue(curve.subList(235, 256).all { it == 1f })
            assertEquals(0.5f, curve[128], 0.01f)
        }
        // A raised black point holds its height to the left as well.
        val lifted = buildToneCurveLut(listOf(30 to 40, 255 to 255), flatBeyondEnds = true)
        assertTrue(lifted.subList(0, 31).all { it == 40f / 255f })
    }

    @Test fun beforeProcessElevenTheCurveIsPinnedToTheCorners() {
        // A raised black point at (30, 40): pinned, the curve still starts at (0, 0) and climbs to it.
        val raised = buildToneCurveLut(listOf(30 to 40, 255 to 255), ToneCurveInterpolation.LINEAR)
        assertEquals(0f, raised[0])
        assertEquals(20f / 255f, raised[15], 0.0001f)
    }

    @Test fun pchipCurveIsSmoothShapePreservingAndPassesThroughPoints() {
        val points = listOf(0 to 0, 64 to 20, 128 to 200, 255 to 255)
        val smooth = buildToneCurveLut(points, ToneCurveInterpolation.PCHIP)
        val linear = buildToneCurveLut(points, ToneCurveInterpolation.LINEAR)

        points.forEach { (x, y) -> assertEquals(y / 255f, smooth[x], 0.0001f) }
        assertTrue(smooth.zipWithNext().all { (left, right) -> right >= left })
        assertTrue(kotlin.math.abs(smooth[32] - linear[32]) > 0.005f)
        assertTrue(smooth.subList(0, 65).all { it in 0f..(20f / 255f) })
        assertTrue(smooth.subList(64, 129).all { it in (20f / 255f)..(200f / 255f) })
    }

    @Test fun pchipDoesNotOvershootNonMonotonicControlPoints() {
        val curve = buildToneCurveLut(
            listOf(0 to 0, 85 to 200, 170 to 60, 255 to 255),
            ToneCurveInterpolation.PCHIP,
        )

        assertTrue(curve.subList(0, 86).all { it in 0f..(200f / 255f) })
        assertTrue(curve.subList(85, 171).all { it in (60f / 255f)..(200f / 255f) })
        assertTrue(curve.subList(170, 256).all { it in (60f / 255f)..1f })
    }

    @Test fun eachChannelCurveRunsAfterTheCompositeOne() {
        val composite = buildToneCurveLut(listOf(0 to 0, 128 to 192, 255 to 255), flatBeyondEnds = true)
        val blue = buildToneCurveLut(listOf(0 to 0, 192 to 64, 255 to 255), flatBeyondEnds = true)
        val tables = RenderParameters(toneCurveRgb = composite, toneCurveBlue = blue).channelToneCurves

        // Mid-grey goes up to 192 through the composite, and the blue curve takes 192 down to 64.
        assertEquals(192f / 255f, tables[128 * 3], 0.0001f)
        assertEquals(192f / 255f, tables[128 * 3 + 1], 0.0001f)
        assertEquals(64f / 255f, tables[128 * 3 + 2], 0.0001f)
        // In the other order — blue first — it would have been composite(blue(128)), somewhere else.
        val blueFirst = sampleToneCurve(composite, sampleToneCurve(blue, 128f / 255f))
        assertTrue("blue first gives ${blueFirst * 255}", kotlin.math.abs(blueFirst - 64f / 255f) > 10f / 255f)
    }

    @Test fun aBlueCurveChangesOnlyTheBlueOfAnExportedPixel() {
        val source = 0xFF808080.toInt()
        val neutral = RenderParameters()
        val bluer = RenderParameters(toneCurveBlue = buildToneCurveLut(listOf(0 to 0, 128 to 200, 255 to 255), flatBeyondEnds = true))
        val before = BitmapPhotoProcessor.processPixel(source, neutral, neutral.effectiveTone())
        val after = BitmapPhotoProcessor.processPixel(source, bluer, bluer.effectiveTone())

        assertEquals(before ushr 16 and 0xFF, after ushr 16 and 0xFF)
        assertEquals(before ushr 8 and 0xFF, after ushr 8 and 0xFF)
        assertTrue((after and 0xFF) > (before and 0xFF) + 40)
        assertFalse(bluer.isNeutral)
    }

    @Test fun neutralCpuPixelIsStableWithinOneChannelValue() {
        val source = 0xFF4A80C0.toInt()
        val parameters = RenderParameters()
        val result = BitmapPhotoProcessor.processPixel(source, parameters, parameters.effectiveTone())
        assertEquals(0xFF, result ushr 24 and 0xFF)
        assertTrue(kotlin.math.abs((result ushr 16 and 0xFF) - 0x4A) <= 1)
        assertTrue(kotlin.math.abs((result ushr 8 and 0xFF) - 0x80) <= 1)
        assertTrue(kotlin.math.abs((result and 0xFF) - 0xC0) <= 1)
    }

    @Test fun cpuPixelAppliesExposureAndHsl() {
        val source = 0xFFFF3000.toInt()
        val parameters = RenderParameters(
            exposure = -1f,
            hslBands = neutralHslBands().toMutableList().apply {
                this[0] = HslRenderBand(hue = 50f, saturation = -30f, luminance = 10f)
            },
        )
        assertNotEquals(
            source,
            BitmapPhotoProcessor.processPixel(source, parameters, parameters.effectiveTone()),
        )
    }

    @Test fun contrastUsesAnEndpointPreservingMidtoneCurve() {
        assertEquals(0f, midtoneContrastLuminance(0f, 1f), 0.0001f)
        assertEquals(0.5f, midtoneContrastLuminance(0.5f, 1f), 0.0001f)
        assertEquals(1f, midtoneContrastLuminance(1f, 1f), 0.0001f)
        assertTrue(midtoneContrastLuminance(0.25f, 1f) < 0.25f)
        assertTrue(midtoneContrastLuminance(0.75f, 1f) > 0.75f)
        assertTrue(midtoneContrastLuminance(0.25f, -1f) > 0.25f)
    }

    /**
     * The property that stops highlights from blowing out and shadows from crushing: whatever the
     * slider and whatever the neighbourhood, both ends of each band are fixed. If this fails, clipping
     * is back eating detail at one end of the scale.
     */
    @Test fun tonalToolsAreAnchoredAtBothEndsOfTheirBand() {
        listOf(-1f, -0.42f, 0f, 0.6f, 1f).forEach { amount ->
            listOf(NO_LOCAL_BASE, 0f, 0.5f, 1f).forEach { base ->
                val highlightPivot = HIGHLIGHT_PIVOT
                val shadowPivot = 1f - HIGHLIGHT_PIVOT
                assertEquals("branco $amount/$base", 1f, locallyAdaptedHighlights(1f, base, amount), 0.0001f)
                assertEquals(
                    "highlights pivot $amount/$base",
                    highlightPivot,
                    locallyAdaptedHighlights(highlightPivot, base, amount),
                    0.0001f,
                )
                assertEquals("preto $amount/$base", 0f, locallyAdaptedShadows(0f, base, amount), 0.0001f)
                assertEquals(
                    "shadows pivot $amount/$base",
                    shadowPivot,
                    locallyAdaptedShadows(shadowPivot, base, amount),
                    0.0001f,
                )
            }
        }
    }

    /**
     * Each tool stays within its band — which is not half the scale each. The two bands overlap in
     * the midtones on purpose, because a light midtone is legitimately the work of both; what neither
     * can do is reach the opposite end.
     */
    @Test fun eachToolStaysInsideItsOwnBand() {
        listOf(-1f, 1f).forEach { amount ->
            assertEquals(0.20f, selectiveHighlightLuminance(0.20f, amount), 0.0001f)
            assertEquals(0.90f, selectiveShadowLuminance(0.90f, amount), 0.0001f)
        }
        val midtoneByHighlights = selectiveHighlightLuminance(0.5f, -1f)
        val midtoneByShadows = selectiveShadowLuminance(0.5f, 1f)
        assertTrue("highlights have to reach 0.50, they stayed at $midtoneByHighlights", midtoneByHighlights < 0.48f)
        assertTrue("shadows have to reach 0.50, they stayed at $midtoneByShadows", midtoneByShadows > 0.52f)
    }

    /** Outside the range the slider saturates; it does not keep pulling or flip its sign. */
    @Test fun tonalToolsClampTheSliderInsteadOfExtrapolating() {
        assertEquals(
            selectiveHighlightLuminance(0.8f, -1f),
            selectiveHighlightLuminance(0.8f, -4f),
            0.0001f,
        )
        assertEquals(
            selectiveShadowLuminance(0.2f, 1f),
            selectiveShadowLuminance(0.2f, 4f),
            0.0001f,
        )
    }

    /**
     * Shadows are the mirror of highlights around 0.5, pixel and neighbourhood mirrored together and
     * the slider's sign swapped. It is through this identity that proving one of the two is enough.
     */
    @Test fun shadowsAreTheExactMirrorOfHighlights() {
        listOf(-1f, -0.3f, 0.4f, 1f).forEach { amount ->
            listOf(NO_LOCAL_BASE, 0.1f, 0.5f, 0.9f).forEach { base ->
                for (step in 0..100) {
                    val value = step / 100f
                    val mirroredBase = if (base < 0f) NO_LOCAL_BASE else 1f - base
                    assertEquals(
                        "mirror at $value with $amount/$base",
                        1f - locallyAdaptedHighlights(1f - value, mirroredBase, -amount),
                        locallyAdaptedShadows(value, base, amount),
                        0.0001f,
                    )
                }
            }
        }
    }

    /**
     * The entry into the band is gradual, and that is what separates a highlights slider from an
     * exposure one: at 0.55 almost nothing moves, and the maximum shift is up high.
     *
     * Where the maximum is is the honest way to say this. Comparing the shift at two fixed points
     * measured gradualness against the slider's strength — the stronger the gain, the more the top
     * of the band saturates against the white anchor and the more that ratio shrinks, without the
     * entry having changed. The peak is the property we want, and it does not depend on the gain.
     */
    @Test fun highlightsEnterTheBandGraduallyInsteadOfLiftingEverything() {
        assertEquals(0.40f, selectiveHighlightLuminance(0.40f, 1f), 0.02f)
        val peak = (350..1000).maxBy { step ->
            val value = step / 1000f
            selectiveHighlightLuminance(value, 1f) - value
        } / 1000f
        assertTrue("the maximum shift was at $peak, it should be well above the pivot", peak > 0.65f)
        val nearPivot = selectiveHighlightLuminance(0.45f, 1f) - 0.45f
        val nearPeak = selectiveHighlightLuminance(0.78f, 1f) - 0.78f
        assertTrue("the effect has to grow above the pivot", nearPeak > 3f * nearPivot)
    }

    /**
     * The slider has to reach the end of its travel with strength well above noise. The old
     * linearised form had a ceiling of an eighth of a band — at −100 a 0.90 sky only went down to
     * 0.85, and the tool seemed to do nothing.
     */
    @Test fun theExtremesOfTheSliderMoveTheBandDecisively() {
        val sky = selectiveHighlightLuminance(0.90f, -1f)
        assertTrue("a 0.90 sky at −100 ended at $sky", sky < 0.78f)
        val shadow = selectiveShadowLuminance(0.10f, 1f)
        assertTrue("a 0.10 shadow at +100 ended at $shadow", shadow > 0.22f)
    }

    /**
     * Recovering highlights has to stretch the almost blown range, not crush it — exactly the
     * opposite of what the old multiplicative curve did. And the same near black when lifting
     * shadows, which is the same curve seen in the mirror.
     */
    @Test fun recoveryExpandsTheContrastNextToTheEndpoints() {
        val highlights = selectiveHighlightLuminance(1f, -1f) - selectiveHighlightLuminance(0.9f, -1f)
        assertTrue("0.90–1.00 should come out stretched, it came out $highlights", highlights > 1.4f * 0.1f)
        val shadows = selectiveShadowLuminance(0.1f, 1f) - selectiveShadowLuminance(0f, 1f)
        assertTrue("0.00–0.10 should come out stretched, it came out $shadows", shadows > 1.4f * 0.1f)
    }

    /**
     * A non-monotonic curve would swap the order of the tones, and that is what the two gains exist to
     * prevent. The sweep includes the neighbourhood because it is what tightens the limit: in the
     * corner where the mask is white and the pixel is at the pivot — a dark point over a blown sky —
     * a gain with a magnitude above one would turn the derivative negative.
     */
    @Test fun tonalToolsStayMonotonicForEveryNeighbourhood() {
        listOf(-1f, -0.6f, -0.2f, 0.2f, 0.6f, 1f).forEach { amount ->
            for (baseStep in 0..20) {
                val base = baseStep / 20f
                var previousHighlight = -1f
                var previousShadow = -1f
                for (step in 0..500) {
                    val value = step / 500f
                    val highlight = locallyAdaptedHighlights(value, base, amount)
                    val shadow = locallyAdaptedShadows(value, base, amount)
                    assertTrue("highlights went down at $value with $amount/$base", highlight >= previousHighlight)
                    assertTrue("shadows went down at $value with $amount/$base", shadow >= previousShadow)
                    previousHighlight = highlight
                    previousShadow = shadow
                }
            }
        }
    }

    /**
     * Local adaptation proper: the same light pixel is treated as sky when it is in a sky and almost
     * left alone when it is an isolated reflection in the dark.
     *
     * *Almost*, and not entirely, because part of the flow's time comes from the pixel itself: the
     * all-or-nothing version of this rule left the reflection exactly where it was and made the
     * slider seem broken in mixed-light scenes.
     */
    @Test fun localAdaptationTellsASkyFromAnIsolatedHighlight() {
        val inSky = locallyAdaptedHighlights(0.95f, base = 0.93f, amount = -1f)
        val isolated = locallyAdaptedHighlights(0.95f, base = 0.20f, amount = -1f)
        assertTrue("in a sky the highlight has to come down, it ended at $inSky", inSky < 0.93f)
        assertTrue(
            "an isolated reflection is not sky: it came down to $isolated against $inSky in the sky",
            0.95f - isolated < 0.4f * (0.95f - inSky),
        )
        assertTrue(
            "but it is not untouchable either — adaptation is a gradation, not a switch; it ended at $isolated",
            isolated < 0.94f,
        )
    }

    /**
     * The halo a naive local tone map produces: a dark branch against a blown sky darkened together
     * with the sky. Here the pixel is below the pivot and so untouchable.
     */
    @Test fun localAdaptationCannotDragAPixelOutsideItsOwnBand() {
        assertEquals(0.20f, locallyAdaptedHighlights(0.20f, base = 0.95f, amount = -1f), 0.0001f)
        assertEquals(0.80f, locallyAdaptedShadows(0.80f, base = 0.05f, amount = 1f), 0.0001f)
    }

    /** Without a mask, a pixel's neighbourhood is itself — and there is no special case at all. */
    @Test fun theGlobalCurveIsTheLocalOneWithTheNeighbourhoodSetToThePixel() {
        listOf(-1f, -0.4f, 0.4f, 1f).forEach { amount ->
            for (step in 0..200) {
                val value = step / 200f
                assertEquals(
                    locallyAdaptedHighlights(value, value, amount),
                    locallyAdaptedHighlights(value, NO_LOCAL_BASE, amount),
                    0.0001f,
                )
            }
        }
    }
}
