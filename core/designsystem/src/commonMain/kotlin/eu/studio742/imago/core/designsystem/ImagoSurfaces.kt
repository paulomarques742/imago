package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Blurs what is behind this surface.
 *
 * On Android it is `RenderEffect`, which requires Android 12, guaranteed by `minSdk` 31; on desktop
 * it is Skia's blur filter. [ImagoColors.Glass] stays opaque by taste, not by necessity.
 */
fun Modifier.imagoGlassBlur(radius: Dp = 20.dp): Modifier = graphicsLayer {
    val blur = radius.toPx()
    renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
}

/**
 * An icon over an arbitrary photo.
 *
 * White on white disappears, and the favourite or shared-album information cannot depend on the
 * photo underneath. The dark disc costs very little and guarantees the contrast.
 */
@Composable
fun PhotoOverlayIcon(
    modifier: Modifier = Modifier,
    size: Dp = ImagoSizes.IconDefault,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(ImagoSpacing.Xs)
            .size(size),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * The navigation or tool bar against the work area.
 *
 * The tone is [ImagoColors.NavBar] and the hairline on top closes the separation where the
 * difference in tone alone still hesitates.
 *
 * The system bar inset is applied here, and only to the content: the background runs under it,
 * which is what avoids the black strip between the light bar and the end of the screen.
 */
@Composable
fun ImagoNavBarSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().background(ImagoColors.NavBar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ImagoColors.BorderSubtle))
        Column(Modifier.navigationBarsPadding(), content = content)
    }
}
