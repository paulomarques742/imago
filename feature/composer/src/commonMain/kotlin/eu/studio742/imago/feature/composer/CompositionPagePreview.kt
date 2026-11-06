package eu.studio742.imago.feature.composer

import eu.studio742.imago.core.render.libraryAuth
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import eu.studio742.imago.core.composition.CompositionElement
import eu.studio742.imago.core.composition.CompositionPage
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.boundsOnPage
import eu.studio742.imago.core.composition.paintedBounds
import eu.studio742.imago.core.composition.intersectionWithPage
import eu.studio742.imago.core.composition.renderElements
import eu.studio742.imago.core.composition.mediaFrame
import eu.studio742.imago.core.designsystem.ImagoUi
import androidx.compose.ui.platform.LocalDensity

/**
 * A composition page in miniature.
 *
 * It is the same drawing the composer's page strip shows and the project list uses to show the first
 * page — not an export, but the elements in their boxes, which at this scale is what can be told apart.
 * Shapes and frames retain their geometry and transparency. Texts and strokes become rectangles of
 * their own colour: at 60 px wide reading the word is not possible, and the patch says where it is.
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
    val uiColors = ImagoUi.colors
    val unit = with(LocalDensity.current) { width.toPx() } / REFERENCE_PAGE_WIDTH
    Box(Modifier.size(width, height).clipToBounds()) {
        Background(
            page.backgroundOverride ?: project.background,
            // A miniature has no use for the large version: both layers come from the thumbnail.
            thumbnailUrl,
            thumbnailUrl,
            apiKey,
            Modifier.fillMaxSize(),
        )
        project.renderElements()
            .filter { element -> element.visible &&
                (element !is CompositionElement.Video || element.pageIndex == page.index) &&
                (element.repeatOnPages.isEmpty() || page.index in element.repeatOnPages) &&
                element.paintedBounds(element.boundsOnPage(page.index), project.format.aspectRatio).intersectionWithPage(page.index) != null
            }
            .sortedBy(CompositionElement::zIndex)
            .forEach { element ->
                val b = element.boundsOnPage(page.index)
                // The coordinates run across the whole stage; here what matters is the fraction within the page.
                val localX = b.x - page.index
                val localY = b.y
                val cell = Modifier
                    .offset(x = width * localX, y = height * localY)
                    .size(
                        width * b.width,
                        height * b.height,
                    )
                    .graphicsLayer {
                        rotationZ = element.transform.rotationDegrees; alpha = if (element.mediaFrame() == null) element.transform.opacity else 1f
                        scaleX = if (element.transform.mirrorHorizontal) -1f else 1f
                        scaleY = if (element.transform.mirrorVertical) -1f else 1f
                    }
                when (element) {
                    is CompositionElement.Photo -> AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl(element.media.assetId))
                            .libraryAuth(apiKey(element.media.assetId))
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = placementAlignment(element.crop),
                        modifier = cell.compositionFrameDecoration(element.frame, unit, element.transform.opacity).placementZoom(element.crop),
                    )
                    is CompositionElement.Video -> AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailUrl(element.media.assetId))
                            .libraryAuth(apiKey(element.media.assetId))
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = placementAlignment(element.crop),
                        modifier = cell.compositionFrameDecoration(element.frame, unit, element.transform.opacity).placementZoom(element.crop),
                    )
                    is CompositionElement.Text -> Box(cell.background(Color(element.colorArgb).copy(alpha = .75f)))
                    is CompositionElement.Shape -> Canvas(cell) { drawCompositionShape(element, unit) }
                    is CompositionElement.Drawing -> if (element.id.startsWith("layout-decoration:")) Canvas(cell) {
                        drawCompositionLayoutStroke(element, unit)
                    } else Box(cell.background(Color(element.colorArgb).copy(alpha = .75f)))
                    is CompositionElement.MediaPlaceholder -> Box(cell.compositionFrameDecoration(element.frame, unit, element.transform.opacity).background(uiColors.text.copy(alpha = .18f)))
                }
            }
    }
}
