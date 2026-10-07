package eu.studio742.imago.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.render.changesTheImage
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.stringResource

/** What another app asked to open could not be read: said, and back to that app. */
@Composable
fun OpenFailedScreen(onClose: () -> Unit) {
    Surface(color = ImagoColors.Background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(ImagoSpacing.Xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(Res.string.library_open_failed), style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary)
            TextButton(onClick = onClose, modifier = Modifier.padding(top = ImagoSpacing.Lg)) {
                Text(stringResource(Res.string.library_open_close))
            }
        }
    }
}

/**
 * What another app opened, as the detail shows it: each photo with its local recipe when it has
 * one, so an edited photo of the gallery opens as it was left.
 */
suspend fun eu.studio742.imago.core.data.OpenedView.toAssetUiModels(
    library: eu.studio742.imago.core.data.LibraryRepository,
    recipes: eu.studio742.imago.core.data.RecipeRepository,
): List<AssetUiModel> = assets.map { asset ->
    val recipe = runCatching { recipes.get(asset.id) }.getOrNull()
        ?.takeIf { it.changesTheImage() }
    asset.toAssetUiModel(library, recipe)
}
