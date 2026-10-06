package eu.studio742.imago.feature.composer

import org.jetbrains.compose.resources.stringResource
import eu.studio742.imago.feature.composer.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.studio742.imago.core.composition.Hsva
import eu.studio742.imago.core.composition.parseColorHex
import eu.studio742.imago.core.composition.toArgb
import eu.studio742.imago.core.composition.toColorHex
import eu.studio742.imago.core.composition.toHsva
import eu.studio742.imago.core.composition.withColorAlpha
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.ImagoRadii
import eu.studio742.imago.core.designsystem.ImagoSpacing

/** The colours that always appear, beyond the brand kit's. */
private val DefaultSwatches = listOf(
    0xFFFFFFFF, 0xFFE6E6E3, 0xFF9A9A96, 0xFF3A3A3E, 0xFF111111, 0xFF000000,
    0xFFD4AF37, 0xFFE07A5F, 0xFFC1462F, 0xFF3D5A80, 0xFF6C9A8B, 0xFF8E7CC3,
)

/**
 * The colour picker, shared by the shapes' fill and outline, the frames' outline, the text and the
 * two ends of the background gradient.
 *
 * It works in HSV and only converts to ARGB on the way out: choosing a colour is looking for a hue and
 * then fine-tuning it, and those are three independent cursors. In RGB they would be three cursors that
 * have to move at the same time to lighten without changing colour.
 *
 * The colour only reaches the model on "Apply", on purpose. Every frame of a drag is an edit, and a
 * cursor swept end to end filled the hundred history slots on its own — the same problem
 * `InspectorSlider` solves by opening and closing a gesture. Here no gesture is needed: the large
 * swatch on top shows the result, and the model is touched only once.
 */
@Composable
internal fun ColorPickerDialog(
    title: String,
    initialArgb: Long,
    palette: List<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    // The hue is kept in HSV between edits, and not re-read from ARGB every time, because a black or a
    // grey has no defined hue — the conversion returns 0°. Without this memory, dragging the value to
    // the bottom and back silently swapped the chosen colour for red.
    var hsva by remember(initialArgb) { mutableStateOf(initialArgb.toHsva()) }
    var hex by remember(initialArgb) { mutableStateOf(initialArgb.toColorHex()) }
    val argb = hsva.toArgb()
    val opaque = Color(hsva.copy(alpha = 1f).toArgb())

    fun update(next: Hsva) {
        val clamped = next.copyClamped()
        hsva = clamped
        hex = clamped.toArgb().toColorHex()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ImagoSpacing.Sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(argb, ImagoRadii.Medium, Modifier.size(56.dp))
                    TextField(
                        value = hex,
                        onValueChange = { typed ->
                            hex = typed
                            // While the text is not a valid colour the previous one stays, instead of
                            // jumping to black mid-typing.
                            parseColorHex(typed)?.let { parsed ->
                                val previousHue = hsva.hue
                                val next = parsed.toHsva()
                                hsva = if (next.saturation <= 0f) next.copy(hue = previousHue) else next
                            }
                        },
                        label = { Text(stringResource(Res.string.composer_hex)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(start = ImagoSpacing.Sm),
                    )
                }
                ChannelSlider(
                    label = stringResource(Res.string.composer_hue),
                    fraction = hsva.hue / 360f,
                    brush = Brush.horizontalGradient((0..6).map { Color(Hsva(it * 60f, 1f, 1f).toArgb()) }),
                ) { update(hsva.copy(hue = it * 360f)) }
                ChannelSlider(
                    label = stringResource(Res.string.composer_saturation),
                    fraction = hsva.saturation,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(Hsva(hsva.hue, 0f, hsva.value).toArgb()),
                            Color(Hsva(hsva.hue, 1f, hsva.value).toArgb()),
                        ),
                    ),
                ) { update(hsva.copy(saturation = it)) }
                ChannelSlider(
                    label = stringResource(Res.string.composer_brightness),
                    fraction = hsva.value,
                    brush = Brush.horizontalGradient(listOf(Color.Black, opaque)),
                ) { update(hsva.copy(value = it)) }
                ChannelSlider(
                    label = stringResource(Res.string.composer_opacity),
                    fraction = hsva.alpha,
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, opaque)),
                ) { update(hsva.copy(alpha = it)) }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (palette + DefaultSwatches).distinct().forEach { swatch ->
                        ColorSwatch(
                            argb = swatch,
                            corner = ImagoRadii.Small,
                            modifier = Modifier.size(32.dp).clickable {
                                // The opacity one already had survives choosing a swatch: the swatches
                                // are colours, not opacities.
                                update(swatch.withColorAlpha(hsva.alpha).toHsva())
                            },
                            label = stringResource(Res.string.composer_color_n, swatch.toColorHex()),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(argb); onDismiss() }) { Text(stringResource(Res.string.composer_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.composer_cancel)) } },
    )
}

/**
 * A cursor drawn over the gradient itself, instead of the system `Slider`.
 *
 * The track **is** the information: the hue about to be picked is seen before getting there, and the
 * opacity reads against the checkerboard. A single `awaitEachGesture` handles tap and drag — two
 * detectors on the same node would compete for the same `down`, which is exactly what already broke
 * the stage's gestures.
 */
@Composable
private fun ChannelSlider(
    label: String,
    fraction: Float,
    brush: Brush,
    onFraction: (Float) -> Unit,
) {
    val shape = RoundedCornerShape(ImagoRadii.Small)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextSecondary,
            modifier = Modifier.width(72.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(28.dp)
                .clip(shape)
                .checkerboard()
                .background(brush)
                .border(1.dp, ImagoColors.BorderVisible, shape)
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val down = awaitFirstDown()
                        onFraction((down.position.x / width).coerceIn(0f, 1f))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            onFraction((change.position.x / width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }
                }
                .drawBehind {
                    val x = fraction.coerceIn(0f, 1f) * size.width
                    val centre = Offset(x, size.height / 2f)
                    val radius = size.height * .32f
                    // Two rings, light inside and dark outside: a single one disappeared against the end
                    // of the track with the same lightness.
                    drawCircle(Color.White, radius, centre, style = Stroke(2.5f))
                    drawCircle(Color.Black.copy(alpha = .55f), radius + 2f, centre, style = Stroke(1.5f))
                },
        )
        Text(
            "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = ImagoColors.TextTertiary,
            modifier = Modifier.padding(start = 6.dp).width(36.dp),
        )
    }
}

/** A swatch, over the checkerboard so a translucent colour is not mistaken for a dark one. */
@Composable
private fun ColorSwatch(argb: Long, corner: Dp, modifier: Modifier = Modifier, label: String? = null) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .clip(shape)
            .checkerboard()
            .background(Color(argb))
            .border(1.dp, ImagoColors.BorderStrong, shape)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    )
}

/** The transparency checkerboard, drawn and not composed — there are dozens of squares per swatch. */
private fun Modifier.checkerboard(cell: Float = 9f) = drawBehind {
    drawRect(Color(0xFF8E8E8B))
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(
                Color(0xFF636360),
                topLeft = Offset(x, y),
                size = Size(minOf(cell, size.width - x), minOf(cell, size.height - y)),
            )
            x += cell * 2
        }
        y += cell
        row++
    }
}
