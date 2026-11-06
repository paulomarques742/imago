package eu.studio742.imago.feature.composer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoUi

/** Fade the tools themselves, so translucent cards keep showing the composition beneath them. */
@Composable
internal fun ComposerToolRow(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(2.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val mask = ImagoUi.colors.text.copy(alpha = 1f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val preferred = LocalComposerContextToolWidth.current.takeIf { it.isSpecified } ?: 80.dp
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val spacing = horizontalArrangement.spacing
        val direction = LocalLayoutDirection.current
        val available = maxWidth - contentPadding.calculateLeftPadding(direction) - contentPadding.calculateRightPadding(direction)
        val columns = (available / (preferred + spacing)).toInt().coerceAtLeast(1)
        // Leave more than half of the next button in view, including the centre of its icon.
        val toolWidth = minOf(preferred, (available - spacing * columns) / (columns + .65f)).coerceAtLeast(ImagoSizes.TouchTarget)
        CompositionLocalProvider(LocalComposerContextToolWidth provides toolWidth) {
            Row(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val edge = minOf(80.dp.toPx(), size.width / 2f)
                    val left = if (rtl) scrollState.canScrollForward else scrollState.canScrollBackward
                    val right = if (rtl) scrollState.canScrollBackward else scrollState.canScrollForward
                    // A fully transparent edge erased the clipped icon and made overflow look like empty space.
                    if (left) drawRect(Brush.horizontalGradient(listOf(mask.copy(alpha = .35f), mask), 0f, edge),
                        size = Size(edge, size.height), blendMode = BlendMode.DstIn)
                    if (right) drawRect(Brush.horizontalGradient(listOf(mask, mask.copy(alpha = .35f)), size.width - edge, size.width),
                        topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height), blendMode = BlendMode.DstIn)
                }.horizontalScroll(scrollState).padding(contentPadding),
                horizontalArrangement = horizontalArrangement, verticalAlignment = verticalAlignment, content = content)
        }
    }
}
