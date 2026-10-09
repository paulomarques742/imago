package eu.studio742.imago.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.feature.shell.resources.Res
import eu.studio742.imago.feature.shell.resources.shell_about_title
import eu.studio742.imago.feature.shell.resources.shell_about_version
import eu.studio742.imago.feature.shell.resources.shell_news_title
import org.jetbrains.compose.resources.stringResource

/**
 * What changed, newest version first, always as the cards ([WhatsNewCards]). Closing it any way —
 * the last button, Skip, outside, back — counts as seen: it is shown once, not until the person
 * agrees to it.
 */
@Composable
internal fun WhatsNewDialog(releases: List<Release>, onDismiss: () -> Unit) {
    val newest = releases.firstOrNull() ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        WhatsNewCards(
            release = newest,
            older = releases.drop(1),
            onDone = onDismiss,
            modifier = Modifier
                .padding(ImagoSpacing.Lg)
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
        )
    }
}

/** The installed version in the settings, with the way back to what changed in it. */
@Composable
internal fun AboutSettingsCard() {
    val release = remember { currentRelease() }
    var showingNews by remember { mutableStateOf(false) }
    Surface(color = ImagoColors.SurfaceElevated, shape = RoundedCornerShape(ImagoRadii.Medium), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(ImagoSpacing.Lg), verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
                Icon(Icons.Outlined.Info, null, tint = ImagoColors.Ivory)
                Text(stringResource(Res.string.shell_about_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(Res.string.shell_about_version, InstalledVersion.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = ImagoColors.TextSecondary,
            )
            if (release != null) {
                TextButton(onClick = { showingNews = true }) { Text(stringResource(Res.string.shell_news_title)) }
            }
        }
    }
    if (showingNews && release != null) WhatsNewDialog(listOf(release)) { showingNews = false }
}
