package eu.studio742.imago.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipePartTest {
    private val target = EditRecipe(
        assetId = "target",
        originalChecksum = "target-sum",
        createdAt = "t0",
        updatedAt = "t0",
        tone = Tone(exposure = -1f, contrast = 10f),
        geometry = Geometry(cropRect = CropRect(0.1f, 0.1f, 0.5f, 0.5f), straighten = 3f, aspectLock = "1:1"),
        masks = listOf(mask("target-mask")),
    )
    private val source = EditRecipe(
        assetId = "source",
        originalChecksum = "source-sum",
        createdAt = "s0",
        updatedAt = "s0",
        whiteBalance = WhiteBalance(temp = 20f, tint = -5f),
        tone = Tone(exposure = 1.5f, contrast = -20f, highlights = -40f),
        presence = Presence(texture = 12f, vibrance = 30f),
        toneCurve = ToneCurve(rgb = listOf(CurvePoint(0, 20), CurvePoint(255, 235))),
        hsl = Hsl(blue = HslBand(saturation = -50f)),
        colorGrading = ColorGrading(shadows = ColorWheel(hue = 200f, saturation = 15f)),
        effects = Effects(vignetteAmount = -30f, vignetteFeather = 80f, grainAmount = 25f),
        geometry = Geometry(
            cropRect = CropRect(0f, 0f, 0.8f, 0.6f),
            straighten = -7f,
            rotation = 90,
            mirrorH = true,
            aspectLock = "4:3",
            perspective = Perspective(vertical = 40f),
        ),
        masks = listOf(mask("source-mask")),
    )

    @Test
    fun everyPartTakesTheWholeLookButNotThePhoto() {
        val applied = target.withPartsFrom(source, RecipePart.entries.toSet())

        assertEquals(source.copy(assetId = "target", originalChecksum = "target-sum", createdAt = "t0", updatedAt = "t0"), applied)
    }

    @Test
    fun theLookLeavesTheFrameAndTheMasksAlone() {
        val applied = target.withPartsFrom(source, RecipePart.LOOK)

        assertEquals(source.tone, applied.tone)
        assertEquals(source.whiteBalance, applied.whiteBalance)
        assertEquals(source.toneCurve, applied.toneCurve)
        assertEquals(source.hsl, applied.hsl)
        assertEquals(source.colorGrading, applied.colorGrading)
        assertEquals(source.effects, applied.effects)
        assertEquals(target.geometry, applied.geometry)
        assertEquals(target.masks, applied.masks)
    }

    @Test
    fun noPartChangesNothing() {
        assertEquals(target, target.withPartsFrom(source, emptySet()))
    }

    @Test
    fun eachSliderGoesOnItsOwn() {
        val applied = target.withPartsFrom(source, setOf(RecipePart.EXPOSURE, RecipePart.VIBRANCE))

        assertEquals(1.5f, applied.tone.exposure)
        assertEquals(10f, applied.tone.contrast)
        assertEquals(0f, applied.tone.highlights)
        assertEquals(30f, applied.presence.vibrance)
        assertEquals(0f, applied.presence.texture)
        assertEquals(target.whiteBalance, applied.whiteBalance)
    }

    @Test
    fun theCropCarriesItsAspectLockAndNothingElseOfTheGeometry() {
        val applied = target.withPartsFrom(source, setOf(RecipePart.CROP))

        assertEquals(source.geometry.cropRect, applied.geometry.cropRect)
        assertEquals("4:3", applied.geometry.aspectLock)
        assertEquals(3f, applied.geometry.straighten)
        assertEquals(0, applied.geometry.rotation)
        assertFalse(applied.geometry.mirrorH)
        assertEquals(Perspective(), applied.geometry.perspective)
    }

    @Test
    fun theDefaultsKeepGeometryAndMasksOut() {
        assertTrue(RecipePart.GEOMETRY.none { it in RecipePart.LOOK })
        assertFalse(RecipePart.MASKS in RecipePart.LOOK)
        assertTrue(RecipePart.MASKS in RecipePart.LEGACY)
        assertTrue(RecipePart.GEOMETRY.none { it in RecipePart.LEGACY })
        assertFalse(RecipePart.REMOVE in RecipePart.LOOK)
        assertFalse(RecipePart.REMOVE in RecipePart.LEGACY)
        assertFalse(RecipePart.REMOVE in RecipePart.BUILT_IN)
    }

    @Test
    fun theCorrectionsTravelOnlyWhenAskedFor() {
        val corrections = listOf(Retouch(id = "dust", mode = RetouchMode.HEAL, strokes = listOf(BrushStroke(listOf(.2f, .3f), .01f))))
        val withDust = source.copy(retouches = corrections)
        assertEquals(target.retouches, target.withPartsFrom(withDust, RecipePart.LOOK).retouches)
        assertEquals(corrections, target.withPartsFrom(withDust, setOf(RecipePart.REMOVE)).retouches)
    }

    @Test
    fun aRecipeSavedBeforeTheChoiceLeavesTheGeometryBehind() {
        val saved = SavedRecipe(id = "r", name = "Old", collection = "", recipe = source, createdAt = "", updatedAt = "")

        assertEquals(RecipePart.LEGACY, saved.appliedParts)
        assertEquals(setOf(RecipePart.CROP), saved.copy(parts = setOf(RecipePart.CROP)).appliedParts)
    }

    private val finished = source.copy(
        detail = Detail(sharpenAmount = 70f, sharpenRadius = 2f, noiseReductionLuminance = 30f, noiseReductionColor = 60f),
        calibration = Calibration(redHue = 15f),
        lens = LensCorrection(vignetteAmount = 20f, purpleAmount = 40f),
    )

    @Test
    fun theFinishingGoesWithTheLookPartByPart() {
        val look = target.withPartsFrom(finished, RecipePart.LOOK)
        assertEquals(finished.detail, look.detail)
        assertEquals(finished.calibration, look.calibration)
        assertEquals(finished.lens, look.lens)

        val sharpening = target.withPartsFrom(finished, setOf(RecipePart.SHARPENING))
        assertEquals(70f, sharpening.detail.sharpenAmount)
        assertEquals(2f, sharpening.detail.sharpenRadius)
        assertEquals(0f, sharpening.detail.noiseReductionLuminance)
        assertEquals(25f, sharpening.detail.noiseReductionColor)

        val colorNoise = target.withPartsFrom(finished, setOf(RecipePart.COLOR_NOISE_REDUCTION))
        assertEquals(60f, colorNoise.detail.noiseReductionColor)
        assertEquals(0f, colorNoise.detail.noiseReductionLuminance)

        val defringe = target.withPartsFrom(finished, setOf(RecipePart.DEFRINGE))
        assertEquals(40f, defringe.lens.purpleAmount)
        assertEquals(0f, defringe.lens.vignetteAmount)
    }

    @Test
    fun theAppsFiltersLeaveTheFinishingOfThePhoto() {
        val sharpened = target.copy(detail = Detail(sharpenAmount = 40f))
        assertEquals(40f, sharpened.withPartsFrom(source, RecipePart.BUILT_IN).detail.sharpenAmount)
    }

    private fun savedWith(recipe: EditRecipe, parts: Set<RecipePart>?) =
        SavedRecipe(id = "r", name = "R", collection = "", recipe = recipe, createdAt = "", updatedAt = "", parts = parts)

    @Test
    fun aRecipeFromBeforeTheFinishingCarriesWhatItHolds() {
        val held = savedWith(finished.copy(lens = LensCorrection()), RecipePart.LOOK - RecipePart.FINISHING).appliedParts

        assertTrue(RecipePart.SHARPENING in held)
        assertTrue(RecipePart.CALIBRATION in held)
        assertFalse(RecipePart.LENS_VIGNETTE in held)
        assertTrue(RecipePart.SHARPENING in savedWith(finished, null).appliedParts)
    }

    @Test
    fun aRecipeWithTheDefaultFinishingLeavesThePhotoAlone() {
        assertEquals(RecipePart.LEGACY, savedWith(source, null).appliedParts)
    }

    @Test
    fun finishingStoredBeforeItWasReadWasNeverChosen() {
        assertEquals(RecipePart.LEGACY, savedWith(finished.copy(processVersion = 20), null).appliedParts)
    }

    @Test
    fun aChoiceThatNamesTheFinishingIsKeptAsItIs() {
        val chosen = setOf(RecipePart.EXPOSURE, RecipePart.SHARPENING)
        assertEquals(chosen, savedWith(finished, chosen).appliedParts)
    }

    @Test
    fun uncheckingAllTheFinishingSticks() {
        val parts = RecipePart.LOOK - RecipePart.FINISHING
        val saved = savedWith(finished.storedForParts(parts), parts)

        assertEquals(parts, saved.appliedParts)
        assertEquals(finished, finished.storedForParts(setOf(RecipePart.DEFRINGE)))
        assertEquals(finished, finished.storedForParts(null))
    }

    @Test
    fun namesFromANewerAppAreSkipped() {
        assertEquals(setOf(RecipePart.CROP, RecipePart.GRAIN), RecipePart.parse(listOf("CROP", "LENS_PROFILE", "GRAIN")))
    }

    private fun mask(id: String) = LocalMask(id = id, name = id)
}
