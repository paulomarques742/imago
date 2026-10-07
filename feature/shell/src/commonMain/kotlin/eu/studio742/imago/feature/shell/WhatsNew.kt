package eu.studio742.imago.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
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
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.feature.shell.resources.Res
import eu.studio742.imago.feature.shell.resources.shell_about_title
import eu.studio742.imago.feature.shell.resources.shell_about_version
import eu.studio742.imago.feature.shell.resources.shell_news_close
import eu.studio742.imago.feature.shell.resources.shell_news_title
import eu.studio742.imago.feature.shell.resources.shell_news_version
import org.jetbrains.compose.resources.stringResource

/**
 * What changed, newest version first. Closing it any way — the button, outside, back — counts as
 * seen: it is shown once, not until the person agrees to it.
 */
@Composable
internal fun WhatsNewDialog(releases: List<Release>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.shell_news_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Lg),
            ) {
                releases.forEach { release ->
                    Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                        Text(
                            stringResource(Res.string.shell_news_version, release.version.toString()),
                            style = MaterialTheme.typography.titleSmall,
                            color = ImagoColors.TextPrimary,
                        )
                        release.notes.forEach { note ->
                            Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium, color = ImagoColors.Gold)
                                Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.shell_news_close)) } },
    )
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
