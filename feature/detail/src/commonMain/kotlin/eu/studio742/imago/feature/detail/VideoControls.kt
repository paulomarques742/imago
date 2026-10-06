package eu.studio742.imago.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSlider
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.feature.detail.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The video player as the detail's controls see it.
 *
 * The controls live in the detail's bottom strip, outside the pager, and not inside the player:
 * media3's controller was covered by that strip, and dragging its bar competed with the pager's. The
 * state is Compose's so the bar follows the video without further ceremony.
 */
@Stable
interface VideoPlayback {
    /** Playing, or loading to play right after. */
    val isPlaying: Boolean
    val hasEnded: Boolean
    val positionMs: Long

    /** Zero while the player does not know how long the video lasts. */
    val durationMs: Long

    fun play()
    fun pause()

    /**
     * [precise] false jumps to the nearest keyframe: it is what is wanted while dragging the bar,
     * especially in a video from the server, where every exact seek forces decoding from the previous
     * keyframe. On release, it seeks to the exact place.
     */
    fun seekTo(positionMs: Long, precise: Boolean = true)
}

internal const val VIDEO_SKIP_MS = 10_000L

/** Where a jump of [deltaMs] goes: never before the start nor after the end, when known. */
internal fun skipTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val target = (positionMs + deltaMs).coerceAtLeast(0L)
    return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
}

/**
 * `1:05`, or `1:02:03` for a video of an hour or more. The hours are decided by the duration and not
 * by the position, so the elapsed and total time always have the same shape and the bar does not
 * jump in width when passing the first hour.
 */
internal fun formatPlaybackTime(ms: Long, durationMs: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (durationMs >= 3_600_000L) "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
    else "${totalSeconds / 60}:${seconds.twoDigits()}"
}

private fun Long.twoDigits() = toString().padStart(2, '0')

@Composable
internal fun VideoControls(playback: VideoPlayback, modifier: Modifier = Modifier) {
    // While the finger is on the bar, it is what rules the position shown: the player is still looking
    // for the frame, and showing its position made the thumb run away from the finger.
    var scrubMs by remember(playback) { mutableStateOf<Long?>(null) }
    var resumeAfterScrub by remember(playback) { mutableStateOf(false) }
    val durationMs = playback.durationMs
    val shownMs = scrubMs ?: playback.positionMs
    val elapsed = formatPlaybackTime(shownMs, durationMs)
    val total = formatPlaybackTime(durationMs, durationMs)
    // Fixed-width digits: without this the time changed width every second and the bar, next to it,
    // trembled.
    val timeStyle = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum"))
    val positionLabel = stringResource(Res.string.detail_video_position)
    val positionState = stringResource(Res.string.detail_video_position_state, elapsed, total)

    Column(modifier.fillMaxWidth().padding(horizontal = ImagoSpacing.Lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(elapsed, style = timeStyle, color = ImagoColors.TextSecondary)
            ImagoSlider(
                value = shownMs.coerceAtMost(durationMs).toFloat(),
                onValueChange = { value ->
                    if (scrubMs == null) {
                        resumeAfterScrub = playback.isPlaying
                        playback.pause()
                    }
                    scrubMs = value.toLong()
                    playback.seekTo(value.toLong(), precise = false)
                },
                onValueChangeFinished = {
                    scrubMs?.let { playback.seekTo(it) }
                    scrubMs = null
                    if (resumeAfterScrub) playback.play()
                },
                range = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                neutral = 0f,
                enabled = durationMs > 0L,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = ImagoSpacing.Sm)
                    .semantics {
                        contentDescription = positionLabel
                        stateDescription = positionState
                    },
            )
            Text(total, style = timeStyle, color = ImagoColors.TextSecondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xl, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { playback.seekTo(skipTarget(playback.positionMs, -VIDEO_SKIP_MS, durationMs)) }) {
                Icon(Icons.Filled.Replay10, stringResource(Res.string.detail_video_back), tint = ImagoColors.TextPrimary)
            }
            IconButton(onClick = { if (playback.isPlaying) playback.pause() else playback.play() }) {
                Icon(
                    imageVector = when {
                        playback.isPlaying -> Icons.Filled.Pause
                        playback.hasEnded -> Icons.Filled.Replay
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = when {
                        playback.isPlaying -> stringResource(Res.string.detail_video_pause)
                        playback.hasEnded -> stringResource(Res.string.detail_video_replay)
                        else -> stringResource(Res.string.detail_video_play)
                    },
                    tint = ImagoColors.TextPrimary,
                    modifier = Modifier.size(ImagoSizes.IconHero),
                )
            }
            IconButton(onClick = { playback.seekTo(skipTarget(playback.positionMs, VIDEO_SKIP_MS, durationMs)) }) {
                Icon(Icons.Filled.Forward10, stringResource(Res.string.detail_video_forward), tint = ImagoColors.TextPrimary)
            }
        }
    }
}
