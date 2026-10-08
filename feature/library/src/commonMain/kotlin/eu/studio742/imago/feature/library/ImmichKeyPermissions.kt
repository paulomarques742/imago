package eu.studio742.imago.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.feature.library.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** A permission of the Immich API key, and what IMAGO does with it. */
internal data class ImmichKeyPermission(val id: String, val purpose: StringResource, val optional: Boolean)

/**
 * The permissions the key is asked for, in reading order: first the ones without which there is no
 * library, then the ones whose absence only turns off what they do — the self-hosting person who
 * would rather not let an app write to the server leaves those out.
 *
 * The ids are Immich's and are not translated — they are what the person looks for in its list. That
 * the list is complete is checked by `ImmichKeyPermissionsTest` against the permissions the contract
 * generated from the OpenAPI says the endpoints ask for.
 */
internal val ImmichKeyPermissionList = listOf(
    ImmichKeyPermission("user.read", Res.string.library_permission_user_read, optional = false),
    ImmichKeyPermission("asset.read", Res.string.library_permission_asset_read, optional = false),
    ImmichKeyPermission("asset.view", Res.string.library_permission_asset_view, optional = false),
    ImmichKeyPermission("asset.download", Res.string.library_permission_asset_download, optional = false),
    ImmichKeyPermission("album.read", Res.string.library_permission_album_read, optional = true),
    ImmichKeyPermission("album.create", Res.string.library_permission_album_create, optional = true),
    ImmichKeyPermission("albumAsset.create", Res.string.library_permission_album_asset_create, optional = true),
    ImmichKeyPermission("albumAsset.delete", Res.string.library_permission_album_asset_delete, optional = true),
    ImmichKeyPermission("album.update", Res.string.library_permission_album_update, optional = true),
    ImmichKeyPermission("album.delete", Res.string.library_permission_album_delete, optional = true),
    ImmichKeyPermission("asset.upload", Res.string.library_permission_asset_upload, optional = true),
    ImmichKeyPermission("stack.create", Res.string.library_permission_stack_create, optional = true),
    ImmichKeyPermission("stack.read", Res.string.library_permission_stack_read, optional = true),
    ImmichKeyPermission("stack.update", Res.string.library_permission_stack_update, optional = true),
    ImmichKeyPermission("stack.delete", Res.string.library_permission_stack_delete, optional = true),
    ImmichKeyPermission("asset.update", Res.string.library_permission_asset_update, optional = true),
    ImmichKeyPermission("asset.delete", Res.string.library_permission_asset_delete, optional = true),
    ImmichKeyPermission("asset.edit.get", Res.string.library_permission_asset_edit_get, optional = true),
    ImmichKeyPermission("asset.edit.create", Res.string.library_permission_asset_edit_create, optional = true),
    ImmichKeyPermission("asset.edit.delete", Res.string.library_permission_asset_edit_delete, optional = true),
    ImmichKeyPermission("person.read", Res.string.library_permission_person_read, optional = true),
    ImmichKeyPermission("map.read", Res.string.library_permission_map_read, optional = true),
    ImmichKeyPermission("memory.read", Res.string.library_permission_memory_read, optional = true),
)

/**
 * What the API key has to be able to do, said where it is asked for.
 *
 * Closed by default: whoever already has the key created does not need the list, and whoever does not
 * opens it before going to Immich. A key with too few permissions connects — Immich only refuses the
 * missing endpoint, later and far from here.
 */
@Composable
internal fun ImmichKeyPermissions(modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ImagoRadii.Small))
                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                .heightIn(min = ImagoSizes.TouchTarget)
                .padding(horizontal = ImagoSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Key, null, tint = ImagoColors.TextSecondary, modifier = Modifier.size(18.dp))
            Text(
                stringResource(Res.string.library_key_permissions_title),
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoColors.TextPrimary,
                modifier = Modifier.weight(1f).padding(horizontal = ImagoSpacing.Sm),
            )
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = ImagoColors.TextSecondary)
        }
        AnimatedVisibility(expanded) {
            Column(
                Modifier.padding(start = ImagoSpacing.Xs, end = ImagoSpacing.Xs, top = ImagoSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
            ) {
                Text(
                    stringResource(Res.string.library_key_permissions_where),
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextSecondary,
                )
                PermissionGroup(Res.string.library_key_permissions_required, ImmichKeyPermissionList.filterNot { it.optional })
                PermissionGroup(
                    Res.string.library_key_permissions_optional,
                    ImmichKeyPermissionList.filter { it.optional },
                    note = Res.string.library_key_permissions_optional_note,
                )
                Text(
                    stringResource(Res.string.library_key_permissions_all),
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun PermissionGroup(title: StringResource, permissions: List<ImmichKeyPermission>, note: StringResource? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Xs)) {
        Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = ImagoColors.TextPrimary)
        note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextTertiary) }
        permissions.forEach { permission ->
            Row {
                // Fixed width so the explanations line up in a column; it fits the longest,
                // `asset.download`, at the system's default font size.
                Text(
                    permission.id,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = ImagoColors.Gold,
                    modifier = Modifier.width(112.dp),
                )
                Spacer(Modifier.width(ImagoSpacing.Sm))
                Text(
                    stringResource(permission.purpose),
                    style = MaterialTheme.typography.bodySmall,
                    color = ImagoColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
