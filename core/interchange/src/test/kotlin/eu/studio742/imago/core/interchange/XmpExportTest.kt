package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.Calibration
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Effects
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Hsl
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.KelvinTint
import eu.studio742.imago.core.model.LensCorrection
import eu.studio742.imago.core.model.LinearMask
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.Presence
import eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME
import eu.studio742.imago.core.model.RAW_PROFILE_NEUTRAL
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.WhiteBalance
import eu.studio742.imago.core.model.withPartsFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmpExportTest {
    private val blank = EditRecipe(assetId = "", originalChecksum = "", createdAt = "", updatedAt = "")

    private val full = blank.copy(
        whiteBalance = WhiteBalance(temp = 12f, tint = -7f),
        tone = Tone(exposure = 0.45f, contrast = 12f, highlights = -30f, shadows = 41f, whites = 5f, blacks = -9f),
        presence = Presence(texture = 6f, clarity = 17f, dehaze = -4f, vibrance = 22f, saturation = -3f),
        toneCurve = ToneCurve(
            rgb = listOf(CurvePoint(0, 10), CurvePoint(90, 80), CurvePoint(255, 250)),
            red = listOf(CurvePoint(0, 0), CurvePoint(128, 140), CurvePoint(255, 255)),
        ),
        hsl = Hsl(red = HslBand(5f, -6f, 7f), aqua = HslBand(-20f, 31f, -82f), magenta = HslBand(1f, 2f, 3f)),
        colorGrading = ColorGrading(
            shadows = ColorWheel(210f, 12f, -5f),
            midtones = ColorWheel(56f, 17f, 3f),
            highlights = ColorWheel(40f, 20f, 8f),
            global = ColorWheel(300f, 4f, -2f),
            blending = 62f,
            balance = -15f,
        ),
        detail = Detail(
            sharpenAmount = 60f, sharpenRadius = 1.3f, sharpenDetail = 30f, sharpenMasking = 20f,
            noiseReductionLuminance = 15f, noiseReductionLuminanceDetail = 40f, noiseReductionContrast = 10f,
            noiseReductionColor = 35f, noiseReductionColorDetail = 45f, noiseReductionColorSmoothness = 55f,
        ),
        effects = Effects(
            vignetteAmount = -16f, vignetteMidpoint = 40f, vignetteRoundness = 10f, vignetteFeather = 60f,
            grainAmount = 9f, grainSize = 30f, grainRoughness = 45f,
        ),
        calibration = Calibration(1f, 2f, 3f, 4f, 5f, 6f, 7f),
        lens = LensCorrection(vignetteAmount = 11f, vignetteMidpoint = 44f, purpleAmount = 8f, greenAmount = 3f),
        profile = RAW_PROFILE_NEUTRAL,
    )

    private fun saved(recipe: EditRecipe, parts: Set<RecipePart>, name: String = "Warm", collection: String = "Summer") =
        SavedRecipe(id = "id", name = name, collection = collection, recipe = recipe, createdAt = "", updatedAt = "", parts = parts)

    private fun export(recipe: SavedRecipe) = encodeXmpPreset(recipe, "IMAGO")

    private fun reread(export: XmpExport): ImagoFileEntry.Ready =
        (decodeRecipeFile(export.fileName, export.bytes) as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.Ready

    private fun text(export: XmpExport) = export.bytes.decodeToString()

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/xmp/$name.xmp")) { name }.use { it.readBytes() }

    @Test
    fun `every look part comes back the same through a preset file`() {
        val export = export(saved(full, RecipePart.LOOK))
        val back = reread(export)

        // A LUT is data that a preset file has no field for: it is said out loud and not written.
        val inAFile = RecipePart.LOOK - RecipePart.LUT
        assertEquals(blank.withPartsFrom(full, inAFile), back.recipe)
        assertEquals(inAFile, back.parts)
        assertEquals("Warm", back.name)
        assertEquals("Summer", back.collection)
        assertEquals(emptyList<ImportGap>(), back.gaps)
        assertEquals(emptySet<XmpLeftOut>(), export.leftOut)
    }

    @Test
    fun `a preset imported and exported reads back the same, for every real one`() {
        listOf("Açores", "Cesar Geres", "César Leve (Brancos)", "DJI city", "Green Landscape").forEach { name ->
            val first = (decodeRecipeFile("$name.xmp", fixture(name)) as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.Ready
            val back = reread(export(saved(first.recipe, first.parts, name = first.name)))
            assertEquals(name, first.recipe, back.recipe)
            assertEquals(name, first.parts, back.parts)
        }
    }

    @Test
    fun `a file holds only the parts the recipe applies`() {
        // Sharpening, noise, calibration and lens at their defaults: a recipe with values there carries them regardless.
        val look = full.copy(detail = Detail(), calibration = Calibration(), lens = LensCorrection())
        val export = export(saved(look, setOf(RecipePart.VIBRANCE, RecipePart.EXPOSURE)))
        val written = text(export)

        assertTrue(written.contains("""crs:Vibrance="+22""""))
        assertTrue(written.contains("""crs:Exposure2012="+0.45""""))
        listOf("Contrast2012", "Sharpness", "ToneCurvePV2012", "WhiteBalance", "HueAdjustmentRed", "CameraProfile", "SplitToning")
            .forEach { assertFalse(it, written.contains("crs:$it")) }
        assertEquals(setOf(RecipePart.VIBRANCE, RecipePart.EXPOSURE), reread(export).parts)
    }

    @Test
    fun `a light stays a light and the choices stay choices`() {
        fun balance(value: WhiteBalance) = reread(export(saved(blank.copy(whiteBalance = value), setOf(RecipePart.WHITE_BALANCE)))).recipe.whiteBalance

        assertEquals(WhiteBalance(light = KelvinTint(5600f, 12f)), balance(WhiteBalance(temp = 30f, tint = 4f, light = KelvinTint(5600f, 12f))))
        assertEquals(WhiteBalance(auto = true), balance(WhiteBalance(auto = true)))
        assertEquals(WhiteBalance(), balance(WhiteBalance()))
        assertEquals(WhiteBalance(temp = -8f, tint = 3f), balance(WhiteBalance(temp = -8f, tint = 3f)))
    }

    @Test
    fun `monochrome is written as black and white, as real presets do`() {
        val export = export(saved(blank.copy(profile = RAW_PROFILE_MONOCHROME), setOf(RecipePart.PROFILE)))
        val written = text(export)

        assertTrue(written.contains("""crs:ConvertToGrayscale="True""""))
        assertTrue(written.contains("""crs:CameraProfile="Default Monochrome""""))
        assertEquals(RAW_PROFILE_MONOCHROME, reread(export).recipe.profile)
    }

    @Test
    fun `what a preset cannot hold is named and kept out of the file`() {
        val mask = LocalMask(id = "m", components = listOf(MaskComponent(shape = MaskShape.LINEAR, linear = LinearMask())))
        val recipe = blank.copy(
            masks = listOf(mask),
            geometry = Geometry(cropRect = CropRect(0.1f, 0.1f, 0.5f, 0.5f)),
            lens = LensCorrection(purpleAmount = 10f, purpleRange = 80f),
        )
        val export = export(saved(recipe, setOf(RecipePart.MASKS, RecipePart.CROP, RecipePart.DEFRINGE, RecipePart.VIBRANCE)))

        assertEquals(setOf(XmpLeftOut.MASKS, XmpLeftOut.GEOMETRY, XmpLeftOut.DEFRINGE_RANGE), export.leftOut)
        val back = reread(export)
        assertFalse(RecipePart.MASKS in back.parts)
        assertFalse(RecipePart.CROP in back.parts)
    }

    @Test
    fun `a part with nothing in it is not reported as left out`() {
        assertEquals(emptySet<XmpLeftOut>(), export(saved(blank, setOf(RecipePart.MASKS, RecipePart.CROP, RecipePart.VIBRANCE))).leftOut)
    }

    @Test
    fun `a name cannot break out of its element`() {
        val name = """A <b>&"quoted" 'x'"""
        val export = export(saved(blank, setOf(RecipePart.VIBRANCE), name = name))
        assertEquals(name, reread(export).name)
    }

    @Test
    fun `the photo and its dates stay out of the file`() {
        val recipe = full.copy(assetId = "photo-1234", originalChecksum = "checksum-5678", derivedAssetId = "derived-9", createdAt = "2026-01-02", updatedAt = "2026-03-04")
        val written = text(export(saved(recipe, RecipePart.LOOK)))
        listOf("photo-1234", "checksum-5678", "derived-9", "2026-01-02", "2026-03-04").forEach { assertFalse(it, written.contains(it)) }
    }

    @Test
    fun `the same recipe gives the same file`() {
        val recipe = saved(full, RecipePart.LOOK)
        assertEquals(text(export(recipe)), text(export(recipe)))
    }

    @Test
    fun `the file is named after the recipe, and a re-import counts as coming from outside`() {
        val export = export(saved(full, RecipePart.LOOK, name = "Dusk: blue/gold"))
        assertEquals("Dusk_ blue_gold.xmp", export.fileName)
        assertEquals(RecipeOrigin.XMP, reread(export).origin)
    }
}
