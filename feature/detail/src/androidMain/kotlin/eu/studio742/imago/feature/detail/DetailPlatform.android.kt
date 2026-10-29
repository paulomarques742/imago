package eu.studio742.imago.feature.detail

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.studio742.imago.core.data.LibraryRepository
import eu.studio742.imago.core.data.RecipeRepository
import eu.studio742.imago.feature.detail.resources.*
import eu.studio742.imago.feature.editor.EditorExporter
import org.jetbrains.compose.resources.StringResource
import java.io.File
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class HiltDetailViewModel @Inject constructor(
    @ApplicationContext context: Context,
    library: LibraryRepository,
    recipes: RecipeRepository,
    exporter: EditorExporter,
    rotation: eu.studio742.imago.feature.library.PhotoRotation,
) : DetailViewModel(library, recipes, exporter, File(context.cacheDir, "share"), rotation)

@Composable
actual fun detailViewModel(): DetailViewModel = hiltViewModel<HiltDetailViewModel>()

actual val SaveToDeviceLabel: StringResource get() = Res.string.detail_save_to_gallery

/** Android's "Set as" (`ATTACH_DATA`): the wallpaper, a contact, whatever the phone offers. */
@Composable
actual fun rememberSetAs(): ((File, String) -> Unit)? {
    val context = LocalContext.current
    return remember(context) {
        { file, mimeType ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_ATTACH_DATA).apply {
                    setDataAndType(uri, mimeType)
                    putExtra("mimeType", mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                null,
            ))
        }
    }
}

/** The Android share menu, with the file served by this module's FileProvider. */
@Composable
actual fun rememberShareFile(): (File, String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { file, mimeType ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                null,
            ))
        }
    }
}

/**
 * The video, as Immich streams it.
 *
 * The API key goes in the header of every request of the player: the playback URL is authenticated
 * like the rest of the API, and without this ExoPlayer got a 401 instead of the stream.
 */
// `DefaultMediaSourceFactory` and `PlayerView` are still marked unstable by media3. They are the
// normal path to play an authenticated stream and there is no stable equivalent; the opt-in is
// declared here, in the only place that uses them, instead of silenced in the whole module's lint.
//
// It has to be androidx's `OptIn` and not Kotlin's: `UnsafeOptInUsageError` is an Android lint check,
// and it only recognises the Java annotation with `markerClass`.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun rememberVideoPlayback(url: String?, apiKey: String): VideoPlayback? {
    if (url == null) return null
    val context = LocalContext.current
    val playback = remember(url, apiKey) {
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(eu.studio742.imago.core.immich.ImmichHttp.client)
            .setDefaultRequestProperties(if (apiKey.isBlank()) emptyMap() else mapOf(eu.studio742.imago.core.immich.IMMICH_API_KEY_HEADER to apiKey))
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, http)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
                playWhenReady = true
            }
        ExoVideoPlayback(player)
    }
    DisposableEffect(playback) { onDispose(playback::release) }
    // ExoPlayer does not say when the position moves, only when the state changes; the bar reads it at
    // this rate, which is enough for the thumb to move without visible jumps.
    LaunchedEffect(playback) {
        while (true) {
            playback.sync()
            delay(POSITION_REFRESH_MS)
        }
    }
    return playback
}

private const val POSITION_REFRESH_MS = 100L

actual val PlaysMotionInApp: Boolean = true

/**
 * The movement, on top of the photo it belongs to.
 *
 * The player is made when the finger first presses (the long press is the time it has to start the
 * stream) and lives while the page does: holding again replays it without asking for it again. The
 * view draws on a texture with a clear shutter (`motion_player`), so until the first frame arrives
 * what shows is still the photo and not a black rectangle.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun MotionPlayer(
    url: String,
    apiKey: String,
    playing: Boolean,
    onEnded: () -> Unit,
    onFailed: (Throwable) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onFailed)
    val player = remember(url, apiKey) {
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(eu.studio742.imago.core.immich.ImmichHttp.client)
            .setDefaultRequestProperties(if (apiKey.isBlank()) emptyMap() else mapOf(eu.studio742.imago.core.immich.IMMICH_API_KEY_HEADER to apiKey))
        val source = EmbeddedMotionDataSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(context, http))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(source))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                // Always silent: it is the photo moving, not a video being watched.
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                eu.studio742.imago.core.model.diagnostics.LocalDiagnostics.failure(
                    eu.studio742.imago.core.model.diagnostics.DiagnosticArea.VIDEO, error,
                    eu.studio742.imago.core.model.diagnostics.DiagnosticOperation.LIVE_PHOTO)
                failed(error)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) ended()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, playing) {
        if (playing) {
            // A player that failed before is asked again: holding once more is the way to retry.
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.seekTo(0L)
            player.play()
        } else {
            player.pause()
        }
    }
    if (playing) {
        AndroidView(
            factory = {
                (android.view.LayoutInflater.from(it).inflate(R.layout.motion_player, null) as PlayerView).apply {
                    this.player = player
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = modifier,
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
actual fun DetailVideoPlayer(playback: VideoPlayback?, url: String, apiKey: String, modifier: Modifier) {
    val exo = playback as? ExoVideoPlayback ?: return
    val player = exo.player
    AndroidView(
        factory = {
            PlayerView(it).apply {
                // The controls are the detail's (`VideoControls`); media3's sat under the bottom
                // strip. Only the buffering indicator stays, which says why the image stopped after a
                // seek in a server video.
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                this.player = player
            }
        },
        update = {
            it.player = player
            // Watching a video nobody touches the screen; without this it would turn off after the
            // system's idle timeout in the middle of the video. Paused, it can turn off again.
            it.keepScreenOn = exo.isPlaying
        },
        modifier = modifier,
    )
}

/**
 * Reads a Motion Photo's video from inside the photo's file: an [EmbeddedMotion] url becomes the
 * photo's own url, shifted to where the video starts and cut where it ends, so the MP4 extractor sees
 * a file of its own. Any other url goes through untouched.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class EmbeddedMotionDataSource(private val upstream: androidx.media3.datasource.DataSource) :
    androidx.media3.datasource.DataSource by upstream {
    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
        val motion = eu.studio742.imago.core.model.EmbeddedMotion.parse(dataSpec.uri.toString()) ?: return upstream.open(dataSpec)
        val left = motion.length - dataSpec.position
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        return upstream.open(
            dataSpec.buildUpon()
                .setUri(android.net.Uri.parse(motion.fileUrl))
                .setPosition(motion.start + dataSpec.position)
                .setLength(length)
                .build(),
        )
    }

    class Factory(private val upstream: androidx.media3.datasource.DataSource.Factory) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource(): androidx.media3.datasource.DataSource = EmbeddedMotionDataSource(upstream.createDataSource())
    }
}

private class ExoVideoPlayback(val player: ExoPlayer) : VideoPlayback {
    override var isPlaying by mutableStateOf(false)
        private set
    override var hasEnded by mutableStateOf(false)
        private set
    override var positionMs by mutableLongStateOf(0L)
        private set
    override var durationMs by mutableLongStateOf(0L)
        private set

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            eu.studio742.imago.core.model.diagnostics.LocalDiagnostics.failure(
                eu.studio742.imago.core.model.diagnostics.DiagnosticArea.VIDEO, error,
                eu.studio742.imago.core.model.diagnostics.DiagnosticOperation.VIDEO)
        }
        override fun onEvents(player: Player, events: Player.Events) = sync()
    }

    init {
        player.addListener(listener)
    }

    fun sync() {
        hasEnded = player.playbackState == Player.STATE_ENDED
        // The intention and not ExoPlayer's `isPlaying`: that one drops to false while the stream loads
        // after a seek, and the button flickered to "play" on every seek.
        isPlaying = player.playWhenReady && !hasEnded
        durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        positionMs = player.currentPosition.coerceAtLeast(0L)
    }

    override fun play() {
        // Once finished, ExoPlayer stays stopped at the end; pressing play means wanting to watch again.
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        player.play()
    }

    override fun pause() = player.pause()

    // `SeekParameters` is still unstable in media3, with no stable way to choose between an exact seek
    // and the nearest keyframe.
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun seekTo(positionMs: Long, precise: Boolean) {
        player.setSeekParameters(if (precise) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(positionMs)
        sync()
    }

    fun release() {
        player.removeListener(listener)
        player.release()
    }
}

@Composable
actual fun HideSystemBars(hidden: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, hidden) {
        val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (hidden) {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(bars)
        }
        onDispose { if (hidden) controller?.show(bars) }
    }
}
