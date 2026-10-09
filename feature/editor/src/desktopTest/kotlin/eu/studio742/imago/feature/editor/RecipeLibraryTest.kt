package eu.studio742.imago.feature.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import eu.studio742.imago.core.model.BUILT_IN_RECIPES
import eu.studio742.imago.core.model.BuiltInRecipeMark
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.SavedRecipe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The recipe library and the dialog that names a recipe. A tester could not find how to mark one of
 * the app's filters as a favourite — there was no way — and typed collections by hand every time.
 */
@OptIn(ExperimentalTestApi::class)
class RecipeLibraryTest {
    private val systemLocale = Locale.getDefault()

    // The texts are looked up as the English the app ships with, whatever this machine speaks.
    @Before fun english() = Locale.setDefault(Locale.ENGLISH)
    @After fun restore() = Locale.setDefault(systemLocale)

    private fun saved(id: String, name: String, collection: String, isFavorite: Boolean = false, usedAt: String? = null) =
        SavedRecipe(
            id = id,
            name = name,
            collection = collection,
            recipe = EditRecipe(assetId = "", originalChecksum = "", createdAt = "2026-01-01", updatedAt = "2026-01-01"),
            createdAt = "2026-01-01",
            updatedAt = "2026-01-01",
            isFavorite = isFavorite,
            usedAt = usedAt,
        )

    private val pastel = BUILT_IN_RECIPES.first { it.id == "built-in-pastel" }
    private val dramatic = BUILT_IN_RECIPES.first { it.id == "built-in-dramatic" }

    private fun SemanticsNodeInteractionsProvider.showsOnly(vararg names: String, among: List<String>) {
        among.forEach { name ->
            val shown = onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
            assertEquals("$name shown", name in names, shown)
        }
    }

    @Test
    fun `favourites and recent mix the person's recipes with the app's`() = runComposeUiTest {
        val mine = listOf(
            saved("a", "Warm evening", "Travel", isFavorite = true, usedAt = "2026-10-01T10:00:00Z"),
            saved("b", "Cold morning", "Travel"),
        )
        val marks = listOf(
            BuiltInRecipeMark(pastel.id, isFavorite = true),
            BuiltInRecipeMark(dramatic.id, usedAt = "2026-10-05T10:00:00Z"),
        )
        setContent {
            RecipeLibraryScreen(
                saved = mine.toCards(),
                builtIn = builtInCards(marks),
                isLoading = false,
                assetId = null,
                onBack = {},
                onSelect = {},
                onToggleFavorite = {},
                onRename = {},
                onDelete = {},
                onCreate = null,
            )
        }
        val names = listOf("Warm evening", "Cold morning", pastel.name, dramatic.name)

        onNodeWithText("Mine").assertIsSelected()
        showsOnly("Warm evening", "Cold morning", among = names)

        onNodeWithText("Favorites").performClick()
        showsOnly("Warm evening", pastel.name, among = names)

        onNodeWithText("Recent").performClick()
        showsOnly("Warm evening", dramatic.name, among = names)
        val dramaticTop = onNodeWithText(dramatic.name).fetchSemanticsNode().boundsInRoot.top
        val warmTop = onNodeWithText("Warm evening").fetchSemanticsNode().boundsInRoot.top
        val dramaticLeft = onNodeWithText(dramatic.name).fetchSemanticsNode().boundsInRoot.left
        val warmLeft = onNodeWithText("Warm evening").fetchSemanticsNode().boundsInRoot.left
        assertTrue("the most recent use comes first", dramaticTop < warmTop || (dramaticTop == warmTop && dramaticLeft < warmLeft))
    }

    @Test
    fun `an app filter has a heart of its own`() = runComposeUiTest {
        val toggled = mutableListOf<RecipeCard>()
        setContent {
            RecipeLibraryScreen(
                saved = emptyList(),
                builtIn = builtInCards(emptyList()),
                isLoading = false,
                assetId = null,
                onBack = {},
                onSelect = {},
                onToggleFavorite = { toggled += it },
                onRename = {},
                onDelete = {},
                onCreate = null,
            )
        }

        onNodeWithText("IMAGO").assertIsSelected()
        onNodeWithContentDescription("Add ${pastel.name} to favorites").performClick()

        assertEquals(listOf(pastel.id to true), toggled.map { it.id to it.isBuiltIn })
    }

    private fun confirmedCollection(collections: List<String>, initial: String, choose: SemanticsNodeInteractionsProvider.() -> Unit): Pair<String, String>? {
        var confirmed: Pair<String, String>? = null
        runComposeUiTest {
            setContent {
                RecipeDetailsDialog(
                    title = "Save recipe",
                    initialName = "Golden",
                    initialCollection = initial,
                    collections = collections,
                    confirmLabel = "Save",
                    onDismiss = {},
                    onConfirm = { name, collection -> confirmed = name to collection },
                )
            }
            choose()
            onNodeWithText("Save").performClick()
        }
        return confirmed
    }

    @Test
    fun `the collections that exist are there to pick`() {
        val result = confirmedCollection(listOf("B&W", "Travel"), initial = "General") {
            onNodeWithText("General").assertIsSelected()
            onNodeWithText("Travel").performClick()
        }
        assertEquals("Golden" to "Travel", result)
    }

    @Test
    fun `a new collection is typed in its own field`() {
        val result = confirmedCollection(listOf("Travel"), initial = "Travel") {
            onNodeWithText("New collection").performClick()
            onNodeWithText("Collection name").performTextInput("  Street ")
        }
        assertEquals("Golden" to "Street", result)
    }

    @Test
    fun `a new name that only differs in case is the collection that exists`() {
        val result = confirmedCollection(listOf("Travel"), initial = "Travel") {
            onNodeWithText("New collection").performClick()
            onNodeWithText("Collection name").performTextInput("travel")
        }
        assertEquals("Golden" to "Travel", result)
    }

    @Test
    fun `collections come once each, in alphabetical order`() {
        val recipes = listOf(saved("1", "x", "travel "), saved("2", "y", "B&W"), saved("3", "z", "travel"), saved("4", "w", ""))
        assertEquals(listOf("B&W", "travel"), recipes.collections())
    }
}
