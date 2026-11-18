package eu.studio742.imago.feature.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import eu.studio742.imago.core.designsystem.LocalReducedMotion
import eu.studio742.imago.feature.shell.resources.shell_news_support_action
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.studio742.imago.core.designsystem.ImagoNewsDark
import eu.studio742.imago.core.designsystem.ImagoNewsLight
import eu.studio742.imago.core.designsystem.ImagoNewsPalette
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.designsystem.ThemeRevealBox
import eu.studio742.imago.core.designsystem.rememberThemeReveal
import eu.studio742.imago.feature.shell.resources.Res
import eu.studio742.imago.feature.shell.resources.shell_news_also_in
import eu.studio742.imago.feature.shell.resources.shell_news_featured
import eu.studio742.imago.feature.shell.resources.shell_news_next
import eu.studio742.imago.feature.shell.resources.shell_news_skip
import eu.studio742.imago.feature.shell.resources.shell_news_start
import eu.studio742.imago.feature.shell.resources.shell_news_title
import eu.studio742.imago.feature.shell.resources.shell_news_version
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A big release's news as cards to swipe through, one theme each, over the colour field of the
 * site's open-source section, drifting slowly.
 *
 * The releases skipped before it, if any, close the deck as one more card with their lines; a
 * release written as lines only is that card on its own. "Skip" and the last card's button both
 * end it; either way it has been seen.
 *
 * The deck is dark until the page that announces the light mode ([NewsPage.turnsLight]): arriving there
 * opens the light deck in a circle from that page's badge, as the app's own change of theme does, and
 * it stays light to the end. Going back before it closes it again.
 *
 * A [NewsPage.support] page shows only with [onSupport], which takes the person to the support card in
 * the settings: where the supporters programme is not open, the page is left out.
 *
 * @param animate false keeps the backdrop still — for tests and screenshots, where an endless
 *   animation never lets the clock settle.
 */
@Composable
internal fun WhatsNewCards(
    release: Release,
    older: List<Release>,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    animate: Boolean = true,
    onSupport: (() -> Unit)? = null,
) {
    val deck = remember(release, onSupport != null) {
        if (onSupport != null) release else Release(release.version, release.notes, release.pages.filterNot { it.support })
    }
    val listed = if (deck.pages.isEmpty()) listOf(deck) + older else older
    val count = deck.pages.size + if (listed.isEmpty()) 0 else 1
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, count - 1)) { count }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == count - 1
    val featured = deck.pages.getOrNull(pager.currentPage)?.featured == true
    val supporting = deck.pages.getOrNull(pager.currentPage)?.support == true
    val lightFrom = deck.pages.indexOfFirst { it.turnsLight }
    fun lightAt(page: Int) = lightFrom >= 0 && page >= lightFrom
    var light by remember { mutableStateOf(lightAt(pager.currentPage)) }
    val colors = if (light) ImagoNewsLight else ImagoNewsDark
    val reveal = rememberThemeReveal()
    var deckInRoot by remember { mutableStateOf(Offset.Zero) }
    var badgeInRoot by remember { mutableStateOf<Offset?>(null) }
    // Only once the page has settled: the circle keeps a picture of the deck, which would stand still
    // over a page that is still sliding.
    LaunchedEffect(pager, lightFrom) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val target = lightAt(page)
            if (target != light) {
                val from = badgeInRoot?.minus(deckInRoot) ?: Offset.Zero
                reveal.reveal(from) { light = target }
            }
        }
    }
    ThemeRevealBox(
        reveal,
        modifier
            .testTag(if (light) "news-cards-light" else "news-cards")
            .onGloballyPositioned { deckInRoot = it.positionInRoot() }
            .clip(RoundedCornerShape(28.dp)),
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(colors.background)
                .border(
                    1.dp,
                    when {
                        featured -> colors.featured.copy(alpha = 0.55f)
                        supporting -> colors.support.copy(alpha = 0.6f)
                        else -> colors.border
                    },
                    RoundedCornerShape(28.dp),
                ),
        )
        NewsBackdrop(colors, animate, Modifier.matchParentSize())
        // The lead page glows gold from the lower corner, under the text and the buttons alike.
        if (featured) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(
                        listOf(colors.featured.copy(alpha = 0.26f), colors.featured.copy(alpha = 0f)),
                        center = Offset(0f, Float.POSITIVE_INFINITY),
                        radius = 1400f,
                    ),
                ),
            )
        }
        // The support page glows warm from behind its heart.
        if (supporting) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(colors.support.copy(alpha = 0.30f), colors.support.copy(alpha = 0f)),
                            center = Offset(size.width / 2f, size.height * 0.3f),
                            radius = size.width * 0.9f,
                        ),
                    )
                },
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = (stringResource(Res.string.shell_news_title) + " · " +
                    stringResource(Res.string.shell_news_version, deck.version.toString())).uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 0.96.sp,
                color = colors.accent,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp),
            )
            // One height for every card, so that the buttons do not move from page to page.
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(CARD_HEIGHT)) { page ->
                if (page < deck.pages.size) {
                    val news = deck.pages[page]
                    if (news.support && onSupport != null) {
                        SupportPageCard(news, colors, active = pager.settledPage == page, animate = animate, onSupport = onSupport)
                    } else {
                        NewsPageCard(
                            news,
                            colors,
                            onBadge = if (news.turnsLight) ({ badgeInRoot = it }) else null,
                        )
                    }
                } else {
                    ReleaseLinesCard(listed, newest = deck, colors)
                }
            }
            PageDots(count = count, current = pager.currentPage, colors = colors, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!last) {
                    TextButton(onClick = onDone) {
                        Text(stringResource(Res.string.shell_news_skip), color = colors.quiet)
                    }
                }
                Spacer(Modifier.weight(1f))
                // The site's button: a pill, ivory on the dark deck and dark on the light one.
                Button(
                    onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.action, contentColor = colors.onAction),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (last) Res.string.shell_news_start else Res.string.shell_news_next))
                }
            }
        }
    }
}

@Composable
private fun NewsPageCard(page: NewsPage, colors: ImagoNewsPalette, onBadge: ((Offset) -> Unit)?) {
    val list = page.items.isNotEmpty()
    // A list carries more lines in the same height: a smaller badge and a smaller type.
    val badge = if (list) 44.dp else 64.dp
    Box(Modifier.fillMaxSize()) {
        // At the bottom of the card, near the buttons; a list starts at the top. With a very large
        // font either scrolls instead of clipping.
        Box(
            Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
            contentAlignment = if (page.items.isEmpty()) Alignment.BottomStart else Alignment.TopStart,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (page.featured) {
                    Text(
                        stringResource(Res.string.shell_news_featured).uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        letterSpacing = 1.1.sp,
                        color = colors.onFeatured,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.featured)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.height(ImagoSpacing.Md))
                }
                Box(
                    Modifier
                        .size(badge)
                        .then(
                            if (onBadge == null) {
                                Modifier
                            } else {
                                Modifier.onGloballyPositioned {
                                    onBadge(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f))
                                }
                            },
                        )
                        .clip(CircleShape)
                        .background(
                            if (page.featured) Brush.linearGradient(colors.featuredBadge) else Brush.linearGradient(listOf(colors.badge, colors.badge)),
                        )
                        .border(1.dp, if (page.featured) colors.featuredBadgeEdge else colors.accent.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        page.icon,
                        contentDescription = null,
                        tint = if (page.featured) colors.onFeatured else colors.accent,
                        modifier = Modifier.size(if (list) 22.dp else 30.dp),
                    )
                }
                Spacer(Modifier.height(if (list) ImagoSpacing.Lg else ImagoSpacing.Xl))
                Text(
                    stringResource(page.title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (page.featured) colors.featuredTitle else colors.title,
                )
                Spacer(Modifier.height(ImagoSpacing.Md))
                page.body?.let { body ->
                    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = colors.body)
                }
                if (list) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        page.items.forEach { item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                                Text("•", style = MaterialTheme.typography.bodySmall, color = colors.accent)
                                Text(stringResource(item), style = MaterialTheme.typography.bodySmall, color = colors.body)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The page that asks for support: a large heart in the middle that fills from the bottom and beats once
 * when the page settles, the title and text centred under it, and a button to the support card in the
 * settings. With Reduce Motion, or without animation, the heart is already full.
 */
@Composable
private fun SupportPageCard(page: NewsPage, colors: ImagoNewsPalette, active: Boolean, animate: Boolean, onSupport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SupportHeart(colors, active, still = !animate || LocalReducedMotion.current)
        Spacer(Modifier.height(ImagoSpacing.Lg))
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.title,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(ImagoSpacing.Md))
        page.body?.let { body ->
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = colors.body, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(ImagoSpacing.Lg))
        OutlinedButton(
            onClick = onSupport,
            border = BorderStroke(1.dp, colors.support),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.heightIn(min = 48.dp).testTag("news-support"),
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = colors.support, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(ImagoSpacing.Sm))
            Text(stringResource(Res.string.shell_news_support_action), color = colors.title)
        }
    }
}

@Composable
private fun SupportHeart(colors: ImagoNewsPalette, active: Boolean, still: Boolean) {
    val fill = remember { Animatable(if (still) 1f else 0f) }
    val beat = remember { Animatable(1f) }
    LaunchedEffect(active, still) {
        when {
            still -> fill.snapTo(1f)
            // Away from the page it empties again, to fill the next time it is reached.
            !active -> {
                fill.snapTo(0f)
                beat.snapTo(1f)
            }
            else -> {
                fill.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
                beat.animateTo(1.14f, tween(140))
                beat.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 300f))
            }
        }
    }
    Box(
        Modifier.size(96.dp).graphicsLayer {
            scaleX = beat.value
            scaleY = beat.value
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = colors.support, modifier = Modifier.fillMaxSize())
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = colors.support,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    clipRect(top = size.height * (1f - fill.value)) {
                        this@drawWithContent.drawContent()
                        drawRect(Brush.verticalGradient(colors.supportHeart), blendMode = BlendMode.SrcIn)
                    }
                },
        )
    }
}

// Tall enough for a page of eight short items on a phone; every page shares it.
private val CARD_HEIGHT = 470.dp

/** Releases told in lines: the newest itself if it has no pages, and the ones skipped before it. */
@Composable
private fun ReleaseLinesCard(releases: List<Release>, newest: Release, colors: ImagoNewsPalette) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Lg),
    ) {
        releases.forEach { release ->
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                Text(
                    stringResource(
                        if (release === newest) Res.string.shell_news_version else Res.string.shell_news_also_in,
                        release.version.toString(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.title,
                )
                val lines = release.notes.ifEmpty { release.pages.map { it.title } }
                lines.forEach { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = colors.accent)
                        Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = colors.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, colors: ImagoNewsPalette, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (index == current) 18.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (index == current) colors.action else colors.dot),
            )
        }
    }
}

/**
 * The colour field behind the site's open-source section, as a gradient: twelve colours on a 3 × 4
 * grid, each a soft radial glow, already saturated ×1.5 and darkened ×0.5 as the site does it. It
 * scales from 1.2 to 1.35 and drifts over 26 s and back, with the page colour fading in at the top
 * and bottom and on the left, where the text is.
 */
@Composable
private fun NewsBackdrop(colors: ImagoNewsPalette, animate: Boolean, modifier: Modifier) {
    val drift = if (animate) {
        val transition = rememberInfiniteTransition(label = "newsBackdrop")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(26_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "drift",
        )
        value
    } else {
        0f
    }
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1.2f + 0.15f * drift
                    scaleX = scale
                    scaleY = scale
                    translationX = (-0.03f + 0.06f * drift) * size.width
                    translationY = (-0.02f + 0.05f * drift) * size.height
                },
        ) {
            drawRect(colors.background)
            val cellWidth = size.width / BACKDROP_COLUMNS
            val cellHeight = size.height / BACKDROP_ROWS
            val radius = maxOf(cellWidth, cellHeight) * 1.25f
            colors.backdrop.forEachIndexed { index, colour ->
                val centre = Offset(
                    (index % BACKDROP_COLUMNS + 0.5f) * cellWidth,
                    (index / BACKDROP_COLUMNS + 0.5f) * cellHeight,
                )
                drawRect(Brush.radialGradient(listOf(colour, colour.copy(alpha = 0f)), centre, radius))
            }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to colors.background,
                    0.22f to colors.background.copy(alpha = 0f),
                    0.78f to colors.background.copy(alpha = 0f),
                    1f to colors.background,
                ),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to colors.background.copy(alpha = 0.72f),
                    0.6f to colors.background.copy(alpha = 0.2f),
                    1f to colors.background.copy(alpha = 0f),
                ),
            ),
        )
    }
}

private const val BACKDROP_COLUMNS = 3
private const val BACKDROP_ROWS = 4

