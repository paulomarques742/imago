package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.resources.*
import org.jetbrains.compose.resources.stringResource

/** State belongs to the screen so opening a library form does not discard its expanded category. */
@Composable
fun CollapsibleSettingsCard(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val stateLabel = stringResource(if (expanded) Res.string.settings_category_expanded else Res.string.settings_category_collapsed)
    Surface(color = ImagoColors.SurfaceElevated, shape = RoundedCornerShape(ImagoRadii.Medium), modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onExpandedChange(!expanded) }
                    .semantics { stateDescription = stateLabel }
                    .heightIn(min = 64.dp).padding(horizontal = ImagoSpacing.Lg, vertical = ImagoSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md),
            ) {
                Icon(icon, null, tint = ImagoColors.Ivory)
                Text(title, style = MaterialTheme.typography.titleMedium, color = ImagoColors.TextPrimary, modifier = Modifier.weight(1f))
                Canvas(Modifier.size(24.dp).rotate(if (expanded) 180f else 0f)) {
                    val chevron = Path().apply {
                        moveTo(size.width / 3, size.height * 5 / 12)
                        lineTo(size.width / 2, size.height * 7 / 12)
                        lineTo(size.width * 2 / 3, size.height * 5 / 12)
                    }
                    drawPath(chevron, ImagoColors.TextSecondary, style = Stroke(width = 2.dp.toPx()))
                }
            }
            if (expanded) Column(
                Modifier.fillMaxWidth().padding(start = ImagoSpacing.Lg, end = ImagoSpacing.Lg, bottom = ImagoSpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md), content = content,
            )
        }
    }
}
