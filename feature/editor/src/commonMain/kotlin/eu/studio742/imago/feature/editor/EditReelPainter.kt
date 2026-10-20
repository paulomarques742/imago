package eu.studio742.imago.feature.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.studio742.imago.core.designsystem.ImagoBrand
import eu.studio742.imago.core.designsystem.ImagoColors
import eu.studio742.imago.core.designsystem.drawImagoSlider
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.render.PhotoBounds
import eu.studio742.imago.core.render.PixelBuffer
import eu.studio742.imago.core.render.toImageBitmap
import eu.studio742.imago.core.render.frameGeometry
import eu.studio742.imago.core.render.toRenderParameters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the sliders appear over the photo. */
enum class ReelStyle {
    /** The editor's own panel under the photo: the sliders as the app draws them. */
    PANEL,

    /** Only the name and the value, floating over the photo. */
    PILL,

    /** A card under the photo where the adjustments pile up, each ticked when it arrives. */
    LIST,
}

const val REEL_WIDTH = 1080
const val REEL_HEIGHT = 1920

/**
 * The frame is laid out in dp at three pixels each — 360 × 640, a phone's width — so the editor's
 * own measures and the design system's sliders come out at the size they have on a phone.
 */
private const val REEL_DENSITY = 3f

/*
 * The closing's timeline, in milliseconds from its start: the photo gives way to black, the symbol's
 * bars draw themselves one after the other, the square lands, the letters arrive, then the tagline.
 */
private const val CLOSING_FADE_MS = 500L
private const val CLOSING_BARS_FROM_MS = 300L
private const val CLOSING_BAR_MS = 260L
private const val CLOSING_BAR_STAGGER_MS = 190L
private const val CLOSING_SQUARE_FROM_MS = 1_060L
private const val CLOSING_SQUARE_MS = 320L
private const val CLOSING_LETTERS_FROM_MS = 1_180L
private const val CLOSING_LETTER_MS = 340L
private const val CLOSING_LETTER_STAGGER_MS = 110L
private const val CLOSING_TAGLINE_FROM_MS = 1_760L
private const val CLOSING_TAGLINE_MS = 480L

/** From here on the closing no longer moves. */
private const val CLOSING_SETTLED_MS = CLOSING_TAGLINE_FROM_MS + CLOSING_TAGLINE_MS

/* The pills come in one after the other, rising a little, and leave together at the end of the step. */
private const val PILL_IN_MS = 320L
private const val PILL_OUT_MS = 260L
private const val PILL_STAGGER_MIN_MS = 80L
private const val PILL_STAGGER_MAX_MS = 260L

/** The curve's graph fades in at the start of its step and out at the end. */
private const val CURVE_FADE_MS = 300L

private val SHEET_TOP = 432.dp
private val PHOTO_BOTTOM_UNDER_SHEET = 416.dp
private val PHOTO_BOTTOM_ALONE = 600.dp
private const val LIST_ROWS = 5

/**
 * Draws one frame of the video from what the script says about that instant and the photo already
 * rendered with that instant's recipe.
 *
 * It draws outside any composition: everything it needs — words, text measurer, brand — is handed
 * in, so the same painter serves the preview, the Android encoder and the desktop one.
 */
class ReelFramePainter(
    private val script: EditReelScript,
    private val style: ReelStyle,
    private val texts: ReelTexts,
    private val textMeasurer: TextMeasurer,
    /**
     * The frame's size against [REEL_WIDTH] × [REEL_HEIGHT]: the preview draws at a third, and an
     * encoder that refuses 1080 × 1920 gets a smaller video with the same layout.
     */
    val scale: Float = 1f,
    /** Always on for now; whether it can be removed is still to be decided. */
    private val watermark: Boolean = true,
) {
    val width: Int = evenPixels(REEL_WIDTH * scale)
    val height: Int = evenPixels(REEL_HEIGHT * scale)

    /**
     * The longest side the photo can take in this frame. A renderer that draws from the full
     * original renders at this size: anything larger would only be scaled down again here.
     */
    val photoMaxSide: Int
        get() {
            val top = if (texts.title != null) 92f else 64f
            val bottom = if (style == ReelStyle.PILL) PHOTO_BOTTOM_ALONE.value else PHOTO_BOTTOM_UNDER_SHEET.value
            return (maxOf(REEL_WIDTH / REEL_DENSITY - 32f, bottom - top) * REEL_DENSITY * width / REEL_WIDTH).roundToInt()
        }

    private var backdropOf: ImageBitmap? = null
    private var backdrop: ImageBitmap? = null

    /**
     * @param photo the photo rendered with [ReelFrame.recipe]
     * @param compareBefore during the comparison, the photo rendered with [EditReelScript.compareBefore]
     * @param backdropPhoto the photo the background is made from: blurred and dark, it hardly shows
     *   the steps change, and building it once instead of every frame halves the cost of a frame
     * @param sourceWidth the width of the image the masks are normalised to
     */
    fun paint(
        frame: ReelFrame,
        photo: ImageBitmap,
        compareBefore: ImageBitmap?,
        backdropPhoto: ImageBitmap,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ImageBitmap {
        val image = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            Density(REEL_DENSITY * width / REEL_WIDTH),
            LayoutDirection.Ltr,
            Canvas(image),
            Size(width.toFloat(), height.toFloat()),
        ) {
            drawFrame(frame, photo, compareBefore, backdropPhoto, sourceWidth, sourceHeight)
        }
        return image
    }

    /**
     * What, besides the script, decides how [frame] looks: two frames with equal keys in the same
     * segment are drawn the same, and the renderer reuses the first.
     */
    internal fun stillKey(frame: ReelFrame): Any = when (val segment = frame.segment) {
        ReelSegment.Before -> 0L
        is ReelSegment.Step -> {
            val elapsed = frame.elapsedMs
            val pillsSettled = (segment.step.controls.size - 1) * pillStaggerMs(segment.step) + PILL_IN_MS
            val pillsMoving = style == ReelStyle.PILL &&
                (elapsed < pillsSettled || elapsed > segment.durationMs - PILL_OUT_MS)
            val curveFading = segment.step.controls.any { it is ReelControl.Curve } &&
                (elapsed < CURVE_FADE_MS || elapsed > segment.durationMs - CURVE_FADE_MS)
            if (pillsMoving || curveFading) elapsed else frame.progress
        }
        ReelSegment.Compare -> frame.elapsedMs
        ReelSegment.Closing -> minOf(frame.elapsedMs, CLOSING_SETTLED_MS)
    }

    private fun DrawScope.drawFrame(
        frame: ReelFrame,
        photo: ImageBitmap,
        compareBefore: ImageBitmap?,
        backdropPhoto: ImageBitmap,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        drawBackdrop(backdropPhoto)
        val box = photoBox()
        val rect = fit(photo, box)
        when (val segment = frame.segment) {
            ReelSegment.Before -> {
                drawPhoto(photo, rect)
                drawTag(texts.before, Offset(rect.left + 12.dp.toPx(), rect.top + 12.dp.toPx()))
                if (style == ReelStyle.PANEL) script.steps.firstOrNull()?.let { drawSheet(it, it.from, 0f, 1f) }
            }
            is ReelSegment.Step -> {
                drawPhoto(photo, rect)
                drawMaskOf(segment.step, frame, rect, sourceWidth, sourceHeight)
                drawCurvesOf(segment, frame, rect)
                when (style) {
                    ReelStyle.PANEL -> drawSheet(segment.step, frame.recipe, frame.progress, 1f)
                    ReelStyle.PILL -> drawPills(segment, frame, rect)
                    ReelStyle.LIST -> drawList(frame.segmentIndex - 1, frame, 1f)
                }
            }
            ReelSegment.Compare -> {
                drawComparison(photo, compareBefore ?: photo, rect, frame.progress)
                val fade = 1f - (frame.progress / 0.15f).coerceIn(0f, 1f)
                if (fade > 0f) {
                    when (style) {
                        ReelStyle.PANEL -> script.steps.lastOrNull()?.let { drawSheet(it, it.to, 1f, fade) }
                        ReelStyle.LIST -> drawList(script.steps.size, frame, fade)
                        ReelStyle.PILL -> Unit
                    }
                }
            }
            ReelSegment.Closing -> drawPhoto(photo, rect)
        }
        drawChrome()
        if (frame.segment == ReelSegment.Closing) drawClosing(frame.elapsedMs)
    }

    // --- layout -------------------------------------------------------------------------------

    private fun DrawScope.photoBox(): Rect {
        val top = (if (texts.title != null) 92.dp else 64.dp).toPx()
        val bottom = (if (style == ReelStyle.PILL) PHOTO_BOTTOM_ALONE else PHOTO_BOTTOM_UNDER_SHEET).toPx()
        return Rect(16.dp.toPx(), top, size.width - 16.dp.toPx(), bottom)
    }

    /** The photo as large as it fits in [box], centred, in whole pixels so it is never resampled twice. */
    private fun fit(photo: ImageBitmap, box: Rect): Rect {
        val scale = min(box.width / photo.width, box.height / photo.height)
        val width = (photo.width * scale).roundToInt().toFloat()
        val height = (photo.height * scale).roundToInt().toFloat()
        val left = (box.left + (box.width - width) / 2).roundToInt().toFloat()
        val top = (box.top + (box.height - height) / 2).roundToInt().toFloat()
        return Rect(left, top, left + width, top + height)
    }

    // --- photo and background -------------------------------------------------------------------

    /**
     * The photo itself, blurred and darkened, behind everything: the frame takes the photo's colours
     * instead of a flat black around a picture that is rarely 9:16.
     */
    private fun DrawScope.drawBackdrop(photo: ImageBitmap) {
        val layer = backdrop.takeIf { backdropOf === photo } ?: backdropLayer(photo).also {
            backdrop = it
            backdropOf = photo
        }
        drawImage(layer)
    }

    /** The background at the frame's size, darkened already: each frame only copies it. */
    private fun backdropLayer(photo: ImageBitmap): ImageBitmap {
        val blurred = blur(photo)
        val layer = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(layer), Size(width.toFloat(), height.toFloat())) {
            drawImage(blurred, dstOffset = IntOffset.Zero, dstSize = IntSize(width, height), filterQuality = FilterQuality.Low)
            drawRect(Color.Black.copy(alpha = 0.6f))
        }
        return layer
    }

    /**
     * A real blur, small: the photo shrunk to an eighth of the frame and blurred three times with a
     * box, which together approach a Gaussian. Only a picture with no detail left survives being
     * enlarged eight times without showing blocks; shrinking alone left the pixels visible.
     */
    private fun blur(photo: ImageBitmap): ImageBitmap {
        val small = ImageBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(small), Size(BACKDROP_WIDTH.toFloat(), BACKDROP_HEIGHT.toFloat())) {
            val scale = maxOf(size.width / photo.width, size.height / photo.height)
            val width = (photo.width * scale).roundToInt()
            val height = (photo.height * scale).roundToInt()
            drawImage(
                photo,
                dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
                dstSize = IntSize(width, height),
                filterQuality = FilterQuality.Medium,
            )
        }
        val pixels = IntArray(BACKDROP_WIDTH * BACKDROP_HEIGHT).also { small.readPixels(it) }
        repeat(3) {
            boxBlur(pixels, BACKDROP_WIDTH, BACKDROP_HEIGHT, BACKDROP_RADIUS, horizontal = true)
            boxBlur(pixels, BACKDROP_WIDTH, BACKDROP_HEIGHT, BACKDROP_RADIUS, horizontal = false)
        }
        return PixelBuffer(BACKDROP_WIDTH, BACKDROP_HEIGHT, pixels).toImageBitmap()
    }

    private fun DrawScope.drawPhoto(photo: ImageBitmap, rect: Rect) {
        clipPath(roundedPath(rect, 14.dp.toPx())) {
            drawImage(
                photo,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
                // The photo arrives at about the size it is drawn; bicubic would only cost time.
                filterQuality = FilterQuality.Medium,
            )
        }
    }

    private fun DrawScope.drawMaskOf(step: ReelStep, frame: ReelFrame, rect: Rect, sourceWidth: Int, sourceHeight: Int) {
        val mask = step.maskId()?.let { frame.recipe.mask(it) } ?: return
        val geometry = frame.recipe.toRenderParameters().frameGeometry(sourceWidth, sourceHeight)
        val projection = MaskProjection(PhotoBounds(rect.left, rect.top, rect.width, rect.height), geometry)
        clipPath(roundedPath(rect, 14.dp.toPx())) {
            drawMaskOutline(mask, projection, alpha = (frame.progress * 4f).coerceIn(0f, 1f))
        }
    }

    /**
     * The curve as the editor draws it over the photo, taking shape while the step moves: the same
     * grid, line and points, in the largest square the photo holds.
     */
    private fun DrawScope.drawCurvesOf(segment: ReelSegment.Step, frame: ReelFrame, rect: Rect) {
        val channels = segment.step.controls.filterIsInstance<ReelControl.Curve>().map { it.channel }
        if (channels.isEmpty()) return
        val alpha = fadeInOut(frame.elapsedMs, segment.durationMs, CURVE_FADE_MS)
        if (alpha <= 0f) return
        val side = min(rect.width, rect.height)
        translate(rect.center.x - side / 2, rect.center.y - side / 2) {
            val plot = curvePlot(side, side)
            channels.forEachIndexed { index, channel ->
                val points = frame.recipe.editableCurvePoints(channel)
                drawCurveGraph(
                    plot = plot,
                    lut = frame.recipe.curveLut(points),
                    color = channel.curveColor(),
                    points = points,
                    alpha = alpha,
                    grid = index == 0,
                )
            }
        }
    }

    // --- Panel ------------------------------------------------------------------------------------

    /**
     * The editor's panel: the step's name and every slider it moves, at [recipe]'s values. When they
     * do not all fit, the rows run up while the values change, so the last one is in view by the
     * time the step settles: a step with many changes shows that it has many.
     */
    private fun DrawScope.drawSheet(step: ReelStep, recipe: EditRecipe, progress: Float, alpha: Float) {
        val top = SHEET_TOP.toPx()
        val radius = 20.dp.toPx()
        val sheet = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f, top = top, right = size.width, bottom = size.height,
                    topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                ),
            )
        }
        drawPath(sheet, ImagoColors.Surface.copy(alpha = 0.96f * alpha))
        drawLine(
            ImagoColors.BorderVisible.copy(alpha = ImagoColors.BorderVisible.alpha * alpha),
            Offset(size.width / 2 - 18.dp.toPx(), top + 10.dp.toPx()),
            Offset(size.width / 2 + 18.dp.toPx(), top + 10.dp.toPx()),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val left = 20.dp.toPx()
        val right = size.width - 20.dp.toPx()
        var y = top + 22.dp.toPx()
        texts.title(step.title)?.let { title ->
            drawTextLine(title, Offset(left, y), headline, ImagoColors.TextPrimary, alpha, maxWidth = right - left - 48.dp.toPx())
        }
        val rowsTop = y + 32.dp.toPx()
        val rowsBottom = size.height - 12.dp.toPx()
        val heights = step.controls.map { if (it.scale() != null) 48.dp.toPx() else 30.dp.toPx() }
        val overflow = (heights.sum() - (rowsBottom - rowsTop)).coerceAtLeast(0f)
        y = rowsTop - overflow * progress
        clipRect(0f, rowsTop - 4.dp.toPx(), size.width, rowsBottom) {
            step.controls.forEachIndexed { index, control ->
                val height = heights[index]
                // A row leaving over the top fades rather than being cut through its text.
                val rowAlpha = alpha * ((y + height - rowsTop) / height).coerceIn(0f, 1f)
                if (rowAlpha > 0f && y < rowsBottom) drawSheetRow(control, recipe, progress, y, left, right, rowAlpha)
                y += height
            }
        }
    }

    private fun DrawScope.drawSheetRow(
        control: ReelControl,
        recipe: EditRecipe,
        progress: Float,
        y: Float,
        left: Float,
        right: Float,
        alpha: Float,
    ) {
        val value = control.valueIn(recipe)
        val scale = control.scale()
        drawTextLine(texts.label(control), Offset(left, y), label, ImagoColors.TextSecondary, alpha, maxWidth = right - left - 90.dp.toPx())
        if (value != null && scale != null) {
            val (range, neutral) = scale
            val valueColor = if (kotlin.math.abs(value - neutral) < 0.001f) ImagoColors.TextTertiary else ImagoColors.Gold
            drawTextEnd(control.formatValue(value), right, y, valueText, valueColor, alpha)
            val trackTop = y + 19.dp.toPx()
            inset(left, trackTop, size.width - right, size.height - trackTop - 18.dp.toPx()) {
                drawSliderWithAlpha(fraction(value, range), fraction(neutral, range), alpha)
            }
        } else if (progress >= 1f) {
            drawTextEnd(CHECK, right, y, valueText, ImagoColors.Gold, alpha)
        }
    }

    private fun DrawScope.drawSliderWithAlpha(valueFraction: Float, neutralFraction: Float, alpha: Float) {
        if (alpha >= 1f) {
            drawImagoSlider(valueFraction, neutralFraction)
        } else {
            // The design system's slider draws opaque; fading it is a layer over this scope.
            drawContext.canvas.saveLayer(
                Rect(0f, 0f, size.width, size.height),
                androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha },
            )
            drawImagoSlider(valueFraction, neutralFraction)
            drawContext.canvas.restore()
        }
    }

    // --- Pill -------------------------------------------------------------------------------------

    /**
     * Every slider of the step gets a pill. They come in one after the other at the bottom and push
     * the earlier ones up; when there are more than the photo holds, the oldest leave over the top,
     * so a step with many changes runs through all of them instead of hiding some. They leave
     * together as the step ends: appearing all at once and vanishing, they flashed.
     */
    private fun DrawScope.drawPills(segment: ReelSegment.Step, frame: ReelFrame, rect: Rect) {
        val step = segment.step
        val stagger = pillStaggerMs(step)
        val leaving = easeInOut(((segment.durationMs - frame.elapsedMs).toFloat() / PILL_OUT_MS).coerceIn(0f, 1f))
        val arrived = step.controls.indices.map { index ->
            easeInOut(((frame.elapsedMs - index * stagger).toFloat() / PILL_IN_MS).coerceIn(0f, 1f))
        }
        val gap = 8.dp.toPx()
        val pillHeight = 40.dp.toPx()
        val pitch = pillHeight + gap
        val bottom = rect.bottom - 16.dp.toPx()
        // Below the step's name and above the photo's bottom edge.
        val room = ((bottom - rect.top - 48.dp.toPx() + gap) / pitch).toInt().coerceAtLeast(1)
        texts.title(step.title)?.let {
            drawTag(it, Offset(rect.left + 12.dp.toPx(), rect.top + 12.dp.toPx()), alpha = minOf(arrived.firstOrNull() ?: 1f, leaving))
        }
        step.controls.forEachIndexed { index, control ->
            val shownIn = arrived[index]
            // How far the pills that came after this one have pushed it up.
            val pushed = (index + 1 until arrived.size).sumOf { arrived[it].toDouble() }.toFloat()
            val overTheTop = (pushed - (room - 1)).coerceIn(0f, 1f)
            val alpha = minOf(shownIn, leaving, 1f - overTheTop)
            if (alpha > 0f) {
                val value = control.valueIn(frame.recipe)
                val name = texts.label(control)
                val end = when {
                    value != null -> control.formatValue(value)
                    frame.progress >= 1f -> CHECK
                    else -> ""
                }
                val top = bottom - pillHeight - pushed * pitch + (1f - shownIn) * 14.dp.toPx() + (1f - leaving) * 6.dp.toPx()
                val nameLayout = measure(name, pillLabel, maxWidth = rect.width - 120.dp.toPx())
                val valueLayout = end.takeIf { it.isNotEmpty() }?.let { measure(it, pillValue) }
                val inner = nameLayout.size.width + (valueLayout?.let { it.size.width + 12.dp.toPx() } ?: 0f)
                val width = inner + 36.dp.toPx()
                val left = size.width / 2 - width / 2
                drawRoundRect(
                    Color.Black.copy(alpha = 0.58f * alpha),
                    topLeft = Offset(left, top),
                    size = Size(width, pillHeight),
                    cornerRadius = CornerRadius(pillHeight / 2),
                )
                drawText(nameLayout, topLeft = Offset(left + 18.dp.toPx(), top + (pillHeight - nameLayout.size.height) / 2), alpha = alpha)
                valueLayout?.let {
                    drawText(
                        it,
                        topLeft = Offset(left + 18.dp.toPx() + nameLayout.size.width + 12.dp.toPx(), top + (pillHeight - it.size.height) / 2),
                        alpha = alpha,
                    )
                }
            }
        }
    }

    /** The pills of a step arrive spread over most of the time its sliders move. */
    private fun pillStaggerMs(step: ReelStep): Long =
        (script.stepMoveMs * 3 / 4 / step.controls.size.coerceAtLeast(1)).coerceIn(PILL_STAGGER_MIN_MS, PILL_STAGGER_MAX_MS)

    // --- List -------------------------------------------------------------------------------------

    /**
     * The adjustments so far, the latest at the bottom: every step before [current] ticked at its
     * final value, the current one at the frame's.
     */
    private fun DrawScope.drawList(current: Int, frame: ReelFrame, alpha: Float) {
        val rows = buildList {
            script.steps.forEachIndexed { index, step ->
                if (index > current) return@forEachIndexed
                val live = index == current
                val recipe = if (live) frame.recipe else step.to
                val done = !live || frame.progress >= 1f
                step.controls.forEach { control -> add(Triple(control, control.valueIn(recipe), done)) }
            }
        }
        if (rows.isEmpty()) return
        val card = Rect(16.dp.toPx(), SHEET_TOP.toPx(), size.width - 16.dp.toPx(), SHEET_TOP.toPx() + (24 + LIST_ROWS * 34).dp.toPx())
        drawRoundRect(
            ImagoColors.Surface.copy(alpha = 0.94f * alpha),
            topLeft = card.topLeft,
            size = card.size,
            cornerRadius = CornerRadius(16.dp.toPx()),
        )
        var y = card.top + 14.dp.toPx()
        val visible = rows.takeLast(LIST_ROWS)
        visible.forEachIndexed { index, (control, value, done) ->
            // The oldest visible row fades, as if it were leaving the top of the card.
            val rowAlpha = alpha * if (index == 0 && rows.size > LIST_ROWS) 0.45f else 1f
            drawTextLine(if (done) CHECK else DOT, Offset(card.left + 16.dp.toPx(), y), valueText, if (done) ImagoColors.Gold else ImagoColors.TextTertiary, rowAlpha)
            drawTextLine(texts.fullLabel(control), Offset(card.left + 40.dp.toPx(), y), label, ImagoColors.TextSecondary, rowAlpha, maxWidth = card.width - 130.dp.toPx())
            value?.let { drawTextEnd(control.formatValue(it), card.right - 16.dp.toPx(), y, valueText, ImagoColors.TextPrimary, rowAlpha) }
            y += 34.dp.toPx()
        }
    }

    // --- comparison and closing ---------------------------------------------------------------------

    /**
     * The edited photo with the untouched one revealed from the left: the line crosses the whole
     * photo, rests on the original and comes back, so the segment starts and ends on the edit.
     *
     * The original keeps its own framing, which a crop or an Upright changes: it fills the edit's
     * frame, centred, rather than being stretched to it.
     */
    private fun DrawScope.drawComparison(after: ImageBitmap, before: ImageBitmap, rect: Rect, progress: Float) {
        drawPhoto(after, rect)
        val reveal = compareReveal(progress)
        val x = rect.left + rect.width * reveal
        if (reveal > 0.001f) {
            clipRect(rect.left, rect.top, x, rect.bottom) { drawPhotoFilling(before, rect) }
            drawLine(ImagoColors.BrandWhite, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 2.dp.toPx())
            drawCircle(Color.Black.copy(alpha = 0.5f), radius = 15.dp.toPx(), center = Offset(x, rect.center.y))
            drawCircle(ImagoColors.BrandWhite, radius = 12.dp.toPx(), center = Offset(x, rect.center.y), style = Stroke(2.dp.toPx()))
        }
        val tagTop = rect.top + 12.dp.toPx()
        if (reveal > 0.2f) drawTag(texts.before, Offset(rect.left + 12.dp.toPx(), tagTop))
        val afterLayout = measure(texts.after, tag)
        val afterLeft = rect.right - 12.dp.toPx() - afterLayout.size.width - 20.dp.toPx()
        // Once the line has passed it, the tag would sit on the original.
        if (x < afterLeft) drawTag(texts.after, Offset(afterLeft, tagTop))
    }

    /** [photo] scaled to cover [rect], centred, with what overflows cut by the photo's rounded corners. */
    private fun DrawScope.drawPhotoFilling(photo: ImageBitmap, rect: Rect) {
        val scale = max(rect.width / photo.width, rect.height / photo.height)
        val width = photo.width * scale
        val height = photo.height * scale
        clipPath(roundedPath(rect, 14.dp.toPx())) {
            drawImage(
                photo,
                dstOffset = IntOffset((rect.center.x - width / 2).roundToInt(), (rect.center.y - height / 2).roundToInt()),
                dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }

    /**
     * The photo gives way to the brand's black, and the logo builds itself: the symbol's bars draw
     * one after the other as a hand would, the square lands last, the letters arrive, then the tagline.
     */
    private fun DrawScope.drawClosing(elapsedMs: Long) {
        drawRect(ImagoColors.BrandBlack.copy(alpha = easeInOut((elapsedMs.toFloat() / CLOSING_FADE_MS).coerceIn(0f, 1f))))
        val symbol = 44.dp.toPx()
        val wordHeight = 22.dp.toPx()
        val wordWidth = wordHeight * ImagoBrand.Wordmark.viewportWidth / ImagoBrand.Wordmark.viewportHeight
        val gap = 16.dp.toPx()
        val left = size.width / 2 - (symbol + gap + wordWidth) / 2
        val top = 280.dp.toPx()
        drawSymbolBuilding(Offset(left, top), symbol, elapsedMs)
        drawWordmarkArriving(Offset(left + symbol + gap, top + (symbol - wordHeight) / 2), wordHeight, elapsedMs)
        val taglineIn = easeInOut(((elapsedMs - CLOSING_TAGLINE_FROM_MS).toFloat() / CLOSING_TAGLINE_MS).coerceIn(0f, 1f))
        if (taglineIn > 0f) {
            val tagline = measure(texts.tagline, closing, maxWidth = size.width - 64.dp.toPx())
            drawText(
                tagline,
                topLeft = Offset(size.width / 2 - tagline.size.width / 2f, top + symbol + 28.dp.toPx() + (1f - taglineIn) * 10.dp.toPx()),
                alpha = taglineIn,
            )
        }
    }

    /** Each bar of the frame grows from its start; then the square lands, with a little overshoot. */
    private fun DrawScope.drawSymbolBuilding(topLeft: Offset, height: Float, elapsedMs: Long) {
        val factor = height / ImagoBrand.Symbol.viewportHeight
        val pieces = BrandPieces.symbol
        translate(topLeft.x, topLeft.y) {
            scale(factor, factor, pivot = Offset.Zero) {
                pieces.forEachIndexed { index, piece ->
                    val bounds = piece.getBounds()
                    if (index < pieces.lastIndex) {
                        val from = CLOSING_BARS_FROM_MS + index * CLOSING_BAR_STAGGER_MS
                        val grown = easeInOut(((elapsedMs - from).toFloat() / CLOSING_BAR_MS).coerceIn(0f, 1f))
                        if (grown > 0f) {
                            val vertical = bounds.height > bounds.width
                            clipRect(
                                bounds.left,
                                bounds.top,
                                if (vertical) bounds.right else bounds.left + bounds.width * grown,
                                if (vertical) bounds.top + bounds.height * grown else bounds.bottom,
                            ) { drawPath(piece, ImagoColors.Ivory) }
                        }
                    } else {
                        val landed = ((elapsedMs - CLOSING_SQUARE_FROM_MS).toFloat() / CLOSING_SQUARE_MS).coerceIn(0f, 1f)
                        if (landed > 0f) {
                            val grow = easeOutBack(landed)
                            scale(grow, grow, pivot = bounds.center) { drawPath(piece, ImagoColors.Ivory) }
                        }
                    }
                }
            }
        }
    }

    /** The letters arrive one after the other, rising into place. */
    private fun DrawScope.drawWordmarkArriving(topLeft: Offset, height: Float, elapsedMs: Long) {
        val factor = height / ImagoBrand.Wordmark.viewportHeight
        BrandPieces.letters.forEachIndexed { index, letter ->
            val from = CLOSING_LETTERS_FROM_MS + index * CLOSING_LETTER_STAGGER_MS
            val shown = easeInOut(((elapsedMs - from).toFloat() / CLOSING_LETTER_MS).coerceIn(0f, 1f))
            if (shown > 0f) {
                translate(topLeft.x, topLeft.y + (1f - shown) * 8.dp.toPx()) {
                    scale(factor, factor, pivot = Offset.Zero) { drawPath(letter, ImagoColors.Ivory.copy(alpha = shown)) }
                }
            }
        }
    }

    /** The watermark and the person's title: on every frame, above the photo. */
    private fun DrawScope.drawChrome() {
        if (watermark) {
            val symbol = 16.dp.toPx()
            val wordHeight = 9.dp.toPx()
            val origin = Offset(20.dp.toPx(), 26.dp.toPx())
            drawBrand(ImagoBrand.Symbol, origin, symbol, ImagoColors.Ivory.copy(alpha = 0.9f))
            drawBrand(ImagoBrand.Wordmark, Offset(origin.x + symbol + 7.dp.toPx(), origin.y + (symbol - wordHeight) / 2), wordHeight, ImagoColors.Ivory.copy(alpha = 0.9f))
        }
        texts.title?.let { title ->
            val layout = measure(title, headline, maxWidth = size.width - 40.dp.toPx())
            drawText(layout, topLeft = Offset(size.width / 2 - layout.size.width / 2f, 58.dp.toPx()))
        }
    }

    // --- drawing helpers -------------------------------------------------------------------------------

    private fun DrawScope.drawTag(text: String, topLeft: Offset, alpha: Float = 1f) {
        if (alpha <= 0f) return
        val layout = measure(text, tag)
        val padX = 10.dp.toPx()
        val padY = 5.dp.toPx()
        drawRoundRect(
            Color.Black.copy(alpha = 0.55f * alpha),
            topLeft = topLeft,
            size = Size(layout.size.width + 2 * padX, layout.size.height + 2 * padY),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
        drawText(layout, topLeft = Offset(topLeft.x + padX, topLeft.y + padY))
    }

    private fun DrawScope.drawTextLine(text: String, topLeft: Offset, style: TextStyle, color: Color, alpha: Float, maxWidth: Float = Float.POSITIVE_INFINITY) {
        val layout = measure(text, style.copy(color = color), maxWidth)
        drawText(layout, topLeft = topLeft, alpha = alpha)
    }

    private fun DrawScope.drawTextEnd(text: String, right: Float, top: Float, style: TextStyle, color: Color, alpha: Float) {
        val layout = measure(text, style.copy(color = color))
        drawText(layout, topLeft = Offset(right - layout.size.width, top), alpha = alpha)
    }

    private fun DrawScope.measure(text: String, style: TextStyle, maxWidth: Float = Float.POSITIVE_INFINITY): TextLayoutResult =
        textMeasurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = if (maxWidth.isFinite()) Constraints(maxWidth = maxWidth.roundToInt().coerceAtLeast(0)) else Constraints(),
            density = this,
            layoutDirection = LayoutDirection.Ltr,
        )

    private fun fraction(value: Float, range: ClosedFloatingPointRange<Float>): Float =
        ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)

    private fun roundedPath(rect: Rect, radius: Float) = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }

    private companion object {
        const val CHECK = "✓"
        const val DOT = "•"

        val headline = TextStyle(color = ImagoColors.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        val label = TextStyle(color = ImagoColors.TextSecondary, fontSize = 14.sp)
        val valueText = TextStyle(color = ImagoColors.Gold, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val tag = TextStyle(color = ImagoColors.BrandWhite, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        val pillLabel = TextStyle(color = ImagoColors.BrandWhite, fontSize = 15.sp)
        val pillValue = TextStyle(color = ImagoColors.Gold, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val closing = TextStyle(color = ImagoColors.Ivory, fontSize = 20.sp)
    }
}

/**
 * The brand's curves at [height], in one colour. The vectors are walked by hand because a painter
 * needs a composition, and these frames are drawn outside one.
 */
/** H.264 counts in macroblocks: an odd side makes encoders refuse the job or stretch the image. */
private fun evenPixels(value: Float): Int = maxOf(2, (value.roundToInt() / 2) * 2)

/** In and out over [fade] at the ends of a segment of [duration]. */
private fun fadeInOut(elapsed: Long, duration: Long, fade: Long): Float =
    easeInOut((minOf(elapsed, duration - elapsed).toFloat() / fade).coerceIn(0f, 1f))

/** Past the target and back, a little: the way a thing lands rather than stops. */
private fun easeOutBack(t: Float): Float {
    val overshoot = 1.2f
    val x = t - 1f
    return 1f + (overshoot + 1f) * x * x * x + overshoot * x * x
}

/**
 * The brand's curves split into the pieces the closing animates, read from its own vectors: the
 * symbol's bars and square, the wordmark's letters. Nothing is redrawn by hand.
 */
private object BrandPieces {
    val symbol: List<Path> by lazy {
        vectorPaths(ImagoBrand.Symbol.root).flatMap { path ->
            // The four bars are one path in the brand file; each closed figure is one bar.
            val figures = mutableListOf<MutableList<PathNode>>(mutableListOf())
            path.pathData.forEach { node ->
                figures.last() += node
                if (node is PathNode.Close) figures += mutableListOf<PathNode>()
            }
            figures.filter { it.isNotEmpty() }.map { it.toPath().apply { fillType = path.pathFillType } }
        }
    }

    val letters: List<Path> by lazy {
        vectorPaths(ImagoBrand.Wordmark.root).map { it.pathData.toPath().apply { fillType = it.pathFillType } }
    }

    private fun vectorPaths(group: VectorGroup): List<VectorPath> = group.flatMap { node ->
        when (node) {
            is VectorPath -> listOf(node)
            is VectorGroup -> vectorPaths(node)
            else -> emptyList()
        }
    }
}

private const val BACKDROP_WIDTH = 135
private const val BACKDROP_HEIGHT = 240
private const val BACKDROP_RADIUS = 6

/**
 * One pass of a moving average along rows or columns, in place, with the edges held. Opaque pixels
 * only: the backdrop is drawn from a photo, never from anything transparent.
 */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val length = if (horizontal) width else height
    val lines = if (horizontal) height else width
    val red = IntArray(length)
    val green = IntArray(length)
    val blue = IntArray(length)
    val window = 2 * radius + 1
    for (line in 0 until lines) {
        fun index(position: Int) = if (horizontal) line * width + position else position * width + line
        for (position in 0 until length) {
            val argb = pixels[index(position)]
            red[position] = argb shr 16 and 0xFF
            green[position] = argb shr 8 and 0xFF
            blue[position] = argb and 0xFF
        }
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (offset in -radius..radius) {
            val at = offset.coerceIn(0, length - 1)
            sumR += red[at]
            sumG += green[at]
            sumB += blue[at]
        }
        for (position in 0 until length) {
            pixels[index(position)] = (0xFF shl 24) or ((sumR / window) shl 16) or ((sumG / window) shl 8) or (sumB / window)
            val leaving = (position - radius).coerceIn(0, length - 1)
            val entering = (position + radius + 1).coerceIn(0, length - 1)
            sumR += red[entering] - red[leaving]
            sumG += green[entering] - green[leaving]
            sumB += blue[entering] - blue[leaving]
        }
    }
}

private fun DrawScope.drawBrand(vector: ImageVector, topLeft: Offset, height: Float, color: Color) {
    val factor = height / vector.viewportHeight
    translate(topLeft.x, topLeft.y) {
        scale(factor, factor, pivot = Offset.Zero) { drawVectorGroup(vector.root, color) }
    }
}

private fun DrawScope.drawVectorGroup(group: VectorGroup, color: Color) {
    for (node in group) {
        when (node) {
            is VectorPath -> drawPath(node.pathData.toPath().apply { fillType = node.pathFillType }, color)
            is VectorGroup -> drawVectorGroup(node, color)
        }
    }
}
