package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.BUILT_IN_RECIPES
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.Detail
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.KelvinTint
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.GeneratedWeights
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.RawDevelopment
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.Tone
import eu.studio742.imago.core.model.ToneCurve
import eu.studio742.imago.core.model.WhiteBalance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagoFileTest {
    private fun recipe(
        exposure: Float = 0.7f,
        crop: CropRect = CropRect(),
        masks: List<LocalMask> = emptyList(),
    ) = EditRecipe(
        assetId = "imago:device:IMG_0042",
        originalChecksum = "local:abcdef",
        createdAt = "2026-10-01T10:00:00Z",
        updatedAt = "2026-10-02T10:00:00Z",
        derivedAssetId = "imago-derived-77",
        exportedLook = "look-secret",
        rawDevelopment = RawDevelopment(exposure = 1.25f),
        tone = Tone(exposure = exposure),
        detail = Detail(sharpenAmount = 40f),
        geometry = Geometry(cropRect = crop),
        masks = masks,
    )

    private fun saved(
        name: String = "Warm evening",
        collection: String = "Travel",
        edit: EditRecipe = recipe(),
        parts: Set<RecipePart>? = RecipePart.LOOK,
    ) = SavedRecipe(
        id = "local-id-$name",
        name = name,
        collection = collection,
        recipe = edit,
        createdAt = "2026-10-01T10:00:00Z",
        updatedAt = "2026-10-02T10:00:00Z",
        isFavorite = true,
        usedAt = "2026-10-03T10:00:00Z",
        parts = parts,
    )

    private fun export(vararg recipes: SavedRecipe, parts: Set<RecipePart> = RecipePart.LOOK) =
        encodeImagoFile(recipes.map { RecipeExport(it, parts) })

    private fun ready(bytes: ByteArray) =
        (decodeImagoFile(bytes) as ImagoFileContent.Recipes).entries.map { it as ImagoFileEntry.Ready }

    @Test
    fun `a RAW's light travels in the file with the white balance`() {
        val balance = WhiteBalance(temp = -30f, tint = 8f, light = KelvinTint(3000f, 10f))
        val edit = recipe().copy(whiteBalance = balance)

        assertEquals(balance, ready(export(saved(edit = edit), parts = setOf(RecipePart.WHITE_BALANCE))).single().recipe.whiteBalance)
        assertEquals(WhiteBalance(), ready(export(saved(edit = edit), parts = setOf(RecipePart.EXPOSURE))).single().recipe.whiteBalance)
    }

    @Test
    fun `a generated mask goes into the file as its shape, without what the model found on the photo`() {
        val weights = GeneratedWeights.of("ppmobileseg-base-ade20k-sky", 1, 2, 2, byteArrayOf(0, 64, -128, -1))
        val sky = LocalMask(id = "m", components = listOf(MaskComponent(shape = MaskShape.SKY, generated = weights)))
        val bytes = export(saved(edit = recipe(masks = listOf(sky))), parts = RecipePart.LOOK + RecipePart.MASKS)

        assertFalse(bytes.decodeToString().contains(weights.png))
        val component = ready(bytes).single().recipe.masks.single().components.single()
        assertEquals(MaskShape.SKY, component.shape)
        assertNull(component.generated)
    }

    @Test
    fun `the origin survives going out to a file and back`() {
        val imported = saved().copy(origin = RecipeOrigin.XMP)

        assertEquals(RecipeOrigin.XMP, ready(export(imported)).single().origin)
    }

    @Test
    fun `a recipe made in IMAGO has no origin in the file`() {
        assertEquals(null, ready(export(saved())).single().origin)
    }

    @Test
    fun `an origin nobody here knows still marks the recipe as imported`() {
        fun originIn(value: String) = ready(
            bytesOf("""{"format":"imago","version":1,"kind":"recipes","recipes":[{"name":"A","processVersion":24,"recipe":{},"origin":$value}]}"""),
        ).single().origin

        assertEquals(RecipeOrigin.OTHER, originIn("\"CUBE\""))
        assertEquals(RecipeOrigin.OTHER, originIn("5"))
        assertEquals(null, originIn("null"))
    }

    private fun keysIn(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> element.keys + element.values.flatMap(::keysIn)
        is JsonArray -> element.flatMap(::keysIn).toSet()
        else -> emptySet()
    }

    private fun bytesOf(text: String) = text.encodeToByteArray()

    private fun fileWith(entry: String, version: Int = IMAGO_FILE_VERSION, kind: String = "recipes") =
        bytesOf("""{"format":"imago","version":$version,"kind":"$kind","recipes":[$entry]}""")

    @Test
    fun `a recipe comes back as it left`() {
        val entry = ready(export(saved())).single()

        assertEquals("Warm evening", entry.name)
        assertEquals("Travel", entry.collection)
        assertEquals(RecipePart.LOOK, entry.parts)
        assertEquals(saved().portableRecipe(RecipePart.LOOK), entry.recipe)
        assertEquals(0.7f, entry.recipe.tone.exposure)
    }

    @Test
    fun `nothing about the photo or the library leaves in the file`() {
        val bytes = export(saved())
        val text = bytes.decodeToString()

        val keys = keysIn(Json.parseToJsonElement(text))
        listOf(
            "assetId", "originalChecksum", "derivedAssetId", "exportedLook", "rawDevelopment",
            "createdAt", "updatedAt", "usedAt", "isFavorite", "id",
        ).forEach { assertFalse("$it must not be written", it in keys) }
        listOf("IMG_0042", "abcdef", "imago-derived-77", "look-secret", "2026-10", "local-id").forEach {
            assertFalse("$it must not be written", text.contains(it))
        }
    }

    @Test
    fun `a part left out goes neutral`() {
        val edit = recipe(crop = CropRect(0.1f, 0.1f, 0.5f, 0.5f), masks = listOf(LocalMask(id = "m")))
        val entry = ready(export(saved(edit = edit, parts = RecipePart.entries.toSet()), parts = RecipePart.LOOK)).single()

        assertEquals(CropRect(), entry.recipe.geometry.cropRect)
        assertTrue(entry.recipe.masks.isEmpty())
        assertEquals(0.7f, entry.recipe.tone.exposure)
    }

    @Test
    fun `sharpening travels with its part and not without it`() {
        assertEquals(40f, ready(export(saved())).single().recipe.detail.sharpenAmount)
        val without = export(saved(), parts = RecipePart.LOOK - RecipePart.SHARPENING)
        assertEquals(0f, ready(without).single().recipe.detail.sharpenAmount)
    }

    @Test
    fun `a pack keeps each recipe in its collection`() {
        val entries = ready(export(saved("One", "Travel"), saved("Two", "Travel"), saved("Three", "Portraits")))

        assertEquals(listOf("One", "Two", "Three"), entries.map { it.name })
        assertEquals(listOf("Travel", "Travel", "Portraits"), entries.map { it.collection })
    }

    @Test
    fun `a file with a byte order mark still reads`() {
        val bytes = "﻿".encodeToByteArray() + export(saved())
        assertEquals(1, ready(bytes).size)
    }

    @Test
    fun `a later file version asks for a newer app`() {
        assertEquals(ImagoFileContent.NeedsNewerApp, decodeImagoFile(fileWith("{}", version = IMAGO_FILE_VERSION + 1)))
    }

    @Test
    fun `a kind this app does not know asks for a newer app`() {
        assertEquals(ImagoFileContent.NeedsNewerApp, decodeImagoFile(fileWith("{}", kind = "composition")))
    }

    @Test
    fun `a recipe from a later process is named and not drawn`() {
        val entry = """{"name":"Future","processVersion":${CURRENT_PROCESS_VERSION + 1},"recipe":{}}"""
        val content = decodeImagoFile(fileWith(entry)) as ImagoFileContent.Recipes
        assertEquals(listOf(ImagoFileEntry.NeedsNewerApp("Future")), content.entries)
    }

    @Test
    fun `a profile this app does not know is named and not drawn`() {
        val entry = """{"name":"Future","processVersion":$CURRENT_PROCESS_VERSION,"parts":["PROFILE"],"recipe":{"profile":"hologram"}}"""
        val content = decodeImagoFile(fileWith(entry)) as ImagoFileContent.Recipes
        assertEquals(listOf(ImagoFileEntry.NeedsNewerApp("Future")), content.entries)
    }

    @Test
    fun `what is not an IMAGO file is refused whole`() {
        listOf("", "not json", "[]", """{"format":"other","version":1,"kind":"recipes","recipes":[{}]}""", """{"format":"imago"}""")
            .forEach { assertEquals(it, ImagoFileContent.Unreadable, decodeImagoFile(bytesOf(it))) }
        assertEquals(ImagoFileContent.Unreadable, decodeImagoFile(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `a file over the limit is not parsed`() {
        assertEquals(ImagoFileContent.Unreadable, decodeImagoFile(ByteArray(IMAGO_FILE_MAX_BYTES + 1) { ' '.code.toByte() }))
    }

    @Test
    fun `a damaged recipe does not take the rest of the file with it`() {
        val good = export(saved()).decodeToString().substringAfter("\"recipes\":[").dropLast(2)
        val content = decodeImagoFile(fileWith("""$good,{"name":"Broken"},17""")) as ImagoFileContent.Recipes

        assertTrue(content.entries[0] is ImagoFileEntry.Ready)
        assertEquals(ImagoFileEntry.Invalid, content.entries[1])
        assertEquals(ImagoFileEntry.Invalid, content.entries[2])
    }

    @Test
    fun `an infinite value is invalid`() {
        val entry = """{"name":"X","processVersion":22,"parts":["EXPOSURE"],"recipe":{"tone":{"exposure":1e999}}}"""
        val content = decodeImagoFile(fileWith(entry)) as ImagoFileContent.Recipes
        assertEquals(ImagoFileEntry.Invalid, content.entries.single())
    }

    @Test
    fun `a file cannot attach a recipe to a photo`() {
        val entry = """{"name":"X","processVersion":22,"parts":["EXPOSURE"],"recipe":
            {"assetId":"imago:device:1","derivedAssetId":"d","exportedLook":"l","rawDevelopment":{"exposure":3.0},"createdAt":"c"}}"""
        val recipe = ready(fileWith(entry)).single().recipe

        assertEquals("", recipe.assetId)
        assertEquals(null, recipe.derivedAssetId)
        assertEquals(null, recipe.exportedLook)
        assertEquals(null, recipe.rawDevelopment)
        assertEquals("", recipe.createdAt)
    }

    @Test
    fun `parts only a newer app knows are counted and the rest enters`() {
        val entry = """{"name":"X","processVersion":22,"parts":["EXPOSURE","HOLOGRAM"],"recipe":{}}"""
        val read = ready(fileWith(entry)).single()

        assertEquals(setOf(RecipePart.EXPOSURE), read.parts)
        assertEquals(1, read.unknownParts)
    }

    @Test
    fun `a list of parts with nothing this app knows is not an empty recipe`() {
        val entry = """{"name":"X","processVersion":22,"parts":["HOLOGRAM"],"recipe":{}}"""
        val content = decodeImagoFile(fileWith(entry)) as ImagoFileContent.Recipes
        assertEquals(listOf(ImagoFileEntry.NeedsNewerApp("X")), content.entries)
    }

    @Test
    fun `the app's own recipes are all sound`() {
        BUILT_IN_RECIPES.forEach { assertTrue(it.name, it.recipe.isSound()) }
    }

    @Test
    fun `what the renderer would index by is checked`() {
        val base = recipe()
        fun curve(vararg points: Pair<Int, Int>) = base.copy(toneCurve = ToneCurve(rgb = points.map { CurvePoint(it.first, it.second) }))

        assertTrue(curve(0 to 0, 255 to 255).isSound())
        assertFalse("out of range", curve(0 to 0, 300 to 255).isSound())
        assertFalse("unordered", curve(200 to 0, 100 to 255).isSound())
        assertFalse("empty", curve().isSound())
        assertFalse("rotation", base.copy(geometry = Geometry(rotation = 37)).isSound())
        assertFalse("crop", base.copy(geometry = Geometry(cropRect = CropRect(0.5f, 0f, 0.9f, 1f))).isSound())
    }

    @Test
    fun `a name from a file is one line of a sensible length`() {
        val entry = """{"name":"line\nbreak ${"x".repeat(300)}","processVersion":22,"parts":["EXPOSURE"],"recipe":{}}"""
        val name = ready(fileWith(entry)).single().name

        assertFalse(name.contains('\n'))
        assertEquals(120, name.length)
    }

    @Test
    fun `only what the recipe has in it counts as tied to the photo`() {
        assertEquals(emptySet<RecipePart>(), saved(parts = RecipePart.entries.toSet()).photoBoundParts())
        val cropped = recipe(crop = CropRect(0.1f, 0.1f, 0.5f, 0.5f))
        assertEquals(setOf(RecipePart.CROP), saved(edit = cropped, parts = RecipePart.entries.toSet()).photoBoundParts())
        assertEquals(emptySet<RecipePart>(), saved(edit = cropped, parts = RecipePart.LOOK).photoBoundParts())
    }

    @Test
    fun `a file name works everywhere`() {
        assertEquals("Warm evening.imago", imagoFileName("Warm evening", "recipes"))
        assertEquals("a_b_c.imago", imagoFileName("a/b:c", "recipes"))
        assertEquals("recipes.imago", imagoFileName(" ... ", "recipes"))
        assertEquals("recipes.imago", imagoFileName(null, "recipes"))
    }
}
