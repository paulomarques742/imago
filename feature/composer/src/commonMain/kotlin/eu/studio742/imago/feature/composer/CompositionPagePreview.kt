package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.render.libraryAuth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject

/**
 * A composition page in miniature.
 *
 * It is the same drawing the composer's page strip shows and the project list uses to show the first
 * page — not an export, but the elements in their boxes, which at this scale is what can be told apart.
 * Texts, shapes and strokes become rectangles of their own colour: at 60 px wide reading the word is
 * not possible, and the patch says where it is.
 *
 * The miniature takes the space it is given respecting the page's format — the rest stays background,
 * because stretching a 9:16 into a square would lie about the composition.
 */
@Composable
internal fun CompositionPagePreview(
    project: CompositionProject,
    page: CompositionPage,
    thumbnailUrl: (String) -> String,
    apiKey: (String) -> String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = project.format.pixelWidth.toFloat() / project.format.pixelHeight
        val width: Dp
        val height: Dp
        if (maxWidth / maxHeight > aspect) {
            height = maxHeight
            width = maxHeight * aspect
        } else {
            width = maxWidth
            height = maxWidth / aspect
        }
        CompositionPageCanvas(project, page, width, height, thumbnailUrl, apiKey)
    }
}

/** The miniature's body, with the size already decided by whoever composes it. */
@Composable
internal fun CompositionPageCanvas(
    project: CompositionProject,
    page: CompositionPage,
    width: Dp,
    height: Dp,
    thumbnailUrl: (String) -> String,
    apiKey: (String) -> String,
) {
    val context = LocalPlatformContext.current
    Box(Modifier.size(width, height)) {
        Background(
            page.backgroundOverride ?: project.background,
            thumbnailUrl,
            apiKey,
            Modifier.fillMaxSize(),
        )
        project.elements
            .filter { it.visible && page.index in it.pagesOccupied() }
            .sortedBy(CompositionElement::zIndex)
            .forEach { element ->
                val b = element.transform.bounds
                // The coordinates run across the whole stage; here what matters is the fraction within the page.
                val localX = (b.x - page.index).coerceIn(0f, 1f)
                val localY = b.y.coerceIn(0f, 1f)
                val cell = Modifier
                    .offset(x = width * localX, y = height * localY)
                    .size(
                        (width * b.width).coerceAtMost(width),
                        (height * b.height).coerceAtMost(height),
                    )
                when (element) {
                    is CompositionElement.Photo -> AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl(element.media.assetId))
                            .libraryAuth(apiKey(element.media.assetId))
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = cell,
                    )
                    is CompositionElement.Video -> AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl(element.media.assetId))
                            .libraryAuth(apiKey(element.media.assetId))
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = cell,
                    )
                    is CompositionElement.Text -> Box(cell.background(Color(element.colorArgb).copy(alpha = .75f)))
                    is CompositionElement.Shape -> Box(cell.background(Color(element.fillArgb).copy(alpha = .75f)))
                    is CompositionElement.Drawing -> Box(cell.background(Color(element.colorArgb).copy(alpha = .75f)))
                    is CompositionElement.MediaPlaceholder -> Box(cell.background(Color.White.copy(alpha = .18f)))
                }
            }
    }
}
