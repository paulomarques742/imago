package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.HslBand
import eu.studio742.imago.core.model.KelvinTint
import eu.studio742.imago.core.model.RAW_PROFILE_COLOR
import eu.studio742.imago.core.model.RAW_PROFILE_MONOCHROME
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XmpPresetTest {
    private val corpus = listOf("Açores", "Cesar Geres", "César Leve (Brancos)", "DJI city", "Green Landscape")

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/xmp/$name.xmp")) { name }.use { it.readBytes() }

    private fun ready(content: ImagoFileContent, index: Int = 0): ImagoFileEntry.Ready =
        (content as ImagoFileContent.Recipes).entries[index] as ImagoFileEntry.Ready

    private fun preset(name: String) = ready(decodeRecipeFile("$name.xmp", fixture(name)))

    private fun packet(attributes: String, body: String = ""): ByteArray = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" $attributes>$body</rdf:Description>
        </rdf:RDF></x:xmpmeta>
    """.trimIndent().encodeToByteArray()

    private fun written(attributes: String, body: String = "") = ready(decodeRecipeFile("x.xmp", packet(attributes, body)))

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The smallest TIFF that holds an XMP packet in tag 700. */
    private fun dngWith(packet: ByteArray, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): ByteArray {
        val buffer = ByteBuffer.allocate(8 + 2 + 12 + 4 + packet.size).order(order)
        buffer.put(if (order == ByteOrder.LITTLE_ENDIAN) "II".encodeToByteArray() else "MM".encodeToByteArray())
        buffer.putShort(42).putInt(8)
        buffer.putShort(1)
        buffer.putShort(700).putShort(1).putInt(packet.size).putInt(26)
        buffer.putInt(0)
        buffer.put(packet)
        return buffer.array()
    }

    @Test
    fun `every preset of the corpus imports, named as in the file and marked as coming from outside`() {
        corpus.forEach { name ->
            val entry = preset(name)
            assertEquals(name, entry.name)
            assertEquals(RecipeOrigin.XMP, entry.origin)
            assertTrue(name, entry.recipe.isSound())
            assertFalse(name, RecipePart.MASKS in entry.parts)
        }
    }

    @Test
    fun `the sliders of a real preset are read as they are`() {
        val entry = preset("Açores")
        val recipe = entry.recipe
        assertEquals(6f, recipe.whiteBalance.temp)
        assertEquals(10f, recipe.whiteBalance.tint)
        assertNull(recipe.whiteBalance.light)
        assertEquals(14f, recipe.tone.contrast)
        assertEquals(27f, recipe.presence.clarity)
        assertEquals(20f, recipe.presence.dehaze)
        assertEquals(6f, recipe.presence.texture)
        assertEquals(15f, recipe.presence.vibrance)
        assertEquals(HslBand(hue = 17f, saturation = 19f, luminance = -17f), recipe.hsl.green)
        assertEquals(-16f, recipe.effects.vignetteAmount)
        assertEquals(emptyList<ImportGap>(), entry.gaps)
    }

    @Test
    fun `the point curve keeps its points and an identity channel is no curve`() {
        val curve = preset("Açores").recipe.toneCurve
        assertEquals(listOf(CurvePoint(0, 10), CurvePoint(77, 57), CurvePoint(153, 159), CurvePoint(255, 255)), curve.rgb)
        assertNull(curve.red)
        assertNull(curve.green)
        assertNull(curve.blue)
        assertEquals(CurvePoint(16, 23), preset("DJI city").recipe.toneCurve.rgb.first())
    }

    @Test
    fun `the parts are the groups the file brings, and an absent group stays the photo's`() {
        val light = preset("César Leve (Brancos)")
        assertFalse(RecipePart.DEFRINGE in light.parts)
        assertTrue(RecipePart.SHARPENING in light.parts)
        assertTrue(RecipePart.GRAIN in light.parts)
        assertEquals(15f, light.recipe.detail.sharpenAmount)
        assertEquals(9f, light.recipe.effects.grainAmount)
        assertTrue(RecipePart.DEFRINGE in preset("Açores").parts)
    }

    @Test
    fun `a default colour profile is the colour profile and an embedded one is left to the photo`() {
        val named = preset("Açores")
        assertTrue(RecipePart.PROFILE in named.parts)
        assertEquals(RAW_PROFILE_COLOR, named.recipe.profile)
        val embedded = preset("Cesar Geres")
        assertFalse(RecipePart.PROFILE in embedded.parts)
        assertEquals(emptyList<ImportGap>(), embedded.gaps.filterIsInstance<ImportGap.Left>().filter { it.setting == ImportedSetting.CAMERA_PROFILE })
    }

    @Test
    fun `split toning from before the wheels draws as a blending of 100`() {
        val grading = preset("César Leve (Brancos)").recipe.colorGrading
        assertEquals(ColorWheel(hue = 62f, saturation = 9f), grading.shadows)
        assertEquals(ColorWheel(hue = 242f, saturation = 13f), grading.highlights)
        assertEquals(100f, grading.blending)
    }

    @Test
    fun `colour grading reads the old names for shadows and highlights and the new ones for the rest`() {
        val grading = preset("DJI city").recipe.colorGrading
        assertEquals(ColorWheel(hue = 7f, saturation = 17f), grading.shadows)
        assertEquals(ColorWheel(hue = 197f, saturation = 27f), grading.highlights)
        assertEquals(ColorWheel(hue = 56f, saturation = 17f), grading.midtones)
        assertEquals(50f, grading.blending)
    }

    @Test
    fun `a parametric curve becomes points and the report calls it approximated`() {
        val entry = preset("Green Landscape")
        val points = entry.recipe.toneCurve.rgb
        assertTrue(ImportGap.Left(ImportedSetting.PARAMETRIC_CURVE, ImportGapReason.APPROXIMATED) in entry.gaps)
        assertEquals(CurvePoint(0, 0), points.first())
        assertEquals(CurvePoint(255, 255), points.last())
        val shadows = points.first { it.x == 32 }
        assertTrue("$shadows", shadows.y < shadows.x)
        assertTrue(points.zipWithNext().all { (a, b) -> a.y <= b.y })
    }

    @Test
    fun `a parametric slider at its limit does not flatten the end of the curve`() {
        val dark = written("""crs:ParametricShadows="-100"""").recipe.toneCurve.rgb.first { it.x == 32 }
        val bright = written("""crs:ParametricHighlights="+100"""").recipe.toneCurve.rgb.first { it.x == 223 }
        assertEquals(CurvePoint(32, 13), dark)
        assertEquals(CurvePoint(223, 242), bright)
    }

    @Test
    fun `a parametric curve stacks on the point curve and keeps its points`() {
        val curve = written(
            """crs:ParametricLights="+40"""",
            "<crs:ToneCurvePV2012><rdf:Seq><rdf:li>0, 20</rdf:li><rdf:li>255, 235</rdf:li></rdf:Seq></crs:ToneCurvePV2012>",
        ).recipe.toneCurve.rgb
        assertEquals(CurvePoint(0, 20), curve.first())
        assertEquals(CurvePoint(255, 235), curve.last())
        assertTrue(curve.size > 2)
    }

    @Test
    fun `an absolute white balance is kept only as the light`() {
        val balance = written("""crs:WhiteBalance="Custom" crs:Temperature="5600" crs:Tint="+12"""").recipe.whiteBalance
        assertEquals(KelvinTint(5600f, 12f), balance.light)
        assertEquals(0f, balance.temp)
        assertEquals(0f, balance.tint)
    }

    @Test
    fun `as shot and auto stay choices`() {
        assertTrue(written("""crs:WhiteBalance="As Shot"""").recipe.whiteBalance.isAsShot)
        assertTrue(written("""crs:WhiteBalance="As Shot"""").parts.contains(RecipePart.WHITE_BALANCE))
        assertTrue(written("""crs:WhiteBalance="Auto"""").recipe.whiteBalance.auto)
    }

    @Test
    fun `black and white is the monochrome profile and no saturation, and the mix is reported`() {
        val entry = written("""crs:ConvertToGrayscale="True" crs:Saturation="+30" crs:GrayMixerRed="-12"""")
        assertEquals(RAW_PROFILE_MONOCHROME, entry.recipe.profile)
        assertEquals(-100f, entry.recipe.presence.saturation)
        assertTrue(RecipePart.PROFILE in entry.parts && RecipePart.SATURATION in entry.parts)
        assertTrue(ImportGap.Left(ImportedSetting.GRAY_MIXER, ImportGapReason.NOT_IN_APP) in entry.gaps)
    }

    @Test
    fun `a black and white preset is not reported for the colour profile it replaces`() {
        val entry = written("""crs:ConvertToGrayscale="True" crs:CameraProfile="Default Color"""")
        assertEquals(emptyList<ImportGap>(), entry.gaps)
    }

    @Test
    fun `a preset with nothing but masks is named in the report and adds no recipe`() {
        val mask = "<crs:MaskGroupBasedCorrections><rdf:Seq><rdf:li/></rdf:Seq></crs:MaskGroupBasedCorrections>"
        val content = decodeRecipeFile("Sky.xmp", packet("""crs:HasSettings="True"""", mask))
        val entry = (content as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.NothingApplies
        assertEquals("Sky", entry.name)
        val plan = planRecipeImport(listOf(ReadImagoFile("Sky.xmp", content)), emptyList(), "now", { "id" })
        assertEquals(emptyList<Any>(), plan.recipes)
        assertEquals(0, plan.invalidRecipes)
        assertEquals(listOf(ImportGap.Left(ImportedSetting.MASKS, ImportGapReason.NOT_IMPORTED)), plan.gaps.single().gaps)
    }

    @Test
    fun `a curve held under both names counts once`() {
        val curve = "<rdf:Seq><rdf:li>0, 0</rdf:li><rdf:li>255, 255</rdf:li></rdf:Seq>"
        val entry = written("""crs:Vibrance="1"""", "<crs:ToneCurvePV2012>$curve</crs:ToneCurvePV2012><crs:ToneCurve>$curve</crs:ToneCurve>")
        assertEquals(emptyList<ImportGap>(), entry.gaps)
    }

    @Test
    fun `a field written as an element reads like one written as an attribute`() {
        val entry = written("", "<crs:Exposure2012>+0.50</crs:Exposure2012><crs:Vibrance>-5</crs:Vibrance>")
        assertEquals(0.5f, entry.recipe.tone.exposure)
        assertEquals(-5f, entry.recipe.presence.vibrance)
    }

    @Test
    fun `a value out of range is limited and reported with what the file asked for`() {
        val entry = written("""crs:Exposure2012="+7.5" crs:Contrast2012="+150"""")
        assertEquals(5f, entry.recipe.tone.exposure)
        assertEquals(100f, entry.recipe.tone.contrast)
        assertTrue(ImportGap.Limited(RecipePart.EXPOSURE, 7.5f) in entry.gaps)
        assertTrue(ImportGap.Limited(RecipePart.CONTRAST, 150f) in entry.gaps)
    }

    @Test
    fun `settings with no place in a recipe are reported and the rest applies`() {
        val mask = "<crs:MaskGroupBasedCorrections><rdf:Seq><rdf:li/></rdf:Seq></crs:MaskGroupBasedCorrections>"
        val entry = written(
            """crs:Exposure2012="+1" crs:FillLight="20" crs:HasCrop="True" crs:LensProfileEnable="1" crs:AutoTone="True" crs:CameraProfile="Camera Landscape"""",
            """$mask<crs:Look crs:Name="Vintage 07"/>""",
        )
        assertEquals(1f, entry.recipe.tone.exposure)
        listOf(
            ImportGap.Left(ImportedSetting.MASKS, ImportGapReason.NOT_IMPORTED),
            ImportGap.Left(ImportedSetting.OLD_TONE_CONTROLS, ImportGapReason.OLD_PROCESS),
            ImportGap.Left(ImportedSetting.GEOMETRY, ImportGapReason.NOT_IMPORTED),
            ImportGap.Left(ImportedSetting.LENS_PROFILE, ImportGapReason.NOT_IN_APP),
            ImportGap.Left(ImportedSetting.AUTO_TONE, ImportGapReason.NOT_IN_APP),
            ImportGap.Left(ImportedSetting.CREATIVE_PROFILE, ImportGapReason.NOT_IN_APP),
        ).forEach { assertTrue("$it", it in entry.gaps) }
        assertFalse(RecipePart.PROFILE in entry.parts)
        assertFalse(RecipePart.MASKS in entry.parts)
    }

    @Test
    fun `a setting nobody knows is reported, and metadata is not`() {
        assertTrue(ImportGap.Left(ImportedSetting.OTHER, ImportGapReason.NOT_IN_APP) in written("""crs:Vibrance="1" crs:SomethingNew="42"""").gaps)
        assertEquals(emptyList<ImportGap>(), written("""crs:Vibrance="1" crs:SomethingNew="0" crs:UUID="ABC" crs:Copyright="Someone"""").gaps)
    }

    @Test
    fun `what a photo's file records about itself is not a setting left behind`() {
        val entry = written("""crs:Vibrance="1" crs:GrainSeed="1250290866" crs:AsShotTemperature="5100" crs:AsShotTint="+21"""")
        assertEquals(emptyList<ImportGap>(), entry.gaps)
    }

    @Test
    fun `a preset names its collection and a localisation key is reduced to its text`() {
        val body = """
            <crs:Name><rdf:Alt><rdf:li xml:lang="x-default">${'$'}${'$'}${'$'}/Key/Name=Warm</rdf:li></rdf:Alt></crs:Name>
            <crs:Group><rdf:Alt><rdf:li xml:lang="x-default">Summer</rdf:li></rdf:Alt></crs:Group>
        """.trimIndent()
        val entry = written("""crs:Vibrance="1"""", body)
        assertEquals("Warm", entry.name)
        assertEquals("Summer", entry.collection)
    }

    @Test
    fun `a file with nothing a recipe can hold enters no recipe`() {
        val content = decodeRecipeFile("x.xmp", packet("""crs:UUID="ABC" crs:HasSettings="True""""))
        assertEquals(listOf(ImagoFileEntry.Invalid), (content as ImagoFileContent.Recipes).entries)
    }

    @Test
    fun `xml with an external entity or a dtd is refused`() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/">&e;</x:xmpmeta>"""
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.xmp", hostile.encodeToByteArray()))
        assertNull(readXmpFields(hostile))
    }

    @Test
    fun `broken xml is unreadable and not a crash`() {
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.xmp", """<a xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"><b>""".encodeToByteArray()))
    }

    @Test
    fun `a dng gives up the preset in its packet, in either byte order`() {
        listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN).forEach { order ->
            val entry = ready(decodeRecipeFile("Mobile.dng", dngWith(fixture("DJI city"), order)))
            assertEquals("DJI city", entry.name)
            assertEquals(RecipeOrigin.DNG, entry.origin)
            assertEquals(-18f, entry.recipe.presence.saturation)
        }
    }

    @Test
    fun `a dng whose packet points outside the file is unreadable`() {
        val bytes = dngWith(fixture("DJI city"))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(18, Int.MAX_VALUE)
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.dng", bytes))
    }

    @Test
    fun `a dng photo with no settings is not a preset`() {
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("x.dng", dngWith("<x:xmpmeta/>".encodeToByteArray())))
    }

    @Test
    fun `a zip enters as one collection and says what it could not read yet`() {
        val content = decodeRecipeFile(
            "Summer Pack.zip",
            zipOf(
                "Pack/Green Landscape.xmp" to fixture("Green Landscape"),
                "Pack/Mobile/DJI.dng" to dngWith(fixture("DJI city")),
                "Pack/Old.lrtemplate" to "s = {}".encodeToByteArray(),
                "Pack/read me.txt" to "hello".encodeToByteArray(),
                "__MACOSX/Pack/._Green Landscape.xmp" to byteArrayOf(0, 5, 22, 7),
            ),
        ) as ImagoFileContent.Recipes
        assertEquals(2, content.entries.size)
        assertEquals(1, content.skippedFiles)
        content.entries.forEach { assertEquals("Summer Pack", (it as ImagoFileEntry.Ready).collection) }
        assertEquals(RecipeOrigin.DNG, (content.entries[1] as ImagoFileEntry.Ready).origin)
    }

    @Test
    fun `one bad file does not take the pack down with it`() {
        val content = decodeRecipeFile(
            "p.zip",
            zipOf("a.xmp" to fixture("Açores"), "b.xmp" to "not xml".encodeToByteArray()),
        ) as ImagoFileContent.Recipes
        assertTrue(content.entries[0] is ImagoFileEntry.Ready)
        assertEquals(ImagoFileEntry.Invalid, content.entries[1])
    }

    @Test
    fun `a zip with a path leaving its folder is refused whole`() {
        listOf("../evil.xmp", "a/../../evil.xmp", "/etc/evil.xmp", "C:/evil.xmp", "..\\evil.xmp").forEach { path ->
            assertEquals(path, ImagoFileContent.Unreadable, decodeRecipeFile("p.zip", zipOf("ok.xmp" to fixture("Açores"), path to fixture("Açores"))))
        }
    }

    @Test
    fun `a zip that inflates past the limit is refused`() {
        val block = ByteArray(16 * 1024 * 1024)
        val entries = (0 until 9).map { "$it.bin" to block }.toTypedArray()
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("bomb.zip", zipOf(*entries)))
    }

    @Test
    fun `a zip with too many entries is refused`() {
        val entries = (0..2000).map { "$it.txt" to ByteArray(0) }.toTypedArray()
        assertEquals(ImagoFileContent.Unreadable, decodeRecipeFile("many.zip", zipOf(*entries)))
    }

    @Test
    fun `an imago file still reads through the same door`() {
        val bytes = """{"format":"imago","version":1,"kind":"recipes","recipes":[{"name":"N","processVersion":24,"recipe":{}}]}""".encodeToByteArray()
        assertEquals("N", ready(decodeRecipeFile("n.imago", bytes)).name)
    }

    @Test
    fun `a loose preset goes to the imported collection and a pack keeps its own`() {
        fun plan(vararg files: ReadImagoFile) =
            planRecipeImport(files.toList(), emptyList(), "now", { "id" }, importedCollection = "Imported").recipes
        assertEquals("Imported", plan(ReadImagoFile("Açores.xmp", decodeRecipeFile("Açores.xmp", fixture("Açores")))).single().collection)
        val pack = zipOf("Açores.xmp" to fixture("Açores"))
        assertEquals("Pack", plan(ReadImagoFile("Pack.zip", decodeRecipeFile("Pack.zip", pack))).single().collection)
    }

    @Test
    fun `the plan names what each recipe left behind`() {
        val plan = planRecipeImport(
            listOf(ReadImagoFile("g.xmp", decodeRecipeFile("g.xmp", fixture("Green Landscape")))),
            emptyList(),
            "now",
            { "id" },
        )
        assertEquals("Green Landscape", plan.gaps.single().name)
        assertEquals(RecipeOrigin.XMP, plan.recipes.single().origin)
    }

    @Test
    fun `importing the same preset twice does not add it again`() {
        val file = ReadImagoFile("a.xmp", decodeRecipeFile("a.xmp", fixture("Açores")))
        val first = planRecipeImport(listOf(file), emptyList(), "now", { "id" })
        val second = planRecipeImport(listOf(file), first.recipes, "later", { "id2" })
        assertEquals(emptyList<Any>(), second.recipes)
        assertEquals(listOf("Açores"), second.alreadyInLibrary)
    }

    @Test
    fun `a stranger's text is not in the report`() {
        val entry = written("""crs:Vibrance="1" crs:SomethingNew="secret-value" crs:CameraProfile="secret-profile"""")
        assertFalse(entry.gaps.toString().contains("secret"))
    }
}
