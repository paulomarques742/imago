package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.uiPlural
import eu.studio742.imago.core.designsystem.i18n.uiText
import eu.studio742.imago.core.designsystem.resources.Res
import eu.studio742.imago.core.designsystem.resources.sync_done
import eu.studio742.imago.core.designsystem.resources.sync_failed
import eu.studio742.imago.core.designsystem.resources.sync_offline
import eu.studio742.imago.core.designsystem.resources.sync_pending
import eu.studio742.imago.core.designsystem.resources.sync_pending_offline
import eu.studio742.imago.core.designsystem.resources.sync_running
import kotlinx.coroutines.flow.Flow

/** What the indicator needs to know about sync. */
data class SyncIndicatorState(
    val running: Boolean,
    val pending: Int,
    val offline: Boolean,
    val failed: Boolean,
)

/** Where the state comes from, and what a tap does (open the account, where the detail is). */
class SyncIndicatorSource(val state: Flow<SyncIndicatorState?>, val onOpen: () -> Unit)

/** Null in an app without sync, or in previews: the indicator does not show. */
val LocalSyncIndicator = staticCompositionLocalOf<SyncIndicatorSource?> { null }

/**
 * A discreet indicator, of the same kind as [AutoSaveStatus]. Without an account it does not show,
 * and no state interrupts what is being done.
 */
@Composable
fun SyncIndicator(modifier: Modifier = Modifier) {
    val source = LocalSyncIndicator.current ?: return
    val state by source.state.collectAsState(initial = null)
    val current = state ?: return
    Text(
        text = syncIndicatorLabel(current).resolve(),
        style = MaterialTheme.typography.labelSmall,
        color = if (current.failed && !current.offline) ImagoColors.Danger else ImagoColors.TextTertiary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .clickable(role = Role.Button, onClick = source.onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

fun syncIndicatorLabel(state: SyncIndicatorState): UiText = when {
    state.running -> uiText(Res.string.sync_running)
    state.offline -> if (state.pending > 0) uiPlural(Res.plurals.sync_pending_offline, state.pending) else uiText(Res.string.sync_offline)
    state.failed -> uiText(Res.string.sync_failed)
    state.pending > 0 -> uiPlural(Res.plurals.sync_pending, state.pending)
    else -> uiText(Res.string.sync_done)
}
