package eu.studio742.imago.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple

/**
 * The translucent round button that floats over the photo.
 *
 * It appears on every immersive screen: back, favourite, undo, menu. The dark disc exists for the
 * same reason as [PhotoOverlayIcon]'s — a white icon over a light sky disappears, and no navigation
 * action can depend on the photo underneath.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ImagoColors.BrandWhite,
    size: Dp = ImagoSizes.TouchTarget,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, ImagoColors.BorderSubtle, CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = size / 2),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else ImagoColors.TextDisabled,
            modifier = Modifier.size(ImagoSizes.IconLarge),
        )
    }
}

/**
 * A row of pill chips with one active choice.
 *
 * The active chip is the inverse of the others — light background, dark text — because over an
 * almost black background a difference in opacity is not enough to tell it apart at first glance.
 */
@Composable
fun <T> ImagoChipRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = ImagoSpacing.Lg,
    /** A chip with an icon shows it in place of the label, which becomes what is read aloud. */
    icon: (T) -> ImageVector? = { null },
) {
    val scrollState = rememberScrollState()
    val itemOffsets = remember { mutableStateMapOf<T, Int>() }
    var viewportWidth by remember { mutableIntStateOf(0) }
    // The active chip can change without a tap — when coming back from another screen, for example.
    // If it is out of view, the row tells nothing.
    LaunchedEffect(selected, itemOffsets[selected], viewportWidth) {
        val offset = itemOffsets[selected] ?: return@LaunchedEffect
        if (viewportWidth == 0) return@LaunchedEffect
        scrollState.animateScrollTo((offset - viewportWidth / 3).coerceIn(0, scrollState.maxValue))
    }
    Row(
        modifier = modifier
            .onSizeChanged { viewportWidth = it.width }
            .horizontalScroll(scrollState)
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .onPlaced { itemOffsets[option] = it.positionInParent().x.toInt() }
                    .clip(RoundedCornerShape(ImagoRadii.Pill))
                    .background(if (isSelected) ImagoColors.BrandWhite else ImagoColors.Charcoal)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .sizeIn(minHeight = ImagoSizes.TouchTarget)
                    .padding(horizontal = ImagoSpacing.Xl),
                contentAlignment = Alignment.Center,
            ) {
                val color = if (isSelected) ImagoColors.BrandBlack else ImagoColors.TextSecondary
                val vector = icon(option)
                if (vector != null) {
                    Icon(vector, contentDescription = label(option), tint = color, modifier = Modifier.size(20.dp))
                } else {
                    Text(text = label(option), style = MaterialTheme.typography.titleSmall, color = color)
                }
            }
        }
    }
}

/**
 * Underlined tabs.
 *
 * Unlike [ImagoChipRow], which filters within a view, this switches views — which is why the
 * indicator is a line and not a capsule: it weighs less and does not compete with the chips when both
 * appear on the same screen.
 */
@Composable
fun <T> ImagoTabRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ImagoSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Xxl),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Column(
                modifier = Modifier
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .sizeIn(minWidth = ImagoSizes.TouchTarget, minHeight = ImagoSizes.TouchTarget),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) ImagoColors.TextPrimary else ImagoColors.TextTertiary,
                )
                Box(
                    Modifier
                        .padding(top = ImagoSpacing.Sm)
                        .size(width = 28.dp, height = 2.dp)
                        .background(if (isSelected) ImagoColors.BrandWhite else Color.Transparent),
                )
            }
        }
    }
}

/** The handle that tells the panel can be dragged. Decorative: the gesture lives in the panel, not here. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.padding(vertical = ImagoSpacing.Md), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(ImagoRadii.Pill))
                .background(ImagoColors.BorderStrong),
        )
    }
}
