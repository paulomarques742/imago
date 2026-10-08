package eu.studio742.imago.feature.detail

import eu.studio742.imago.core.data.CatalogSyncState
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.model.AssetType
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.ImmichAlbum
import eu.studio742.imago.core.model.ImmichAsset
import eu.studio742.imago.core.model.ImmichAssetDetail
import eu.studio742.imago.core.model.ImmichTimeBucket
import eu.studio742.imago.core.model.LibraryFilter
import eu.studio742.imago.feature.editor.EditorAsset
import eu.studio742.imago.feature.editor.EditorExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The strip of a stack in the detail, and what Edit opens.
 *
 * A stack shows once in the grid, by its cover; the detail is where its other photos are reached,
 * and the one that is edited is chosen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailStackTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun detailAsset(id: String) = DetailAsset(
        id = id, checksum = "", fileName = "$id.jpg", thumbnailUrl = id, previewUrl = id, apiKey = "",
        fileCreatedAt = "2026-08-02T10:00:00.000Z", date = "2026-08-02T11:00", isFavorite = false,
    )

    private class StackLibrary(
        private val stack: List<String>?,
        private val exports: Map<String, String> = emptyMap(),
    ) : LibraryRepository {
        var stackReads = 0
        val changes = mutableListOf<String>()

        override suspend fun makeStackCover(assetId: String) { changes += "cover:$assetId" }
        override suspend fun removeFromStack(assetId: String) { changes += "remove:$assetId" }
        override suspend fun unstack(assetId: String) { changes += "unstack:$assetId" }

        override suspend fun stackMembers(assetId: String): List<ImmichAsset> {
            stackReads++
            val members = stack ?: error("stack.read is missing")
            return if (assetId in members) members.map { photoOf(it) } else emptyList()
        }
        override suspend fun exportOriginal(assetId: String): String? = exports[assetId]
        override suspend fun assetDetail(assetId: String): ImmichAssetDetail = error("not used")

        override fun assets(filter: LibraryFilter, month: String?, albumId: String?, query: String?) = error("not used")
        override suspend fun albums(): List<ImmichAlbum> = error("not used")
        override suspend fun timeBuckets(): List<ImmichTimeBucket> = error("not used")
        override val catalogSync: StateFlow<CatalogSyncState> = MutableStateFlow(CatalogSyncState())
        override suspend fun syncCatalog() = error("not used")
        override suspend fun loadMonth(month: String) = error("not used")
        override suspend fun indexOfAsset(assetId: String, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override suspend fun indexOfDate(date: LocalDate, filter: LibraryFilter, month: String?, query: String?): Int? = null
        override fun thumbnailUrl(assetId: String) = assetId
        override fun previewUrl(assetId: String) = assetId
        override fun videoPlaybackUrl(assetId: String) = assetId
        override fun apiKey(assetId: String) = ""
        override suspend fun setFavorite(assetId: String, isFavorite: Boolean) = error("not used")
        override suspend fun deleteAsset(assetId: String) = error("not used")
        override suspend fun downloadOriginal(assetId: String, destination: File) = error("not used")

        private fun photoOf(id: String) = ImmichAsset(
            id = id, checksum = "", originalFileName = "$id.jpg",
            fileCreatedAt = "2026-08-02T10:00:00.000Z", localDateTime = "2026-08-02T11:00",
            width = null, height = null, isFavorite = false, isEdited = false, type = AssetType.IMAGE,
        )
    }

    private object NoRecipes : RecipeRepository {
        override suspend fun get(assetId: String): EditRecipe? = null
        override suspend fun save(recipe: EditRecipe) = Unit
    }

    private object NoExports : EditorExporter {
        override suspend fun renderJpeg(asset: EditorAsset, recipe: EditRecipe, onPhase: (UiText) -> Unit): File = error("not used")
        override suspend fun saveToDevice(jpeg: File, fileName: String, createdAt: String): UiText? = error("not used")
        override suspend fun saveOriginalToDevice(file: File, fileName: String, isVideo: Boolean, createdAt: String): UiText? = error("not used")
    }

    private fun viewModel(library: LibraryRepository) =
        DetailViewModel(library, NoRecipes, NoExports, File(System.getProperty("java.io.tmpdir")))

    @Test fun aCoverBringsItsStackAndItStaysWhileMovingInsideIt() {
        val library = StackLibrary(stack = listOf("cover", "second", "third"))
        val model = viewModel(library)

        model.open(detailAsset("cover"))
        assertEquals(listOf("cover", "second", "third"), model.state.value.stack.map { it.id })

        // Another photo of the same stack, in the cover's place: the strip stays, not asked again.
        model.open(detailAsset("second"))
        assertEquals(listOf("cover", "second", "third"), model.state.value.stack.map { it.id })
        assertEquals(1, library.stackReads)
    }

    @Test fun editingAnExportOpensItsOriginal() {
        val library = StackLibrary(stack = listOf("export", "original"), exports = mapOf("export" to "original"))
        val model = viewModel(library)

        model.open(detailAsset("export"))
        assertEquals("original", model.state.value.editInstead?.id)

        // The original itself is edited as itself.
        model.open(detailAsset("original"))
        assertNull(model.state.value.editInstead)
    }

    @Test fun withoutStackReadThereIsNoStrip() {
        val model = viewModel(StackLibrary(stack = null))

        model.open(detailAsset("cover"))
        assertTrue(model.state.value.stack.isEmpty())
    }

    @Test fun changingTheStackReadsItsStripAgain() {
        val library = StackLibrary(stack = listOf("cover", "second"))
        val model = viewModel(library)
        model.open(detailAsset("second"))
        assertEquals(1, library.stackReads)

        model.makeStackCover(detailAsset("second"))

        assertEquals(listOf("cover:second"), library.changes)
        assertEquals(2, library.stackReads)
        assertEquals(listOf("cover", "second"), model.state.value.stack.map { it.id })
    }
}
