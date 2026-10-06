package eu.studio742.imago.feature.composer

import androidx.paging.PagingData
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.CompositionTemplate
import eu.studio742.imago.core.data.BrandKitRepository
import eu.studio742.imago.core.data.CompositionMediaRepository
import eu.studio742.imago.core.data.CompositionRepository
import eu.studio742.imago.core.data.CompositionTemplateRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.ImmichAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * The repositories [ComposerEditorViewModel] requires, with nothing behind them.
 *
 * The tests that draw the composer to an image need a ViewModel to pass the actions to, but never
 * touch them: what is checked is what stays on scene, not what happens to a tap.
 */
object FakeComposerRepos {
    object FakeProjects : CompositionRepository {
        override fun observeAll(): Flow<List<CompositionProject>> = flowOf(emptyList())
        override suspend fun get(id: String): CompositionProject? = null
        override suspend fun save(project: CompositionProject) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun duplicate(id: String, name: suspend (original: String) -> String): CompositionProject = error("not used")
    }

    object FakeTemplates : CompositionTemplateRepository {
        override fun observeAll(): Flow<List<CompositionTemplate>> = flowOf(emptyList())
        override suspend fun get(id: String): CompositionTemplate? = null
        override suspend fun save(template: CompositionTemplate) = Unit
        override suspend fun delete(id: String) = Unit
    }

    object FakeMedia : CompositionMediaRepository {
        override fun media(query: String?): Flow<PagingData<ImmichAsset>> = flowOf(PagingData.empty())
        override fun thumbnailUrl(assetId: String) = ""
        override fun previewUrl(assetId: String) = ""
        override fun videoPlaybackUrl(assetId: String) = ""
        override fun apiKey(assetId: String) = ""
        override suspend fun downloadOriginal(assetId: String, destination: File) = Unit
        override suspend fun uploadComposition(
            targetLibraryId: String,
            file: File,
            fileName: String,
            mimeType: String,
            createdAt: String,
        ) = Unit
    }

    object FakeRecipes : RecipeRepository {
        override suspend fun get(assetId: String): EditRecipe? = null
        override suspend fun save(recipe: EditRecipe) = Unit
    }

    object FakeExports : CompositionExports {
        override suspend fun export(
            project: CompositionProject,
            format: StaticExportFormat,
            pageIndex: Int?,
            targetLibraryId: String?,
            onProgress: (completed: Int, total: Int) -> Unit,
        ): CompositionExportOutcome = error("not used")
        override fun cancel(projectId: String) = Unit
        override suspend fun previewPage(project: CompositionProject, pageIndex: Int): File = error("not used")
    }

    object FakeBrandKits : BrandKitRepository {
        override fun observe(): Flow<BrandKit?> = flowOf(null)
        override suspend fun save(brandKit: BrandKit) = Unit
    }

    /** A ViewModel ready to pass to a screen that is going to be drawn. */
    fun viewModel() = ComposerEditorViewModel(FakeProjects, FakeTemplates, FakeMedia, FakeRecipes, FakeExports, FakeBrandKits)

    val projects = FakeProjects
    val templates = FakeTemplates
    val media = FakeMedia
    val recipes = FakeRecipes
    val exports = FakeExports
    val brandKits = FakeBrandKits
}
