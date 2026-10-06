package eu.studio742.imago.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.studio742.imago.core.designsystem.resources.Res
import eu.studio742.imago.core.designsystem.resources.autosave_saved
import eu.studio742.imago.core.designsystem.resources.autosave_saving
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Auto-save states.
 *
 * There is no Save button, so the only sign that the edit is safe is this. Showing only `Saving…`
 * leaves the user with no confirmation at all when the work finishes — hence the short
 * confirmation, which then withdraws on its own so as not to compete with the photo.
 */
private const val SAVED_VISIBLE_MS = 1_600L

@Composable
fun AutoSaveStatus(isSaving: Boolean, modifier: Modifier = Modifier) {
    var showSaved by remember { mutableStateOf(false) }
    var wasSaving by remember { mutableStateOf(false) }

    LaunchedEffect(isSaving) {
        if (isSaving) {
            wasSaving = true
            showSaved = false
        } else if (wasSaving) {
            wasSaving = false
            showSaved = true
            delay(SAVED_VISIBLE_MS)
            showSaved = false
        }
    }

    AnimatedVisibility(
        visible = isSaving || showSaved,
        modifier = modifier,
        enter = fadeIn(imagoTween(ImagoMotion.Fast)),
        exit = fadeOut(imagoTween(ImagoMotion.Default)),
    ) {
        Text(
            text = stringResource(if (isSaving) Res.string.autosave_saving else Res.string.autosave_saved),
            style = MaterialTheme.typography.labelSmall,
            color = if (isSaving) ImagoColors.TextSecondary else ImagoColors.TextTertiary,
        )
    }
}
