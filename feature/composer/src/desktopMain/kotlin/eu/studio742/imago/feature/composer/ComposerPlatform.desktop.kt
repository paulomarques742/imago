package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
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
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.data.LocalDesktopDataGraph
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.immich.IMMICH_API_KEY_HEADER
import java.io.File
import java.net.URI

@Composable
actual fun composerEditorViewModel(): ComposerEditorViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel {
        ComposerEditorViewModel(
            graph.compositions, graph.compositionTemplates, graph.compositionMedia, graph.recipes,
            DesktopCompositionExports(graph.compositionMedia), graph.brandKit,
        )
    }
}

@Composable
actual fun composerHubViewModel(): ComposerHubViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { ComposerHubViewModel(graph.compositions, graph.compositionTemplates, graph.compositionMedia) }
}

@Composable
actual fun addToCompositionViewModel(): AddToCompositionViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { AddToCompositionViewModel(graph.compositions, graph.recipes) }
}

@Composable
actual fun brandKitViewModel(): BrandKitViewModel {
    val graph = LocalDesktopDataGraph.current
    return viewModel { BrandKitViewModel(graph.brandKit, graph.compositionMedia) }
}

/** On the computer, the pages are already in a folder: sharing is opening it in Explorer. */
@Composable
actual fun rememberShareExports(): (List<String>, List<String>) -> Unit = remember {
    { locations, _ ->
        locations.firstOrNull()?.let { path ->
            runCatching { java.awt.Desktop.getDesktop().open(File(path).parentFile) }
        }
        Unit
    }
}

@Composable
actual fun ProxyVideoPlayer(path: String, modifier: Modifier) = ExternalPlayerBox(modifier) { File(path) }

@Composable
actual fun ElementVideoPlayer(element: CompositionElement.Video, url: String, apiKey: String, modifier: Modifier) =
    ExternalPlayerBox(modifier) { localVideo(url, apiKey) }

/**
 * The video opens in the Windows player: the app does not have a player of its own on desktop yet. The
 * stage still shows the element's position and framing; what is missing is the video running inside.
 */
@Composable
private fun ExternalPlayerBox(modifier: Modifier, file: suspend () -> File) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
            if (busy) CircularProgressIndicator(color = ImagoColors.Ivory)
            else Button(onClick = {
                busy = true; failed = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { java.awt.Desktop.getDesktop().open(file()) } }
                        .onFailure { failed = true }
                    busy = false
                }
            }) {
                Icon(Icons.Outlined.PlayCircle, null)
                Text(stringResource(Res.string.composer_play_in_windows))
            }
            if (failed) Text(stringResource(Res.string.composer_video_open_failed), style = MaterialTheme.typography.bodySmall, color = ImagoColors.TextSecondary)
        }
    }
}

private val http by lazy { OkHttpClient() }

private fun localVideo(url: String, apiKey: String): File {
    if (url.startsWith("file:")) return File(URI(url))
    val target = File.createTempFile("imago-video-", ".mp4").apply { deleteOnExit() }
    val request = Request.Builder().url(url).apply { if (apiKey.isNotBlank()) header(IMMICH_API_KEY_HEADER, apiKey) }.build()
    http.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "The server refused the video (${response.code})." }
        target.outputStream().use { response.body.byteStream().copyTo(it) }
    }
    return target
}
