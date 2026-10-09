package eu.studio742.imago.feature.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.feature.shell.resources.Res
import eu.studio742.imago.feature.shell.resources.shell_news_also_in
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
) {
    val listed = if (release.pages.isEmpty()) listOf(release) + older else older
    val count = release.pages.size + if (listed.isEmpty()) 0 else 1
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, count - 1)) { count }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == count - 1
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(ImagoColors.Background)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp)),
    ) {
        NewsBackdrop(animate, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = (stringResource(Res.string.shell_news_title) + " · " +
                    stringResource(Res.string.shell_news_version, release.version.toString())).uppercase(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 0.96.sp,
                color = ImagoColors.Gold,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp),
            )
            // One height for every card, so that the buttons do not move from page to page.
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(CARD_HEIGHT)) { page ->
                if (page < release.pages.size) {
                    NewsPageCard(release.pages[page])
                } else {
                    ReleaseLinesCard(listed, newest = release)
                }
            }
            PageDots(count = count, current = pager.currentPage, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!last) {
                    TextButton(onClick = onDone) {
                        Text(stringResource(Res.string.shell_news_skip), color = Color.White.copy(alpha = 0.72f))
                    }
                }
                Spacer(Modifier.weight(1f))
                // The site's button: an ivory pill with dark text.
                Button(
                    onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    colors = ButtonDefaults.buttonColors(containerColor = ImagoColors.Ivory, contentColor = ImagoColors.Background),
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
private fun NewsPageCard(page: NewsPage) {
    // At the bottom of the card, near the buttons; with a very large font it scrolls instead of clipping.
    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), contentAlignment = Alignment.BottomStart) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, ImagoColors.Gold.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(page.icon, contentDescription = null, tint = ImagoColors.Gold, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(ImagoSpacing.Xl))
        Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, color = ImagoColors.BrandWhite)
        Spacer(Modifier.height(ImagoSpacing.Md))
        Text(
            stringResource(page.body),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.78f),
        )
    }
    }
}

private val CARD_HEIGHT = 420.dp

/** Releases told in lines: the newest itself if it has no pages, and the ones skipped before it. */
@Composable
private fun ReleaseLinesCard(releases: List<Release>, newest: Release) {
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
                    color = ImagoColors.BrandWhite,
                )
                val lines = release.notes.ifEmpty { release.pages.map { it.title } }
                lines.forEach { note ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = ImagoColors.Gold)
                        Text(stringResource(note), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f))
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            Box(
                Modifier
                    .height(6.dp)
                    .width(if (index == current) 18.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (index == current) ImagoColors.Ivory else Color.White.copy(alpha = 0.3f)),
            )
        }
    }
}

/**
 * The colour field behind the site's open-source section, as a gradient: twelve colours on a 3 × 4
 * grid, each a soft radial glow, already saturated ×1.5 and darkened ×0.5 as the site does it. It
 * scales from 1.2 to 1.35 and drifts over 26 s and back, with the page colour fading in at the top
 * and bottom and a darker left side for the text.
 */
@Composable
private fun NewsBackdrop(animate: Boolean, modifier: Modifier) {
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
            drawRect(ImagoColors.Background)
            val cellWidth = size.width / BACKDROP_COLUMNS
            val cellHeight = size.height / BACKDROP_ROWS
            val radius = maxOf(cellWidth, cellHeight) * 1.25f
            BACKDROP_COLOURS.forEachIndexed { index, colour ->
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
                    0f to ImagoColors.Background,
                    0.22f to Color(0x000B0D0F),
                    0.78f to Color(0x000B0D0F),
                    1f to ImagoColors.Background,
                ),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xB80B0D0F),
                    0.6f to Color(0x330B0D0F),
                    1f to Color(0x000B0D0F),
                ),
            ),
        )
    }
}

private const val BACKDROP_COLUMNS = 3
private const val BACKDROP_ROWS = 4

/** Row by row, top to bottom: sky blue, warm brown, then dark olive and teal. */
private val BACKDROP_COLOURS = listOf(
    Color(0xFF4C5F7B), Color(0xFF3C5471), Color(0xFF345274),
    Color(0xFF342916), Color(0xFF382A17), Color(0xFF3C301A),
    Color(0xFF1D190C), Color(0xFF172322), Color(0xFF2D2B0E),
    Color(0xFF0B1921), Color(0xFF22250F), Color(0xFF382E00),
)
