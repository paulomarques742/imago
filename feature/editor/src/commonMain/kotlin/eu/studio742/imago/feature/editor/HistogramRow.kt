package eu.studio742.imago.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.ClippingWarning
import eu.studio742.imago.core.render.Histogram
import eu.studio742.imago.feature.editor.resources.*
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The warning's colours: the same the shader paints with (`uClipHighlights`, `uClipShadows`). */
private val HighlightWarning = Color(0xFFF22626)
private val ShadowWarning = Color(0xFF265AF2)

/**
 * The histogram of the photo as the recipe leaves it, with the two triangles that turn on the warning
 * of what is cut off at either end — shadows on the left, highlights on the right, as in Lightroom.
 *
 * It follows the sliders with a short pause, so that dragging one does not draw it a hundred times.
 * [showsWarning] is false where the canvas cannot paint the warning: the triangles are left out there,
 * and the histogram alone stays.
 */
@Composable
internal fun HistogramRow(
    assetId: String?,
    recipe: EditRecipe,
    clipping: ClippingWarning,
    showsWarning: Boolean,
    onToggleShadows: () -> Unit,
    onToggleHighlights: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var histogram by remember { mutableStateOf<Histogram?>(null) }
    LaunchedEffect(assetId, recipe) {
        if (assetId == null) return@LaunchedEffect
        delay(HISTOGRAM_PAUSE_MS)
        RecipePreviewCache.histogram(assetId, recipe)?.let { histogram = it }
    }
    val description = stringResource(Res.string.editor_histogram)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = ImagoSpacing.Sm)
            .height(HISTOGRAM_HEIGHT)
            .clip(RoundedCornerShape(ImagoRadii.Medium))
            .background(ImagoColors.Surface)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxWidth().height(HISTOGRAM_HEIGHT)) {
            histogram?.let { drawHistogram(it) }
        }
        if (showsWarning) {
            WarningTriangle(
                on = clipping.shadows,
                warning = ShadowWarning,
                description = stringResource(Res.string.editor_clip_shadows),
                onClick = onToggleShadows,
                pointsLeft = true,
                modifier = Modifier.align(Alignment.TopStart),
            )
            WarningTriangle(
                on = clipping.highlights,
                warning = HighlightWarning,
                description = stringResource(Res.string.editor_clip_highlights),
                onClick = onToggleHighlights,
                pointsLeft = false,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun WarningTriangle(
    on: Boolean,
    warning: Color,
    description: String,
    onClick: () -> Unit,
    pointsLeft: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val path = Path().apply {
                if (pointsLeft) {
                    moveTo(0f, size.height / 2f); lineTo(size.width, 0f); lineTo(size.width, size.height)
                } else {
                    moveTo(size.width, size.height / 2f); lineTo(0f, 0f); lineTo(0f, size.height)
                }
                close()
            }
            if (on) drawPath(path, warning) else drawPath(path, ImagoColors.TextSecondary, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/**
 * Three channels added over a dark ground and the luminance under them. The height is the square root
 * of the count: with a straight scale one tall spike — a clipped sky — flattens every other column.
 */
private fun DrawScope.drawHistogram(histogram: Histogram) {
    val peak = sqrt(histogram.peak.coerceAtLeast(1).toFloat())
    fun area(counts: IntArray, color: Color, blend: BlendMode) {
        val path = Path().apply {
            moveTo(0f, size.height)
            counts.forEachIndexed { index, count ->
                val x = (index + 0.5f) / counts.size * size.width
                lineTo(x, size.height - sqrt(count.toFloat()) / peak * size.height * 0.92f)
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, color, blendMode = blend)
    }
    area(histogram.luminance, Color(0x44FFFFFF), BlendMode.SrcOver)
    area(histogram.red, Color(0x99E03030), BlendMode.Plus)
    area(histogram.green, Color(0x9930C040), BlendMode.Plus)
    area(histogram.blue, Color(0x993060E0), BlendMode.Plus)
}

private val HISTOGRAM_HEIGHT = 84.dp
private const val HISTOGRAM_PAUSE_MS = 60L
