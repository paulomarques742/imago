package eu.studio742.imago.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

/**
 * A change of appearance as a circle opening from where it was asked for.
 *
 * The screen as it was is kept as a picture and laid over the app, which already has the new theme, and
 * a hole in the picture grows until nothing of it is left. The app underneath is the real one the whole
 * time: nothing is recreated, and it stays alive while the circle opens.
 */
@Stable
class ThemeReveal internal constructor(
    private val scope: CoroutineScope,
    private val reducedMotion: Boolean,
) {
    internal var picture by mutableStateOf<ImageBitmap?>(null)
        private set
    internal var origin by mutableStateOf(Offset.Zero)
        private set
    internal val opening = Animatable(0f)

    /** Set while the next frame has to be kept; completed by the draw that kept it. */
    internal var capture by mutableStateOf<CompletableDeferred<Unit>?>(null)
        private set
    internal var takePicture: suspend () -> ImageBitmap? = { null }

    private var running = false

    /** Runs [change], which switches the theme, with the circle opening from [from], in the root's pixels. */
    fun reveal(from: Offset, change: () -> Unit) {
        if (reducedMotion || running) {
            change()
            return
        }
        running = true
        scope.launch {
            try {
                val drawn = CompletableDeferred<Unit>()
                capture = drawn
                drawn.await()
                capture = null
                val kept = takePicture()
                origin = from
                opening.snapTo(0f)
                picture = kept
                change()
                if (kept != null) opening.animateTo(1f, tween(REVEAL_MILLIS, easing = FastOutSlowInEasing))
            } finally {
                picture = null
                running = false
            }
        }
    }
}

/** How far the circle has to go to uncover the whole of [size]: to the corner furthest from [origin]. */
internal fun revealRadius(origin: Offset, size: Size): Float =
    hypot(max(origin.x, size.width - origin.x), max(origin.y, size.height - origin.y))

/** The app's [ThemeReveal], given by [ImagoTheme]. */
val LocalThemeReveal = staticCompositionLocalOf<ThemeReveal?> { null }

/** A [ThemeReveal] for one part of the screen, drawn by a [ThemeRevealBox]; the app's own is [LocalThemeReveal]. */
@Composable
fun rememberThemeReveal(): ThemeReveal {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    return remember(scope, reduced) { ThemeReveal(scope, reduced) }
}

/**
 * Where [reveal] opens its circle: it keeps this box as it was and uncovers the new look under it.
 * The origin of [ThemeReveal.reveal] is in this box's pixels.
 */
@Composable
fun ThemeRevealBox(reveal: ThemeReveal, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val layer = rememberGraphicsLayer()
    reveal.takePicture = { layer.toImageBitmap() }
    Box(
        modifier.drawWithContent {
            val capture = reveal.capture
            if (capture != null) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                capture.complete(Unit)
            } else {
                drawContent()
            }
            val picture = reveal.picture ?: return@drawWithContent
            val hole = Path().apply {
                addOval(Rect(reveal.origin, revealRadius(reveal.origin, size) * reveal.opening.value))
            }
            clipPath(hole, ClipOp.Difference) { drawImage(picture) }
        },
        content = content,
    )
}

@Composable
internal fun ThemeRevealHost(content: @Composable () -> Unit) {
    val reveal = rememberThemeReveal()
    CompositionLocalProvider(LocalThemeReveal provides reveal) {
        ThemeRevealBox(reveal, Modifier.fillMaxSize()) { content() }
    }
}

private const val REVEAL_MILLIS = 620
