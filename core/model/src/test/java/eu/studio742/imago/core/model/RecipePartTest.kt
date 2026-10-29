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

    @Test
    fun namesFromANewerAppAreSkipped() {
        assertEquals(setOf(RecipePart.CROP, RecipePart.GRAIN), RecipePart.parse(listOf("CROP", "LENS_PROFILE", "GRAIN")))
    }

    private fun mask(id: String) = LocalMask(id = id, name = id)
}
