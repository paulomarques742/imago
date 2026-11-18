package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * No real `.lrtemplate` is in the corpus yet, so these files are written from the format's grammar: a
 * `s = { … }` table with the preset's settings under `value.settings`, named as the XMP fields are.
 */
class LrTemplateTest {
    private fun template(settings: String, title: String = "Warm Sun"): ByteArray = """
        s = {
        	id = "00000000-0000-0000-0000-000000000000",
        	internalName = "$title",
        	title = "$title",
        	type = "Develop",
        	value = {
        		settingsID = "11111111-1111-1111-1111-111111111111",
        		uuid = "22222222-2222-2222-2222-222222222222",
        		settings = {
        $settings
        		},
        	},
        	version = 0,
        }
    """.trimIndent().encodeToByteArray()

    private fun ready(bytes: ByteArray, name: String = "Warm Sun.lrtemplate") =
        (decodeRecipeFile(name, bytes) as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.Ready

    private fun xmp(attributes: String): ByteArray = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" $attributes/>
        </rdf:RDF></x:xmpmeta>
    """.trimIndent().encodeToByteArray()

    @Test
    fun `a template gives the same recipe as the xmp with the same settings`() {
        val fromTemplate = ready(
            template(
                """
                Exposure2012 = 0.5,
                Contrast2012 = 25,
                Vibrance = -5,
                Saturation = 12,
                ProcessVersion = "11.0",
                ConvertToGrayscale = false,
                """.trimIndent(),
            ),
        )
        val fromXmp = (decodeRecipeFile("x.xmp", xmp("""crs:Exposure2012="+0.50" crs:Contrast2012="+25" crs:Vibrance="-5" crs:Saturation="+12"""")) as ImagoFileContent.Recipes)
            .entries.single() as ImagoFileEntry.Ready
        assertEquals(fromXmp.recipe.tone, fromTemplate.recipe.tone)
        assertEquals(fromXmp.recipe.presence, fromTemplate.recipe.presence)
        assertEquals(fromXmp.parts, fromTemplate.parts)
        assertEquals(RecipeOrigin.LRTEMPLATE, fromTemplate.origin)
        assertEquals("Warm Sun", fromTemplate.name)
    }

    @Test
    fun `a curve written as a flat run of numbers becomes points`() {
        val curve = ready(template("ToneCurvePV2012 = { 0, 20, 128, 140, 255, 235 },")).recipe.toneCurve.rgb
        assertEquals(listOf(CurvePoint(0, 20), CurvePoint(128, 140), CurvePoint(255, 235)), curve)
        assertTrue(RecipePart.TONE_CURVE in ready(template("ToneCurvePV2012 = { 0, 20, 255, 235 },")).parts)
    }

    @Test
    fun `comments, a byte order mark and keys in brackets are read`() {
        val text = "﻿-- exported by a long time ago\n" + String(template("[\"Exposure2012\"] = 1, --[[ a block\ncomment ]] Contrast2012 = 10,")).replace("title = ", "-- note\n\ttitle = ")
        val entry = ready(text.encodeToByteArray())
        assertEquals(1f, entry.recipe.tone.exposure)
        assertEquals(10f, entry.recipe.tone.contrast)
    }

    @Test
    fun `masks are named in the report like in an xmp`() {
        val content = decodeRecipeFile(
            "Sky.lrtemplate",
            template("HasSettings = true,\nMaskGroupBasedCorrections = { { What = \"Mask/Group\", CorrectionName = \"Sky\" } },", "Sky"),
        )
        val entry = (content as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.NothingApplies
        assertEquals(listOf(ImportGap.Left(ImportedSetting.MASKS, ImportGapReason.NOT_IMPORTED)), entry.gaps)
    }

    @Test
    fun `a template inside a zip enters beside the xmp files and is no longer skipped`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("Pack/Old.lrtemplate")); zip.write(template("Exposure2012 = 0.25,", "Old")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("Pack/New.xmp")); zip.write(xmp("""crs:Exposure2012="+0.25"""")); zip.closeEntry()
        }
        val content = decodeRecipeFile("Pack.zip", out.toByteArray()) as ImagoFileContent.Recipes
        assertEquals(2, content.entries.size)
        assertEquals(0, content.skippedFiles)
        val old = content.entries[0] as ImagoFileEntry.Ready
        assertEquals("Pack", old.collection)
        assertEquals(RecipeOrigin.LRTEMPLATE, old.origin)
    }

    @Test
    fun `anything that is more than data is refused, and nothing runs`() {
        listOf(
            "s = { value = { settings = { Exposure2012 = os.execute(\"x\") } } }",
            "s = { value = { settings = { Exposure2012 = 1 + 1 } } }",
            "s = { value = { settings = { Exposure2012 = badName } } }",
            "s = { value = { settings = { Exposure2012 = 1 } } } trailing()",
            "s = { value = { settings = { Exposure2012 = \"unterminated } } }",
            "s = { value = { settings = {",
        ).forEach { source ->
            assertEquals(source, ImagoFileContent.Unreadable, decodeRecipeFile("x.lrtemplate", source.encodeToByteArray()))
        }
    }

    @Test
    fun `a table nested too deep is refused instead of overflowing the stack`() {
        val deep = "s = { value = { settings = { Nested = " + "{".repeat(5000) + "}".repeat(5000) + " } } }"
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.lrtemplate", deep.encodeToByteArray()))
    }

    @Test
    fun `a file that is not a template is not read as one`() {
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.lrtemplate", "hello settings".encodeToByteArray()))
    }
}
