package eu.studio742.imago.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.render.libraryAuth
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The photos of this day in an earlier [year], with the one on the card. */
data class MemoryUiModel(
    val year: Int,
    val yearsAgo: Int,
    val assetIds: List<String>,
    val coverUrl: String,
    val apiKey: String,
)

/** "On this day": a card per earlier year, over the timeline. */
@Composable
internal fun OnThisDayStrip(memories: List<MemoryUiModel>, onOpen: (MemoryUiModel) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ImagoSpacing.Sm, vertical = ImagoSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
    ) {
        items(memories, key = MemoryUiModel::year) { memory ->
            val label = pluralStringResource(Res.plurals.library_on_this_day_years_ago, memory.yearsAgo, memory.yearsAgo)
            Box(
                Modifier
                    .size(width = 112.dp, height = 150.dp)
                    .clip(RoundedCornerShape(ImagoRadii.Medium))
                    .background(ImagoColors.SurfaceElevated)
                    .clickable(role = Role.Button, onClickLabel = label) { onOpen(memory) },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(memory.coverUrl).libraryAuth(memory.apiKey).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(ImagoSpacing.Sm)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Text(memory.year.toString(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

/** Where "On this day" is turned off and on again: one choice for every library on this device. */
@Composable
internal fun OnThisDaySetting(viewModel: LibrarySettingsViewModel) {
    val shown by viewModel.configuration.showOnThisDay.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { viewModel.configuration.setShowOnThisDay(!shown) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
    ) {
        Icon(Icons.Outlined.History, null, tint = ImagoColors.Ivory)
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.library_on_this_day_setting), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.library_on_this_day_setting_body), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        }
        Switch(checked = shown, onCheckedChange = null)
    }
}
