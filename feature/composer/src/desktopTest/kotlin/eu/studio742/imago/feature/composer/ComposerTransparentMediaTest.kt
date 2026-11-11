package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.composition.CompositionBackground
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.ElementTransform
import eu.studio742.imago.core.composition.MediaReference
import eu.studio742.imago.core.composition.NormalizedRect
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.composition.PhotoRecipeSnapshot
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.model.EditRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerTransparentMediaTest {
    private val urls = object : CompositionMediaRepository by FakeComposerRepos.FakeMedia {
        override fun previewUrl(assetId: String) = "preview:$assetId"
        override fun originalUrl(assetId: String) = "original:$assetId"
    }

    private fun photo(id: String, fileName: String) = CompositionElement.Photo(id, ElementTransform(NormalizedRect(.1f, .1f, .5f, .5f)), 0,
        MediaReference(id, "sum", fileName), PhotoRecipeSnapshot(EditRecipe(assetId = id, originalChecksum = "sum", createdAt = "", updatedAt = "")))

    private fun model(project: CompositionProject): ComposerEditorViewModel {
        val projects = object : CompositionRepository {
            override fun observeAll() = flowOf(listOf(project))
            override suspend fun get(id: String) = project
            override suspend fun save(project: CompositionProject) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun duplicate(id: String, name: suspend (String) -> String): CompositionProject = error("unused")
        }
        val f = FakeComposerRepos
        return ComposerEditorViewModel(projects, f.templates, urls, f.recipes, f.exports, f.brandKits).also { it.open(project.id) }
    }

    private fun project(elements: List<CompositionElement>, background: CompositionBackground = CompositionBackground.Solid(0xFF000000)) = CompositionProject(
        id = "alpha", name = "Alpha", format = PageFormat.custom(240, 240), pages = listOf(CompositionPage("page", 0)),
        elements = elements, background = background, createdAt = "", updatedAt = "")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the stage draws a png from the original and a jpeg from the preview`() {
        val vm = model(project(listOf(photo("logo", "logo.png"), photo("beach", "beach.jpg"))))
        assertEquals("original:logo", vm.stagePhotoUrl("logo"))
        assertEquals("preview:beach", vm.stagePhotoUrl("beach"))
    }

    @Test fun `a png used as the page background keeps its transparency too`() {
        val vm = model(project(emptyList(), CompositionBackground.Photo(MediaReference("paper", "sum", "paper.png"))))
        assertEquals("original:paper", vm.stagePhotoUrl("paper"))
    }

    @Test fun `media the project does not know stays on the preview`() {
        assertEquals("preview:other", model(project(emptyList())).stagePhotoUrl("other"))
    }
}
