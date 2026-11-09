package eu.studio742.imago.core.interchange

import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeImportPlanTest {
    private fun saved(name: String, exposure: Float = 0.5f, collection: String = "Travel", parts: Set<RecipePart> = RecipePart.LOOK) = SavedRecipe(
        id = "id-$name",
        name = name,
        collection = collection,
        recipe = EditRecipe(assetId = "a", originalChecksum = "c", createdAt = "t", updatedAt = "t", tone = Tone(exposure = exposure)),
        createdAt = "t",
        updatedAt = "t",
        parts = parts,
    )

    private fun fileOf(vararg recipes: SavedRecipe, name: String = "pack.imago") =
        ReadImagoFile(name, decodeImagoFile(encodeImagoFile(recipes.map { RecipeExport(it, it.appliedParts) })))

    private fun plan(library: List<SavedRecipe>, vararg files: ReadImagoFile): RecipeImportPlan {
        var next = 0
        return planRecipeImport(files.toList(), library, now = "2026-11-08T12:00:00Z", newId = { "new-${next++}" })
    }

    @Test
    fun `a new recipe enters as a recipe of its own`() {
        val added = plan(emptyList(), fileOf(saved("One"))).recipes.single()

        assertEquals("new-0", added.id)
        assertEquals("One", added.name)
        assertEquals("Travel", added.collection)
        assertEquals("2026-11-08T12:00:00Z", added.createdAt)
        assertEquals("2026-11-08T12:00:00Z", added.recipe.updatedAt)
        assertFalse(added.isFavorite)
        assertEquals(null, added.usedAt)
        assertEquals(RecipePart.LOOK, added.parts)
    }

    @Test
    fun `an imported recipe keeps its origin when it enters`() {
        val added = plan(emptyList(), fileOf(saved("One").copy(origin = RecipeOrigin.DNG))).recipes.single()

        assertEquals(RecipeOrigin.DNG, added.origin)
    }

    @Test
    fun `a recipe already in the library is skipped and named`() {
        val result = plan(listOf(saved("One")), fileOf(saved("One")))

        assertTrue(result.recipes.isEmpty())
        assertEquals(listOf("One"), result.alreadyInLibrary)
    }

    @Test
    fun `the same recipe in another collection is still the same recipe`() {
        val result = plan(listOf(saved("One", collection = "Elsewhere")), fileOf(saved("One")))
        assertEquals(listOf("One"), result.alreadyInLibrary)
    }

    @Test
    fun `the same name with other values enters numbered`() {
        val result = plan(listOf(saved("One", exposure = 2f)), fileOf(saved("One")))

        assertEquals(listOf("One (2)"), result.recipes.map { it.name })
        assertTrue(result.alreadyInLibrary.isEmpty())
    }

    @Test
    fun `numbering skips the numbers already taken`() {
        val library = listOf(saved("One", exposure = 2f), saved("One (2)", exposure = 3f))
        assertEquals(listOf("One (3)"), plan(library, fileOf(saved("One"))).recipes.map { it.name })
    }

    @Test
    fun `importing a numbered recipe again does not number it again`() {
        val library = listOf(saved("One", exposure = 2f), saved("One (2)", exposure = 0.5f))
        val result = plan(library, fileOf(saved("One")))

        assertTrue(result.recipes.isEmpty())
        assertEquals(listOf("One"), result.alreadyInLibrary)
    }

    @Test
    fun `a recipe twice in the same import enters once`() {
        val result = plan(emptyList(), fileOf(saved("One")), fileOf(saved("One"), name = "other.imago"))

        assertEquals(listOf("One"), result.recipes.map { it.name })
        assertEquals(listOf("One"), result.alreadyInLibrary)
    }

    @Test
    fun `a recipe exported without its geometry is still recognised`() {
        val library = saved("One", parts = RecipePart.entries.toSet())
        val file = ReadImagoFile("a.imago", decodeImagoFile(encodeImagoFile(listOf(RecipeExport(library, RecipePart.LOOK)))))

        assertEquals(listOf("One"), plan(listOf(library), file).alreadyInLibrary)
    }

    @Test
    fun `bringing more than the library's recipe applies is a new recipe`() {
        val library = saved("One", parts = setOf(RecipePart.EXPOSURE))
        val result = plan(listOf(library), fileOf(saved("One", parts = RecipePart.LOOK)))
        assertEquals(listOf("One (2)"), result.recipes.map { it.name })
    }

    @Test
    fun `a file without a collection gives its name to it`() {
        val noCollection = saved("One", collection = "")
        val added = plan(emptyList(), fileOf(noCollection, name = "Summer pack.imago")).recipes.single()
        assertEquals("Summer pack", added.collection)
    }

    @Test
    fun `a recipe without a name takes the file's`() {
        val added = plan(emptyList(), fileOf(saved(""), name = "Summer.imago")).recipes.single()
        assertEquals("Summer", added.name)
    }

    @Test
    fun `what does not enter is all accounted for`() {
        val entries = listOf(ImagoFileEntry.Invalid, ImagoFileEntry.NeedsNewerApp("Future"))
        val result = plan(
            emptyList(),
            ReadImagoFile("a.imago", ImagoFileContent.Recipes(entries)),
            ReadImagoFile("b.imago", ImagoFileContent.Unreadable),
            ReadImagoFile("c.imago", ImagoFileContent.NeedsNewerApp),
        )

        assertEquals(1, result.invalidRecipes)
        assertEquals(listOf("Future"), result.needsNewerApp)
        assertEquals(1, result.unreadableFiles)
        assertEquals(1, result.filesNeedingNewerApp)
    }

    @Test
    fun `a recipe missing parts only a newer app knows is reported`() {
        val entry = ImagoFileEntry.Ready("One", "", setOf(RecipePart.EXPOSURE), saved("One").recipe, unknownParts = 2)
        val result = plan(emptyList(), ReadImagoFile("a.imago", ImagoFileContent.Recipes(listOf(entry))))

        assertEquals(listOf("One"), result.partlyRead)
        assertEquals(1, result.recipes.size)
    }
}
