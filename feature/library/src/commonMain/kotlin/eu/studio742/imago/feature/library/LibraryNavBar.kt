package eu.studio742.imago.feature.library

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.library.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoNavBarSurface
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing

/**
 * The destinations of the bottom bar.
 *
 * They are the four places where the app starts anything. Recipes and compose are not detail screens
 * of the library — they are its siblings, and so the bar stays up in there instead of disappearing
 * and forcing a way back to change subject.
 */
enum class LibraryNavDestination { TIMELINE, ALBUMS, RECIPES, COMPOSER }

/**
 * The app's navigation bar.
 *
 * It lives here, and not inside the library screen, because three screens show it: the library, the
 * recipe library and compose. [onRefresh] is optional because it is the only item that is not
 * everyone's — only the library has something to refresh.
 */
@Composable
fun LibraryNavBar(
    selected: LibraryNavDestination?,
    onSelect: (LibraryNavDestination) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    ImagoNavBarSurface(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ImagoSizes.TouchTarget + ImagoSpacing.Xl),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryNavDestination.entries.forEach { destination ->
                val isSelected = destination == selected
                LibraryDestination(
                    label = destination.label,
                    // The active destination becomes a filled icon: over a black background the
                    // difference in opacity alone reads badly.
                    icon = { tint ->
                        Icon(
                            imageVector = destination.icon(isSelected),
                            contentDescription = null,
                            tint = tint,
                        )
                    },
                    selected = isSelected,
                    onClick = { onSelect(destination) },
                )
            }
            Box {
                LibraryDestination(
                    label = stringResource(Res.string.library_nav_more),
                    icon = { tint -> Icon(Icons.Outlined.MoreHoriz, contentDescription = null, tint = tint) },
                    selected = false,
                    onClick = { moreExpanded = true },
                )
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.library_settings)) },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = { moreExpanded = false; onOpenSettings() },
                    )
                    onRefresh?.let { refresh ->
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.library_refresh)) },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            onClick = {
                                moreExpanded = false
                                refresh()
                            },
                        )
                    }
                }
            }
        }
    }
}

private val LibraryNavDestination.label: String
    @Composable get() = when (this) {
        LibraryNavDestination.TIMELINE -> stringResource(Res.string.library_nav_library)
        LibraryNavDestination.ALBUMS -> stringResource(Res.string.library_nav_albums)
        LibraryNavDestination.RECIPES -> stringResource(Res.string.library_nav_recipes)
        LibraryNavDestination.COMPOSER -> stringResource(Res.string.library_nav_compose)
    }

/** Recipes use the same icon as the editor's recipes tool: it is the same destination. */
private fun LibraryNavDestination.icon(selected: Boolean): ImageVector = when (this) {
    LibraryNavDestination.TIMELINE -> if (selected) Icons.Filled.Photo else Icons.Outlined.PhotoLibrary
    LibraryNavDestination.ALBUMS -> if (selected) Icons.Filled.Collections else Icons.Outlined.Collections
    LibraryNavDestination.RECIPES -> if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome
    LibraryNavDestination.COMPOSER -> if (selected) Icons.Filled.ViewCarousel else Icons.Outlined.ViewCarousel
}

@Composable
private fun LibraryDestination(
    label: String,
    icon: @Composable (Color) -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) ImagoColors.Ivory else ImagoColors.TextTertiary
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(ImagoRadii.Small))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .sizeIn(minWidth = ImagoSizes.TouchTarget, minHeight = ImagoSizes.TouchTarget)
            .padding(horizontal = ImagoSpacing.Md, vertical = ImagoSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(ImagoSizes.IconDefault), contentAlignment = Alignment.Center) { icon(tint) }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
