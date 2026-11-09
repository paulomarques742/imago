package eu.studio742.imago.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.interchange.RecipeExport
import eu.studio742.imago.core.interchange.ImportGap
import eu.studio742.imago.core.interchange.ImportGapReason
import eu.studio742.imago.core.interchange.ImportedSetting
import eu.studio742.imago.core.interchange.RecipeImportGaps
import eu.studio742.imago.core.interchange.RecipeImportPlan
import eu.studio742.imago.core.interchange.decodeImagoFile
import eu.studio742.imago.core.interchange.ImagoFileContent
import eu.studio742.imago.core.interchange.ImagoFileEntry
import eu.studio742.imago.core.interchange.encodeImagoFile
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.RecipeOrigin
import eu.studio742.imago.core.model.RecipePart
import eu.studio742.imago.core.model.SavedRecipe
import eu.studio742.imago.core.model.Tone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Locale

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RecipeFilesTest {
    private val systemLocale = Locale.getDefault()

    // The texts are looked up as the English the app ships with, whatever this machine speaks.
    @Before
    fun setUp() {
        Locale.setDefault(Locale.ENGLISH)
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Locale.setDefault(systemLocale)
        Dispatchers.resetMain()
    }

    private fun saved(name: String, crop: CropRect = CropRect(), parts: Set<RecipePart> = RecipePart.LOOK) = SavedRecipe(
        id = "id-$name",
        name = name,
        collection = "Travel",
        recipe = EditRecipe(
            assetId = "a",
            originalChecksum = "c",
            createdAt = "t",
            updatedAt = "t",
            tone = Tone(exposure = 0.5f),
            geometry = Geometry(cropRect = crop),
        ),
        createdAt = "t",
        updatedAt = "t",
        parts = parts,
    )

    private class Library(var recipes: List<SavedRecipe> = emptyList(), val failSaving: Boolean = false) : SavedRecipeRepository {
        override suspend fun list() = recipes
        override suspend fun save(recipe: SavedRecipe) {
            check(!failSaving)
            recipes = recipes.filter { it.id != recipe.id } + recipe
        }
        override suspend fun delete(id: String) { recipes = recipes.filter { it.id != id } }
        override suspend fun builtInMarks() = emptyList<BuiltInRecipeMark>()
        override suspend fun saveBuiltInMark(mark: BuiltInRecipeMark) = Unit
    }

    private fun exporter(onDelivered: (String, ByteArray) -> Unit, onFailed: () -> Unit = {}) = RecipeExporter(
        RecipeFileActions(share = onDelivered, saveAs = onDelivered, open = {}),
        CoroutineScope(Dispatchers.Unconfined),
        { "IMAGO recipes" },
        onFailed,
    )

    private fun fileWith(vararg recipes: SavedRecipe) =
        encodeImagoFile(recipes.map { RecipeExport(it, it.appliedParts) })

    @Test
    fun `a recipe with nothing tied to a photo goes straight to the file`() = runBlocking {
        val delivered = CompletableDeferred<Pair<String, ByteArray>>()
        val exporter = exporter({ name, bytes -> delivered.complete(name to bytes) })

        exporter.start(listOf(saved("One")), RecipeDelivery.SHARE)

        assertNull(exporter.asking)
        val (name, bytes) = withTimeout(5_000) { delivered.await() }
        assertEquals("One.imago", name)
        val entry = (decodeImagoFile(bytes) as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.Ready
        assertEquals(RecipePart.LOOK, entry.parts)
    }

    @Test
    fun `a recipe with a crop asks first and leaves the crop out unless ticked`() = runBlocking {
        val delivered = CompletableDeferred<ByteArray>()
        val exporter = exporter({ _, bytes -> delivered.complete(bytes) })
        val cropped = saved("One", CropRect(0.1f, 0.1f, 0.5f, 0.5f), RecipePart.entries.toSet())

        exporter.start(listOf(cropped), RecipeDelivery.SAVE)
        assertNotNull(exporter.asking)
        assertTrue("nothing is written while asking", !delivered.isCompleted)

        exporter.confirm(RecipePart.LOOK)
        val entry = (decodeImagoFile(withTimeout(5_000) { delivered.await() }) as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.Ready
        assertEquals(CropRect(), entry.recipe.geometry.cropRect)
    }

    private fun configurationHolding(choice: String?): ConfigurationRepository {
        var stored = choice
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ConfigurationRepository::class.java)) { _, method, args ->
            when (method.name) {
                "getRecipePartsChoice" -> stored
                "setRecipePartsChoice" -> { stored = args[0] as String?; Unit }
                else -> error(method.name)
            }
        } as ConfigurationRepository
    }

    @Test
    fun `a choice remembered from before the finishing boxes gets them ticked`() {
        val remembered = configurationHolding("EXPOSURE,CONTRAST").rememberedRecipeParts()

        assertEquals(setOf(RecipePart.EXPOSURE, RecipePart.CONTRAST) + RecipePart.FINISHING, remembered)
    }

    @Test
    fun `unticking the finishing boxes is remembered`() {
        val configuration = configurationHolding(null)
        val chosen = setOf(RecipePart.EXPOSURE)

        configuration.rememberRecipeParts(chosen)

        assertEquals(chosen, configuration.rememberedRecipeParts())
    }

    @Test
    fun `the question about the parts starts with what is tied to a photo unticked`() = runComposeUiTest {
        var delivered: ByteArray? = null
        val exporter = exporter({ _, bytes -> delivered = bytes })
        exporter.start(listOf(saved("One", CropRect(0.1f, 0.1f, 0.5f, 0.5f), RecipePart.entries.toSet())), RecipeDelivery.SHARE)
        setContent { RecipeExportDialog(exporter) }

        onNodeWithText("What goes in the file").assertExists()
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { delivered != null }

        val entry = (decodeImagoFile(delivered!!) as ImagoFileContent.Recipes).entries.single() as ImagoFileEntry.Ready
        assertEquals(RecipePart.LOOK, entry.parts)
        assertEquals(CropRect(), entry.recipe.geometry.cropRect)
        assertNull(exporter.asking)
    }

    @Test
    fun `sharing an imported recipe asks first, and the file keeps its origin`() = runComposeUiTest {
        var delivered: ByteArray? = null
        val exporter = exporter({ _, bytes -> delivered = bytes })
        exporter.start(listOf(saved("One").copy(origin = RecipeOrigin.XMP), saved("Two")), RecipeDelivery.SHARE)
        setContent { RecipeExportDialog(exporter) }

        onNodeWithText("Can you share this?").assertExists()
        assertNull("nothing leaves before the answer", delivered)
        onNodeWithText("Share").performClick()
        waitUntil(timeoutMillis = 5_000) { delivered != null }

        val entries = (decodeImagoFile(delivered!!) as ImagoFileContent.Recipes).entries.map { it as ImagoFileEntry.Ready }
        assertEquals(listOf(RecipeOrigin.XMP, null), entries.map { it.origin })
        assertNull(exporter.askingRights)
    }

    @Test
    fun `saying no to sharing an imported recipe sends nothing`() = runComposeUiTest {
        var delivered: ByteArray? = null
        val exporter = exporter({ _, bytes -> delivered = bytes })
        exporter.start(listOf(saved("One").copy(origin = RecipeOrigin.XMP)), RecipeDelivery.SHARE)
        setContent { RecipeExportDialog(exporter) }

        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertNull(exporter.askingRights)
        assertNull(delivered)
    }

    @Test
    fun `sharing a recipe made here, or saving an imported one, asks nothing`() = runBlocking {
        val delivered = Channel<String>(Channel.UNLIMITED)
        val exporter = exporter({ name, _ -> delivered.trySend(name) })

        exporter.start(listOf(saved("Own")), RecipeDelivery.SHARE)
        assertNull(exporter.askingRights)
        assertEquals("Own.imago", withTimeout(5_000) { delivered.receive() })
        exporter.start(listOf(saved("Imported").copy(origin = RecipeOrigin.DNG)), RecipeDelivery.SAVE)
        assertNull(exporter.askingRights)
        assertEquals("Imported.imago", withTimeout(5_000) { delivered.receive() })
    }

    @Test
    fun `several recipes make one file under the pack's name`() = runBlocking {
        val delivered = CompletableDeferred<Pair<String, ByteArray>>()
        val exporter = exporter({ name, bytes -> delivered.complete(name to bytes) })

        exporter.start(listOf(saved("One"), saved("Two")), RecipeDelivery.SAVE)

        val (name, bytes) = withTimeout(5_000) { delivered.await() }
        assertEquals("IMAGO recipes.imago", name)
        assertEquals(2, (decodeImagoFile(bytes) as ImagoFileContent.Recipes).entries.size)
    }

    @Test
    fun `opening a file saves nothing until it is confirmed`() = runBlocking {
        val library = Library()
        val viewModel = RecipeLibraryViewModel(library)

        viewModel.openFiles(listOf(RecipeFile("pack.imago", fileWith(saved("One"), saved("Two")))))
        val plan = withTimeout(5_000) { viewModel.state.first { it.importPlan != null } }.importPlan!!

        assertEquals(2, plan.recipes.size)
        assertTrue(library.recipes.isEmpty())

        viewModel.addImported()
        withTimeout(5_000) { viewModel.state.first { it.importPlan == null } }
        assertEquals(listOf("One", "Two"), library.recipes.map { it.name })
        assertEquals(1, viewModel.state.value.importsAdded)
    }

    @Test
    fun `leaving the preview adds nothing`() = runBlocking {
        val library = Library()
        val viewModel = RecipeLibraryViewModel(library)

        viewModel.openFiles(listOf(RecipeFile("pack.imago", fileWith(saved("One")))))
        withTimeout(5_000) { viewModel.state.first { it.importPlan != null } }
        viewModel.dismissImport()

        assertNull(viewModel.state.value.importPlan)
        assertTrue(library.recipes.isEmpty())
    }

    @Test
    fun `a failed save keeps the preview to try again`() = runBlocking {
        val viewModel = RecipeLibraryViewModel(Library(failSaving = true))

        viewModel.openFiles(listOf(RecipeFile("pack.imago", fileWith(saved("One")))))
        withTimeout(5_000) { viewModel.state.first { it.importPlan != null } }
        viewModel.addImported()

        val state = withTimeout(5_000) { viewModel.state.first { it.importAddFailed } }
        assertNotNull(state.importPlan)
    }

    @Test
    fun `a preset from another app enters marked, in the imported collection, with what it left behind`() = runBlocking {
        val library = Library()
        val viewModel = RecipeLibraryViewModel(library)
        val preset = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
              crs:Vibrance="+10" crs:ParametricShadows="-20"/></rdf:RDF></x:xmpmeta>
        """.trimIndent().encodeToByteArray()

        viewModel.openFiles(listOf(RecipeFile("Warm.xmp", preset)), importedCollection = "Imported")
        val plan = withTimeout(5_000) { viewModel.state.first { it.importPlan != null } }.importPlan!!

        val recipe = plan.recipes.single()
        assertEquals("Warm", recipe.name)
        assertEquals("Imported", recipe.collection)
        assertEquals(RecipeOrigin.XMP, recipe.origin)
        assertEquals(
            listOf(ImportGap.Left(ImportedSetting.PARAMETRIC_CURVE, ImportGapReason.APPROXIMATED)),
            plan.gaps.single().gaps,
        )
    }

    @Test
    fun `a dropped dng stays a photo while the other recipe formats open as recipes`() {
        assertTrue(isRecipeFileName("a.imago") && isRecipeFileName("a.XMP") && isRecipeFileName("pack.zip"))
        assertFalse(isRecipeFileName("photo.dng"))
    }

    @Test
    fun `every setting a report can name has a label`() = runComposeUiTest {
        val gaps = ImportedSetting.entries.flatMap { setting -> ImportGapReason.entries.map { ImportGap.Left(setting, it) } } +
            ImportGap.Limited(RecipePart.EXPOSURE, 7.5f)
        setContent {
            RecipeImportScreen(
                plan = RecipeImportPlan(gaps = listOf(RecipeImportGaps("Warm evening", gaps)), skippedFiles = 2),
                assetId = null,
                sampleIndex = 0,
                onSampleIndex = {},
                isAdding = false,
                addFailed = false,
                onBack = {},
                onAdd = {},
            )
        }
        onNodeWithText("Warm evening", substring = true).assertExists()
        onNodeWithText("7.5", substring = true).assertExists()
    }

    @Test
    fun `a file that could not be read is reported`() = runBlocking {
        val viewModel = RecipeLibraryViewModel(Library())

        viewModel.openFiles(listOf(RecipeFile("big.imago", null)))
        val plan = withTimeout(5_000) { viewModel.state.first { it.importPlan != null } }.importPlan!!

        assertEquals(1, plan.unreadableFiles)
        assertTrue(plan.recipes.isEmpty())
    }

    @Test
    fun `the preview names what will not enter and adds on the button`() = runComposeUiTest {
        var added = false
        val plan = RecipeImportPlan(
            recipes = listOf(saved("Warm evening")),
            alreadyInLibrary = listOf("Cold morning"),
            needsNewerApp = listOf("Future"),
            invalidRecipes = 2,
            unreadableFiles = 1,
        )
        setContent {
            RecipeImportScreen(
                plan = plan,
                assetId = null,
                sampleIndex = 0,
                onSampleIndex = {},
                isAdding = false,
                addFailed = false,
                onBack = {},
                onAdd = { added = true },
            )
        }

        onNodeWithText("Warm evening").assertExists()
        onNodeWithText("Already in the library: Cold morning").assertExists()
        onNodeWithText("Need a newer version of IMAGO: Future").assertExists()
        onNodeWithText("2 recipes are damaged and were left out.").assertExists()
        onNodeWithText("1 file couldn’t be read.").assertExists()
        onNodeWithText("Add to recipes").performClick()
        assertTrue(added)
    }

    @Test
    fun `with nothing to add there is no button to add it`() = runComposeUiTest {
        setContent {
            RecipeImportScreen(
                plan = RecipeImportPlan(unreadableFiles = 1),
                assetId = null,
                sampleIndex = 0,
                onSampleIndex = {},
                isAdding = false,
                addFailed = false,
                onBack = {},
                onAdd = {},
            )
        }

        onNodeWithText("There is nothing to add.").assertExists()
        onNodeWithText("Add to recipes").assertDoesNotExist()
    }

    @Test
    fun `a long press selects and the bar sends what is selected`() = runComposeUiTest {
        var shared: List<String>? = null
        setContent {
            RecipeLibraryScreen(
                saved = listOf(saved("One"), saved("Two")).toCards(),
                builtIn = emptyList(),
                isLoading = false,
                assetId = null,
                onBack = {},
                onSelect = {},
                onToggleFavorite = {},
                onRename = {},
                onDelete = {},
                onCreate = null,
                onShareRecipes = { cards -> shared = cards.map { it.name } },
                onSaveRecipes = {},
            )
        }

        onNodeWithText("One").performTouchInput { longClick() }
        onNodeWithText("1 selected").assertExists()
        onNodeWithText("Two").performClick()
        onNodeWithText("2 selected").assertExists()

        onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf("One", "Two"), shared)
        onNodeWithText("2 selected").assertDoesNotExist()
    }

    @Test
    fun `without a share menu only saving is offered`() = runComposeUiTest {
        setContent {
            RecipeLibraryScreen(
                saved = listOf(saved("One")).toCards(),
                builtIn = emptyList(),
                isLoading = false,
                assetId = null,
                onBack = {},
                onSelect = {},
                onToggleFavorite = {},
                onRename = {},
                onDelete = {},
                onCreate = null,
                onShareRecipes = null,
                onSaveRecipes = {},
            )
        }

        onNodeWithText("One").performTouchInput { longClick() }
        onAllNodesWithContentDescription("Share").assertCountEquals(0)
        onNodeWithContentDescription("Save as…").assertExists()
    }

    @Test
    fun `an import shows the person's recipes even from the app's tab`() = runComposeUiTest {
        var signal by mutableStateOf(0)
        setContent {
            RecipeLibraryScreen(
                saved = emptyList(),
                builtIn = builtInCards(emptyList()),
                isLoading = false,
                assetId = null,
                onBack = {},
                onSelect = {},
                onToggleFavorite = {},
                onRename = {},
                onDelete = {},
                onCreate = null,
                showMineSignal = signal,
            )
        }
        onNodeWithText("Mine").assertExists()

        signal = 1
        waitForIdle()
        onNodeWithText("Mine").assertIsSelected()
    }
}
