package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.render.toRenderParameters
import eu.studio742.imago.core.render.lightWhiteBalanceOf
import eu.studio742.imago.core.model.WhiteBalance
import eu.studio742.imago.core.model.BUILT_IN_RECIPES

class RecipeAdjustmentTest {
    private val neutral = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-08-06T12:00:00Z",
        updatedAt = "2026-08-06T12:00:00Z",
    )

    @Test
    fun everyVisibleAdjustmentUpdatesOnlyItsRecipeValue() {
        Adjustment.entries.forEach { adjustment ->
            val changed = neutral.withAdjustment(adjustment, adjustment.range.endInclusive / 2f)

            assertEquals(adjustment.range.endInclusive / 2f, changed.adjustmentValue(adjustment))
            Adjustment.entries.filterNot { it == adjustment }.forEach { untouched ->
                assertEquals(untouched.neutral, changed.adjustmentValue(untouched))
            }
        }
    }

    @Test
    fun neutralRecipeMapsToNeutralRenderParameters() {
        assertTrue(neutral.toRenderParameters().isNeutral)
    }

    @Test
    fun curveAndHslMapToRenderParameters() {
        val changed = neutral.copy(
            toneCurve = neutral.toneCurve.copy(rgb = listOf(CurvePoint(0, 10), CurvePoint(255, 245))),
            hsl = neutral.hsl.copy(red = HslBand(hue = 12f, saturation = 20f, luminance = -8f)),
        ).toRenderParameters()

        assertEquals(10f / 255f, changed.toneCurveRgb.first(), 0.0001f)
        assertEquals(12f, changed.hslBands.first().hue)
        assertEquals(20f, changed.hslBands.first().saturation)
        assertEquals(-8f, changed.hslBands.first().luminance)
    }

    @Test
    fun colorGradingWheelsMapToRenderParameters() {
        val grade = neutral.copy(
            colorGrading = ColorGrading(
                shadows = ColorWheel(hue = 210f, saturation = 40f, luminance = -10f),
                global = ColorWheel(hue = 45f, saturation = 25f),
                blending = 80f,
                balance = -30f,
            ),
        ).toRenderParameters().colorGrade

        assertTrue(grade.isActive)
        assertEquals(80f, grade.blending)
        assertEquals(-30f, grade.balance)
        assertEquals(-10f, grade.shadows.luminance)
        // The 210° blue reaches the pipeline as chroma, and the chroma has to say so: more blue than
        // red. The hue itself does not travel — see `ColorGradeRange`.
        assertTrue(grade.shadows.chromaBlue > 0f)
        assertTrue(grade.shadows.chromaRed < 0f)
        assertTrue(grade.global.chromaRed > 0f)
        assertFalse(grade.midtones.isActive)
        assertFalse(grade.highlights.isActive)
    }

    /** Each wheel writes to its own, and not to the other three. */
    @Test
    fun eachWheelIsItsOwnValue() {
        ColorGradeWheel.entries.forEach { wheel ->
            var recipe = neutral
            ColorGradeComponent.entries.forEach { component ->
                recipe = recipe.copy(colorGrading = recipe.colorGrading.let { grading ->
                    when (wheel) {
                        ColorGradeWheel.SHADOWS -> grading.copy(shadows = grading.shadows.withTestValue(component))
                        ColorGradeWheel.MIDTONES -> grading.copy(midtones = grading.midtones.withTestValue(component))
                        ColorGradeWheel.HIGHLIGHTS ->
                            grading.copy(highlights = grading.highlights.withTestValue(component))
                        ColorGradeWheel.GLOBAL -> grading.copy(global = grading.global.withTestValue(component))
                    }
                })
            }
            ColorGradeComponent.entries.forEach { component ->
                assertEquals(
                    testValue(component),
                    recipe.colorGradeWheel(wheel).component(component),
                )
            }
            ColorGradeWheel.entries.filterNot { it == wheel }.forEach { untouched ->
                assertEquals(ColorWheel(), recipe.colorGradeWheel(untouched))
            }
        }
    }

    /** Hue wraps around: the handle dragged past red continues through red. */
    @Test
    fun theHueWrapsAroundInsteadOfStopping() {
        assertEquals(10f, positiveHue(370f), 0.0001f)
        assertEquals(350f, positiveHue(-10f), 0.0001f)
        assertEquals(0f, positiveHue(360f), 0.0001f)
        assertEquals(180f, positiveHue(-180f), 0.0001f)
    }

    /** Blending and balance are sliders like the others, and have to reach the recipe as such. */
    @Test
    fun blendingAndBalanceAreOrdinarySliders() {
        assertEquals(50f, Adjustment.GRADE_BLENDING.neutral)
        assertEquals(0f, Adjustment.GRADE_BALANCE.neutral)
        val changed = neutral
            .withAdjustment(Adjustment.GRADE_BLENDING, 70f)
            .withAdjustment(Adjustment.GRADE_BALANCE, -20f)
        assertEquals(70f, changed.colorGrading.blending)
        assertEquals(-20f, changed.colorGrading.balance)
        // And they are not local: a mask has no tonal zones of its own to blend.
        assertFalse(Adjustment.GRADE_BLENDING.isLocal())
        assertFalse(Adjustment.GRADE_BALANCE.isLocal())
    }

    @Test
    fun directCurvePointsCanBeAddedMovedAndRemovedWithoutCrossing() {
        val endpoints = listOf(CurvePoint(0, 0), CurvePoint(255, 255))
        val added = endpoints.withAddedCurvePoint(128, 180)
        assertEquals(listOf(0, 128, 255), added.map { it.x })

        val moved = added.withMovedCurvePoint(index = 1, x = 300, y = -20)
        assertEquals(254, moved[1].x)
        assertEquals(0, moved[1].y)

        assertEquals(endpoints, moved.withRemovedCurvePoint(1))
        assertEquals(endpoints, endpoints.withRemovedCurvePoint(0))
    }

    @Test
    fun theEndPointsMoveOnBothAxesWithoutPassingTheirNeighbours() {
        val points = listOf(CurvePoint(0, 0), CurvePoint(128, 128), CurvePoint(255, 255))
        // The black point in to 20 and up to 10, as in Lightroom.
        assertEquals(CurvePoint(20, 10), points.withMovedCurvePoint(index = 0, x = 20, y = 10).first())
        assertEquals(CurvePoint(230, 240), points.withMovedCurvePoint(index = 2, x = 230, y = 240).last())
        // Never past the point beside them, nor out of the range.
        assertEquals(127, points.withMovedCurvePoint(index = 0, x = 200, y = 0).first().x)
        assertEquals(129, points.withMovedCurvePoint(index = 2, x = 40, y = 255).last().x)
        assertEquals(0, points.withMovedCurvePoint(index = 0, x = -30, y = 0).first().x)
        // Two points only: each stops one short of the other.
        val two = listOf(CurvePoint(0, 0), CurvePoint(255, 255))
        assertEquals(254, two.withMovedCurvePoint(index = 0, x = 255, y = 0).first().x)
    }

    @Test
    fun aPointIsOnlyAddedBetweenTheEndPoints() {
        val points = listOf(CurvePoint(30, 0), CurvePoint(220, 255))
        // Outside them the curve is flat on purpose; a tap there adds nothing.
        assertEquals(points, points.withAddedCurvePoint(10, 0))
        assertEquals(points, points.withAddedCurvePoint(240, 255))
        assertEquals(listOf(30, 100, 220), points.withAddedCurvePoint(100, 90).map { it.x })
    }

    @Test
    fun freedEndPointsStayWhereTheyWereFromProcessElevenOn() {
        val freed = EditRecipe(
            assetId = "a",
            originalChecksum = "c",
            createdAt = "2026-10-08T00:00:00Z",
            updatedAt = "2026-10-08T00:00:00Z",
            processVersion = 11,
            toneCurve = ToneCurve(rgb = listOf(CurvePoint(20, 0), CurvePoint(235, 255))),
        )
        assertEquals(listOf(20, 235), freed.editableCurvePoints().map { it.x })
        // Before 11 the corners were always there, drawn even when not stored.
        assertEquals(listOf(0, 20, 235, 255), freed.copy(processVersion = 10).editableCurvePoints().map { it.x })
    }

    @Test
    fun aChannelWithoutItsOwnCurveStartsAsTheStraightLine() {
        val recipe = neutral.copy(toneCurve = ToneCurve(rgb = listOf(CurvePoint(0, 20), CurvePoint(255, 255))))
        assertEquals(listOf(CurvePoint(0, 0), CurvePoint(255, 255)), recipe.editableCurvePoints(CurveChannel.BLUE))
        assertEquals(listOf(CurvePoint(0, 20), CurvePoint(255, 255)), recipe.editableCurvePoints(CurveChannel.RGB))
    }

    @Test
    fun anOlderWhiteBalanceOpensConvertedToTheLightModel() {
        val older = neutral.copy(
            processVersion = 10,
            whiteBalance = WhiteBalance(temp = 26f, tint = 6f),
            toneCurve = ToneCurve(rgb = listOf(CurvePoint(20, 0), CurvePoint(235, 255))),
        )

        val opened = older.openedForEditing()

        assertEquals(CURRENT_PROCESS_VERSION, opened.processVersion)
        assertEquals(lightWhiteBalanceOf(older.whiteBalance), opened.whiteBalance)
        assertTrue("the light model needs less for the same warmth", opened.whiteBalance.temp < 26f)
        // At process 10 the curve was pinned to its corners; opened at 13 it has to say so itself.
        assertEquals(listOf(0, 20, 235, 255), opened.toneCurve.rgb.map { it.x })
    }

    @Test
    fun aRecipeWithoutWhiteBalanceOpensAsItWas() {
        val older = neutral.copy(processVersion = 10, toneCurve = ToneCurve(rgb = listOf(CurvePoint(20, 0), CurvePoint(235, 255))))
        assertEquals(older, older.openedForEditing())
    }

    @Test
    fun anAppPresetIsAppliedWithItsWhiteBalanceConverted() {
        val goldenHour = BUILT_IN_RECIPES.first { it.recipe.whiteBalance != WhiteBalance() }.recipe
        val applied = goldenHour.rebasedOnto(neutral, "2026-10-09T12:00:00Z")
        assertEquals(CURRENT_PROCESS_VERSION, applied.processVersion)
        assertEquals(lightWhiteBalanceOf(goldenHour.whiteBalance), applied.whiteBalance)
    }

    @Test
    fun legacyLinearRecipesRemainStableUntilCurveIsEdited() {
        val points = listOf(CurvePoint(0, 0), CurvePoint(64, 20), CurvePoint(128, 200), CurvePoint(255, 255))
        val legacy = neutral.copy(processVersion = 1, toneCurve = neutral.toneCurve.copy(rgb = points))
        val current = legacy.copy(processVersion = CURRENT_PROCESS_VERSION)

        assertTrue(kotlin.math.abs(legacy.toRenderParameters().toneCurveRgb[32] - current.toRenderParameters().toneCurveRgb[32]) > 0.005f)
        assertEquals(20f / 255f, current.toRenderParameters().toneCurveRgb[64], 0.0001f)
    }

    @Test
    fun reusableRecipeKeepsAdjustmentsButRebasesPhotoIdentity() {
        val source = neutral.withAdjustment(Adjustment.EXPOSURE, 1.25f).copy(
            derivedAssetId = "old-export",
            createdAt = "2025-01-01T00:00:00Z",
        )
        val target = neutral.copy(
            assetId = "asset-2",
            originalChecksum = "checksum-2",
            createdAt = "2026-08-07T10:00:00Z",
        )

        val applied = source.rebasedOnto(target, "2026-08-07T10:01:00Z")

        assertEquals("asset-2", applied.assetId)
        assertEquals("checksum-2", applied.originalChecksum)
        assertEquals(target.createdAt, applied.createdAt)
        assertEquals("2026-08-07T10:01:00Z", applied.updatedAt)
        assertEquals(null, applied.derivedAssetId)
        assertEquals(1.25f, applied.tone.exposure)
        assertEquals(CURRENT_PROCESS_VERSION, applied.processVersion)
    }

    @Test
    fun presenceAndEffectsMapToPreviewParameters() {
        val parameters = neutral
            .withAdjustment(Adjustment.TEXTURE, 40f)
            .withAdjustment(Adjustment.CLARITY, -25f)
            .withAdjustment(Adjustment.DEHAZE, 60f)
            .withAdjustment(Adjustment.VIGNETTE_AMOUNT, -50f)
            .withAdjustment(Adjustment.VIGNETTE_MIDPOINT, 30f)
            .withAdjustment(Adjustment.VIGNETTE_ROUNDNESS, -80f)
            .withAdjustment(Adjustment.VIGNETTE_FEATHER, 70f)
            .withAdjustment(Adjustment.GRAIN_AMOUNT, 35f)
            .withAdjustment(Adjustment.GRAIN_SIZE, 60f)
            .withAdjustment(Adjustment.GRAIN_ROUGHNESS, 20f)
            .toRenderParameters()

        assertEquals(40f, parameters.texture)
        assertEquals(-25f, parameters.clarity)
        assertEquals(60f, parameters.dehaze)
        assertEquals(-50f, parameters.vignetteAmount)
        assertEquals(30f, parameters.vignetteMidpoint)
        assertEquals(-80f, parameters.vignetteRoundness)
        assertEquals(70f, parameters.vignetteFeather)
        assertEquals(35f, parameters.grainAmount)
        assertEquals(60f, parameters.grainSize)
        assertEquals(20f, parameters.grainRoughness)
    }

    @Test
    fun onlyTheNewNeighbourhoodToolsAskForTheBlurPyramid() {
        // The pyramid is the most expensive step of the frame; a light or colour adjustment cannot ask for it.
        assertFalse(neutral.withAdjustment(Adjustment.EXPOSURE, 1f).toRenderParameters().needsDetailStage)
        assertFalse(neutral.withAdjustment(Adjustment.VIGNETTE_AMOUNT, -50f).toRenderParameters().needsDetailStage)
        assertTrue(neutral.withAdjustment(Adjustment.TEXTURE, 10f).toRenderParameters().needsDetailStage)
        assertTrue(neutral.withAdjustment(Adjustment.CLARITY, 10f).toRenderParameters().needsDetailStage)
        assertTrue(neutral.withAdjustment(Adjustment.DEHAZE, 10f).toRenderParameters().needsDetailStage)

        // The dark channel statistics are dehaze's alone.
        assertFalse(neutral.withAdjustment(Adjustment.CLARITY, 10f).toRenderParameters().needsDehazeStats)
        assertTrue(neutral.withAdjustment(Adjustment.DEHAZE, 10f).toRenderParameters().needsDehazeStats)
    }

    @Test
    fun dependentSlidersNameTheAdjustmentThatSwitchesThemOn() {
        assertEquals(Adjustment.VIGNETTE_AMOUNT, Adjustment.VIGNETTE_MIDPOINT.parent())
        assertEquals(Adjustment.VIGNETTE_AMOUNT, Adjustment.VIGNETTE_ROUNDNESS.parent())
        assertEquals(Adjustment.VIGNETTE_AMOUNT, Adjustment.VIGNETTE_FEATHER.parent())
        assertEquals(Adjustment.GRAIN_AMOUNT, Adjustment.GRAIN_SIZE.parent())
        assertEquals(Adjustment.GRAIN_AMOUNT, Adjustment.GRAIN_ROUGHNESS.parent())
        assertEquals(null, Adjustment.VIGNETTE_AMOUNT.parent())
        assertEquals(null, Adjustment.EXPOSURE.parent())
        // A parent always has to be neutral at zero, or it would never turn its children off.
        Adjustment.entries.mapNotNull { it.parent() }.distinct().forEach { parent ->
            assertEquals(0f, parent.neutral)
        }
    }

    @Test
    fun geometryMapsToPreviewParameters() {
        val parameters = neutral.copy(
            geometry = neutral.geometry.copy(
                cropRect = eu.studio742.imago.core.model.CropRect(0.1f, 0.2f, 0.7f, 0.6f),
                straighten = -12.5f,
                rotation = 270,
                mirrorH = true,
                mirrorV = true,
            ),
        ).toRenderParameters()

        assertEquals(0.1f, parameters.cropX)
        assertEquals(0.2f, parameters.cropY)
        assertEquals(0.7f, parameters.cropWidth)
        assertEquals(0.6f, parameters.cropHeight)
        assertEquals(-12.5f, parameters.straighten)
        assertEquals(270, parameters.rotation)
        assertTrue(parameters.mirrorH)
        assertTrue(parameters.mirrorV)
    }

    @Test
    fun preGeometryRecipesIgnorePreviouslyDormantGeometryFields() {
        val parameters = neutral.copy(
            processVersion = 3,
            geometry = neutral.geometry.copy(rotation = 90, mirrorH = true),
        ).toRenderParameters()

        assertEquals(0, parameters.rotation)
        assertEquals(false, parameters.mirrorH)
    }

    @Test
    fun preCropRecipesIgnorePreviouslyDormantCropFields() {
        val parameters = neutral.copy(
            processVersion = 5,
            geometry = neutral.geometry.copy(
                cropRect = eu.studio742.imago.core.model.CropRect(0.1f, 0.2f, 0.7f, 0.6f),
            ),
        ).toRenderParameters()

        assertEquals(0f, parameters.cropX)
        assertEquals(0f, parameters.cropY)
        assertEquals(1f, parameters.cropWidth)
        assertEquals(1f, parameters.cropHeight)
    }

    @Test
    fun preStraightenRecipesIgnorePreviouslyDormantFineRotation() {
        val parameters = neutral.copy(
            processVersion = 6,
            geometry = neutral.geometry.copy(straighten = 18f),
        ).toRenderParameters()

        assertEquals(0f, parameters.straighten)
    }
}

/** A distinct value per component, so no test passes because they were swapped. */
private fun testValue(component: ColorGradeComponent): Float = when (component) {
    ColorGradeComponent.HUE -> 200f
    ColorGradeComponent.SATURATION -> 35f
    ColorGradeComponent.LUMINANCE -> -15f
}

private fun ColorWheel.withTestValue(component: ColorGradeComponent): ColorWheel = when (component) {
    ColorGradeComponent.HUE -> copy(hue = testValue(component))
    ColorGradeComponent.SATURATION -> copy(saturation = testValue(component))
    ColorGradeComponent.LUMINANCE -> copy(luminance = testValue(component))
}
