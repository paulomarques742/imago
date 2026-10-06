package eu.studio742.imago.feature.detail

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.designsystem.i18n.resolve
import eu.studio742.imago.core.designsystem.i18n.toUiText
import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.detail.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.NativeDialogs
import eu.studio742.imago.core.immich.IMMICH_API_KEY_HEADER
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Composable
actual fun detailViewModel(): DetailViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel {
        DetailViewModel(graph.library, graph.recipes, Files.createTempDirectory("imago-share").toFile())
    }
}

/** On desktop, sharing is saving a copy of the original where the user chooses. */
@Composable
actual fun rememberShareFile(): (File, String) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(scope) {
        { file, _ ->
            scope.launch {
                val destination = NativeDialogs.saveFile(file.name) ?: return@launch
                withContext(Dispatchers.IO) { Files.copy(file.toPath(), destination, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }
}

/**
 * The video opens in the Windows player: the app does not have a player of its own on desktop yet. A
 * video from a folder opens directly; an Immich one is downloaded first, with the key, to a temporary
 * folder.
 */
@Composable
actual fun rememberVideoPlayback(url: String?, apiKey: String): VideoPlayback? = null

@Composable
actual fun DetailVideoPlayer(playback: VideoPlayback?, url: String, apiKey: String, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var busy by remember(url) { mutableStateOf(false) }
    var error by remember(url) { mutableStateOf<UiText?>(null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Md)) {
            if (busy) CircularProgressIndicator(color = ImagoColors.Ivory)
            else Button(onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().open(localVideo(url, apiKey)) } }
                        .onFailure { error = it.toUiText(Res.string.detail_video_open_failed) }
                    busy = false
                }
            }) {
                Icon(Icons.Outlined.PlayCircle, null)
                Text(stringResource(Res.string.detail_play_in_windows))
            }
            error?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium, color = ImagoColors.TextSecondary) }
        }
    }
}

private val http by lazy { OkHttpClient() }

private fun localVideo(url: String, apiKey: String): File {
    if (url.startsWith("file:")) return File(URI(url))
    val target = File.createTempFile("imago-video-", ".mp4").apply { deleteOnExit() }
    val request = Request.Builder().url(url).apply { if (apiKey.isNotBlank()) header(IMMICH_API_KEY_HEADER, apiKey) }.build()
    http.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "Immich refused the video (${response.code})." }
        target.outputStream().use { response.body.byteStream().copyTo(it) }
    }
    return target
}
