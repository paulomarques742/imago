package eu.studio742.imago.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import java.io.File

/** The detail's ViewModel: through Hilt on Android, through the app's data layer on desktop. */
@Composable
expect fun detailViewModel(): DetailViewModel

/**
 * What to do with the original downloaded for sharing: Android's share menu, Windows's "Save copy"
 * dialog.
 */
@Composable
expect fun rememberShareFile(): (file: File, mimeType: String) -> Unit

/** The menu entry that saves a copy: "Save to gallery" on the phone, "Save copy…" on the computer. */
expect val SaveToDeviceLabel: StringResource

/**
 * The player of the open video, for the detail's controls to drive it; `null` with a null [url], or
 * on a platform without a player of its own. The API key goes in the player's requests: the stream is
 * authenticated.
 */
@Composable
expect fun rememberVideoPlayback(url: String?, apiKey: String): VideoPlayback?

/** The open page's video: [playback]'s image, or, without it, whatever the platform does with [url]. */
@Composable
expect fun DetailVideoPlayer(playback: VideoPlayback?, url: String, apiKey: String, modifier: Modifier = Modifier)