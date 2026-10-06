package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.StringResource
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.studio742.imago.core.data.ConfigurationRepository
import eu.studio742.imago.core.data.DerivedAssetRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.core.data.SavedRecipeRepository
import eu.studio742.imago.core.immich.ImmichApi
import eu.studio742.imago.core.model.EditRecipe
import java.io.File
import javax.inject.Inject

/** The export on Android: the original at full resolution through BitmapFactory, and the gallery. */
class AndroidEditorExporter @Inject constructor(
    private val fullResolution: FullResolutionExporter,
    private val gallery: GalleryExporter,
) : EditorExporter {
    override suspend fun renderJpeg(asset: EditorAsset, recipe: EditRecipe, onPhase: (UiText) -> Unit): File =
        fullResolution.renderJpeg(asset, recipe, onPhase)

    override suspend fun saveToDevice(jpeg: File, fileName: String, createdAt: String): UiText {
        gallery.saveJpeg(jpeg, fileName, createdAt)
        return uiText(Res.string.editor_saved_to_gallery)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EditorBindings {
    @Binds abstract fun exporter(implementation: AndroidEditorExporter): EditorExporter
}

@HiltViewModel
class HiltEditorViewModel @Inject constructor(
    @ApplicationContext context: Context,
    recipes: RecipeRepository,
    savedRecipes: SavedRecipeRepository,
    derivedAssets: DerivedAssetRepository,
    configuration: ConfigurationRepository,
    immichApi: ImmichApi,
    exporter: EditorExporter,
) : EditorViewModel(context, recipes, savedRecipes, derivedAssets, configuration, immichApi, exporter)

@HiltViewModel
class HiltRecipeLibraryViewModel @Inject constructor(savedRecipes: SavedRecipeRepository) : RecipeLibraryViewModel(savedRecipes)

@Composable
actual fun editorViewModel(): EditorViewModel = hiltViewModel<HiltEditorViewModel>()

@Composable
actual fun recipeLibraryViewModel(): RecipeLibraryViewModel = hiltViewModel<HiltRecipeLibraryViewModel>()

@Composable
actual fun rememberSaveToDevice(onSave: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onSave() else onDenied()
    }
    return {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            onSave()
        }
    }
}

actual val SaveToDeviceLabel: StringResource get() = Res.string.editor_save_to_gallery
