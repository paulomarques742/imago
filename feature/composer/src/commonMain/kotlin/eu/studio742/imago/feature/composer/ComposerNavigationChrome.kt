package eu.studio742.imago.feature.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.studio742.imago.core.designsystem.ImagoMotion
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSizes
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ImagoUi
import eu.studio742.imago.core.designsystem.imagoTween
import eu.studio742.imago.feature.composer.resources.Res
import eu.studio742.imago.feature.composer.resources.composer_add_elements
import org.jetbrains.compose.resources.stringResource

private val NavigationHeight = 56.dp
private val AddSize = 64.dp
private val CentreSlotWidth = 80.dp
internal val LocalComposerContextToolWidth = staticCompositionLocalOf { Dp.Unspecified }

/** The measured area stays in place while the stage gesture fades its controls. */
@Composable
internal fun ComposerNavigationChrome(
    contextVisible: Boolean,
    immersive: Boolean,
    contentAlpha: Float,
    onHeightChanged: (Dp) -> Unit,
    context: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(
        modifier.fillMaxWidth().onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) }
            .alpha(contentAlpha),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = contextVisible,
            enter = fadeIn(imagoTween(ImagoMotion.Fast)) +
                slideInVertically(imagoTween(ImagoMotion.Default)) { it } +
                expandVertically(imagoTween(ImagoMotion.Default), expandFrom = Alignment.Bottom, clip = false),
            exit = fadeOut(imagoTween(ImagoMotion.Fast)) +
                slideOutVertically(imagoTween(ImagoMotion.Default)) { it } +
                shrinkVertically(imagoTween(ImagoMotion.Default), shrinkTowards = Alignment.Bottom, clip = false),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ComposerContextCard(immersive = immersive, content = context)
                Spacer(Modifier.height(ImagoSpacing.Sm))
            }
        }
        // The card reveals from behind this surface, never on top of the main controls.
        Box(Modifier.fillMaxWidth().zIndex(1f)) { navigation() }
    }
}

/** Bounded wrapping keeps short menus compact and longer menus within the window. */
@Composable
internal fun ComposerContextCard(
    immersive: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = ImagoUi.colors
    val background by animateColorAsState(if (immersive) Color.Transparent else colors.glass,
        imagoTween(ImagoMotion.Instant), label = "composerContextBackground")
    val border by animateColorAsState(if (immersive) Color.Transparent else colors.subtleBorder,
        imagoTween(ImagoMotion.Instant), label = "composerContextBorder")
    val shape = RoundedCornerShape(ImagoRadii.Panel)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = ImagoSpacing.Lg), contentAlignment = Alignment.BottomCenter) {
        // Match the bar's side slots on a phone, but avoid stretching a few tools across a tablet.
        val toolWidth = ((maxWidth + ImagoSpacing.Lg * 2 - CentreSlotWidth) / 4f)
            .coerceIn(ImagoSizes.TouchTarget, CentreSlotWidth)
        Surface(
            color = background,
            shape = shape,
            modifier = modifier.widthIn(max = maxWidth)
                .animateContentSize(imagoTween(ImagoMotion.Default), alignment = Alignment.BottomCenter)
                .border(1.dp, border, shape).testTag("composer-context-card"),
        ) {
            CompositionLocalProvider(LocalComposerContextToolWidth provides toolWidth) {
                Column(Modifier.padding(horizontal = ImagoSpacing.Sm, vertical = ImagoSpacing.Xs),
                    horizontalAlignment = Alignment.CenterHorizontally) { content() }
            }
        }
    }
}

/** Equal side slots and an independent centre make Add immune to labels and optional actions. */
@Composable
internal fun ComposerNavigationBar(
    onAdd: () -> Unit,
    layers: @Composable () -> Unit,
    background: @Composable () -> Unit,
    pages: @Composable () -> Unit,
    more: @Composable () -> Unit,
    immersive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = ImagoUi.colors
    val addLabel = stringResource(Res.string.composer_add_elements)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val surface by animateColorAsState(if (immersive) Color.Transparent else colors.navigation,
        imagoTween(ImagoMotion.Instant), label = "composerNavigationBackground")
    Box(modifier.fillMaxWidth().height(AddSize + bottomInset).testTag("composer-navigation")) {
        Surface(
            color = surface,
            shape = RoundedCornerShape(topStart = ImagoRadii.Panel, topEnd = ImagoRadii.Panel),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(NavigationHeight + bottomInset)
                .testTag("composer-navigation-surface"),
        ) {}
        Row(Modifier.fillMaxWidth().height(NavigationHeight).offset(y = AddSize - NavigationHeight),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { layers() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { background() }
            Spacer(Modifier.width(CentreSlotWidth))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { pages() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { more() }
        }
        Surface(
            onClick = onAdd,
            shape = RoundedCornerShape(ImagoRadii.Large),
            color = colors.accent,
            contentColor = colors.onAccent,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopCenter).size(AddSize).testTag("composer-add")
                .semantics { contentDescription = addLabel },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Add, null, Modifier.size(28.dp))
                Text(addLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
