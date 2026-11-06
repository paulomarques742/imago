package eu.studio742.imago.feature.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ComposerMultiSelectionHint(modifier: Modifier = Modifier, editingGroup: Boolean = false, onFinishGroup: () -> Unit = {}) {
    val colors = ImagoUi.colors
    val shape = RoundedCornerShape(ImagoRadii.Small)
    Box(modifier.testTag("composer-multiselection-hint").semantics { liveRegion = LiveRegionMode.Polite }
        .background(colors.glass, shape).border(1.dp, colors.subtleBorder, shape)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.SelectAll, null, Modifier.size(20.dp), tint = colors.accent)
            Text(stringResource(if (editingGroup) Res.string.composer_edit_group_hint else Res.string.composer_multi_select_hint),
                color = colors.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (editingGroup) TextButton(onClick = onFinishGroup, modifier = Modifier.testTag("composer-finish-group")) {
                Text(stringResource(Res.string.composer_finish_group))
            }
        }
    }
}
