package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent

/** The photo untouched, before the first step. */
const val REEL_BEFORE_MS = 1_000L

/**
 * The time the steps share. They spread over it so each one breathes, within the limits below: a
 * video with two steps does not stretch them to seven seconds each.
 */
const val REEL_EDITS_MS = 15_000L

/** The shortest a step gets, when there are many: under this the eye cannot follow a slider. */
const val REEL_STEP_MIN_MS = 1_250L

/** The longest a step gets, when there are few. */
const val REEL_STEP_MAX_MS = 3_000L

/** The part of a step in which the sliders move; the rest is a pause to see what they did. */
const val REEL_STEP_MOVE_SHARE = 0.7f

/**
 * The part of a step that introduces a mask: its red region appears and, for a brush, the strokes
 * are painted, before any slider moves. The rest of the step is the sliders and the pause.
 */
const val REEL_MASK_INTRO_SHARE = 0.4f

/**
 * A step that crops: in the first part the whole photo is on screen and the frame closes on what will be
 * kept, as in the editor's crop; from there until [REEL_CROP_ZOOM_END_SHARE] the view zooms into the
 * frame; the rest is the cropped photo, holding.
 */
const val REEL_CROP_CLOSE_SHARE = 0.5f
const val REEL_CROP_ZOOM_END_SHARE = 0.8f

/** A step that starts on a crop already made first opens out to the whole photo, over this part of it. */
private const val REEL_CROP_OPEN_SHARE = 0.15f

/** The crop frame fades in over this. */
private const val REEL_CROP_FADE_MS = 250L

/**
 * The line that sweeps across the photo, original on one side and edited on the other: all the way
 * across, a pause on the original, and back.
 */
const val REEL_COMPARE_MS = 3_000L

/** The logo building itself, then the tagline. */
const val REEL_CLOSING_MS = 3_000L

/** Short enough for a story or a reel. */
const val REEL_MAX_MS = REEL_BEFORE_MS + REEL_EDITS_MS + REEL_COMPARE_MS + REEL_CLOSING_MS

/** How many steps fit in [REEL_EDITS_MS] at their shortest; past this, neighbouring steps are joined. */
internal val REEL_MAX_STEPS = (REEL_EDITS_MS / REEL_STEP_MIN_MS).toInt()

/** How long each of [count] steps lasts: their share of [REEL_EDITS_MS], within the limits. */
internal fun reelStepMs(count: Int): Long =
    if (count == 0) REEL_STEP_MAX_MS else (REEL_EDITS_MS / count).coerceIn(REEL_STEP_MIN_MS, REEL_STEP_MAX_MS)

/** What a step is called on screen. */
sealed interface ReelTitle {
    data class Panel(val panel: ReelPanel) : ReelTitle

    /** The mask's name lives in the step's recipes, under this id. */
    data class Mask(val maskId: String) : ReelTitle

    /** The history entry's own label: the same words the History sheet showed. */
    data class History(val label: UiText) : ReelTitle

    /** Steps from different panels joined to fit the length: only the sliders speak. */
    data object Combined : ReelTitle
}

/** One move of the video: the photo goes from [from] to [to] while [controls] slide. */
data class ReelStep(
    val from: EditRecipe,
    val to: EditRecipe,
    val controls: List<ReelControl>,
    val title: ReelTitle,
) {
    val panels: Set<ReelPanel> get() = controls.mapTo(linkedSetOf()) { it.panel }
}

sealed interface ReelSegment {
    val durationMs: Long

    data object Before : ReelSegment {
        override val durationMs: Long get() = REEL_BEFORE_MS
    }

    /**
     * @param introMs how long the mask is shown being drawn before the sliders start; zero for any
     *   step that is not about drawing a mask
     */
    data class Step(val step: ReelStep, override val durationMs: Long, val introMs: Long = 0L) : ReelSegment {
        /** The time left for the sliders and the pause that follows them. */
        val adjustMs: Long get() = durationMs - introMs
    }

    data object Compare : ReelSegment {
        override val durationMs: Long get() = REEL_COMPARE_MS
    }

    data object Closing : ReelSegment {
        override val durationMs: Long get() = REEL_CLOSING_MS
    }
}

data class ReelControlValue(val control: ReelControl, val value: Float?)

/**
 * A mask being introduced: the photo as the editor shows it while the mask is drawn.
 *
 * @param recipe the recipe with the mask drawn so far, to render with the mask's red over it
 * @param alpha how much of that red is on the photo
 */
data class ReelMaskReveal(val maskId: String, val recipe: EditRecipe, val alpha: Float)

/**
 * A crop being made, as the editor shows it: the photo rendered whole and the frame over what is kept.
 * Both rectangles are in the uncropped photo's coordinates, from 0 to 1.
 *
 * @param frame the crop frame
 * @param view the part of the uncropped photo on screen: all of it while the frame closes, then
 *   narrowing to the frame, which is the zoom into the crop
 * @param alpha how much of the frame, its grid and the dimming outside it shows
 */
data class ReelCropView(val frame: CropRect, val view: CropRect, val alpha: Float)

/**
 * What to draw at one instant.
 *
 * @param progress how far the segment has gone: for a step, the eased progress of its sliders, which
 *   reaches 1 when the hold begins; for the other segments, plain time.
 * @param elapsedMs the time since the segment began, for what animates on its own clock; in a step
 *   with an introduction, since the sliders started
 * @param recipe what to render the photo with. Frames whose recipe is equal can reuse the render.
 * @param reveal set while a step introduces its mask, before its sliders move
 * @param crop set while a step's crop is being made; [recipe] is then the photo uncropped
 */
data class ReelFrame(
    val segment: ReelSegment,
    val segmentIndex: Int,
    val progress: Float,
    val elapsedMs: Long,
    val recipe: EditRecipe,
    val controls: List<ReelControlValue>,
    val reveal: ReelMaskReveal? = null,
    val crop: ReelCropView? = null,
)

/**
 * The script of the "Show your edit" video: which steps, how long each takes and, for any instant,
 * the recipe to render and the value of every slider on screen.
 *
 * It draws and encodes nothing; the frames and the encoders read it.
 */
class EditReelScript private constructor(
    /** The photo the video starts from. */
    val before: EditRecipe,
    /** The photo it ends with: exactly the recipe the editor saved. */
    val after: EditRecipe,
    val steps: List<ReelStep>,
) {
    /**
     * The untouched side of the comparison: the photo as it came in, framing included. A crop or an
     * Upright is part of the edit, and a before already in the final framing would hide it.
     */
    val compareBefore: EditRecipe = before

    /** How long each step lasts in this video. */
    val stepMs: Long = reelStepMs(steps.size)

    /** How long the sliders of a step take to reach their value. */
    val stepMoveMs: Long = (stepMs * REEL_STEP_MOVE_SHARE).toLong()

    /** How long the mask of [step] takes to be drawn, before its sliders move. */
    fun introMs(step: ReelStep): Long = if (step.revealedMask() != null) (stepMs * REEL_MASK_INTRO_SHARE).toLong() else 0L

    /** How long the sliders of [step] take to reach their value; a crop leaves time to zoom into it. */
    fun moveMs(step: ReelStep): Long =
        if (step.crops()) (stepMs * REEL_CROP_CLOSE_SHARE).toLong() else ((stepMs - introMs(step)) * REEL_STEP_MOVE_SHARE).toLong()

    val segments: List<ReelSegment> =
        listOf<ReelSegment>(ReelSegment.Before) + steps.map { ReelSegment.Step(it, stepMs, introMs(it)) } +
            ReelSegment.Compare + ReelSegment.Closing

    val durationMs: Long = segments.sumOf { it.durationMs }

    fun frameAt(timeMs: Long): ReelFrame {
        var start = 0L
        val clamped = timeMs.coerceIn(0L, durationMs - 1)
        segments.forEachIndexed { index, segment ->
            val elapsed = clamped - start
            if (elapsed < segment.durationMs) return frameIn(segment, index, elapsed)
            start += segment.durationMs
        }
        error("$timeMs is past a script of $durationMs ms")
    }

    private fun frameIn(segment: ReelSegment, index: Int, elapsedMs: Long): ReelFrame = when (segment) {
        is ReelSegment.Step -> {
            val step = segment.step
            val reveal = step.revealedMask()?.takeIf { elapsedMs < segment.introMs }?.let {
                revealAt(step, it, elapsedMs.toFloat() / segment.introMs)
            }
            val adjusting = (elapsedMs - segment.introMs).coerceAtLeast(0L)
            val progress = if (reveal != null) 0f else easeInOut((adjusting.toFloat() / moveMs(step)).coerceAtMost(1f))
            val recipe = interpolateRecipe(step.from, step.to, progress)
            val crop = if (step.crops()) cropViewAt(step, recipe, elapsedMs) else null
            ReelFrame(
                segment = segment,
                segmentIndex = index,
                progress = progress,
                elapsedMs = adjusting,
                recipe = if (crop != null) recipe.uncropped() else recipe,
                controls = step.controls.map { ReelControlValue(it, it.valueIn(recipe)) },
                reveal = reveal,
                crop = crop,
            )
        }
        else -> ReelFrame(
            segment = segment,
            segmentIndex = index,
            progress = elapsedMs.toFloat() / segment.durationMs,
            elapsedMs = elapsedMs,
            recipe = if (segment == ReelSegment.Before) before else after,
            controls = emptyList(),
        )
    }

    /**
     * The crop of [step] at [elapsedMs]: the frame follows the sliders' [recipe] while the whole photo is
     * on view, then the view zooms into it. Null once the zoom has ended, when the photo is the crop.
     */
    private fun cropViewAt(step: ReelStep, recipe: EditRecipe, elapsedMs: Long): ReelCropView? {
        val zoomStart = moveMs(step)
        val zoomEnd = (stepMs * REEL_CROP_ZOOM_END_SHARE).toLong()
        if (elapsedMs >= zoomEnd) return null
        val whole = CropRect()
        val fromCrop = step.from.geometry.cropRect
        val zoom = easeInOut(((elapsedMs - zoomStart).toFloat() / (zoomEnd - zoomStart)).coerceIn(0f, 1f))
        val view = if (zoom > 0f) {
            lerpCrop(whole, step.to.geometry.cropRect, zoom)
        } else {
            lerpCrop(fromCrop, whole, easeInOut((elapsedMs.toFloat() / (stepMs * REEL_CROP_OPEN_SHARE)).coerceIn(0f, 1f)))
        }
        val alpha = minOf((elapsedMs.toFloat() / REEL_CROP_FADE_MS).coerceIn(0f, 1f), 1f - zoom)
        return ReelCropView(frame = recipe.geometry.cropRect, view = view, alpha = alpha)
    }

    /** The step's mask as the editor shows it at [t] of its introduction: drawn so far, with its red. */
    private fun revealAt(step: ReelStep, mask: LocalMask, t: Float): ReelMaskReveal {
        val before = step.from.mask(mask.id)
        val painted = easeInOut((t / REEL_MASK_DRAW_SHARE).coerceIn(0f, 1f))
        val drawn = mask.copy(
            adjustments = before?.adjustments ?: LocalAdjustments(),
            components = mask.components.map { it.paintedTo(painted) },
        )
        val masks = if (before != null) step.from.masks.map { if (it.id == mask.id) drawn else it } else step.from.masks + drawn
        val alpha = minOf(t / REEL_MASK_FADE_SHARE, (1f - t) / REEL_MASK_FADE_SHARE, 1f).coerceIn(0f, 1f)
        return ReelMaskReveal(mask.id, step.from.copy(masks = masks), alpha)
    }

    companion object {
        /**
         * From the recipe as it is: every panel that is off neutral becomes a step, in panel order,
         * and every mask a step of its own. Works for any edited photo.
         */
        fun fromRecipe(recipe: EditRecipe): EditReelScript =
            EditReelScript(recipe.neutral(), recipe, fitLength(stepsToward(recipe)))

        /**
         * From the editor's history, up to [index]: what was undone and not redone is left out.
         *
         * A session that started on a photo already edited first plays that earlier edit by panels,
         * so the video still starts from the untouched photo.
         */
        fun fromHistory(entries: List<HistoryEntry>, index: Int): EditReelScript {
            require(index in entries.indices) { "History index $index outside ${entries.size} entries" }
            val path = entries.subList(0, index + 1)
            val origin = path.first().recipe
            val sessionSteps = path.zipWithNext { previous, entry ->
                ReelStep(
                    from = previous.recipe,
                    to = entry.recipe,
                    controls = changedControls(previous.recipe, entry.recipe),
                    title = ReelTitle.History(entry.label),
                )
            }.filterNot { it.from.drawsLike(it.to) }
            val steps = stepsToward(origin) + joinRepeats(sessionSteps)
            return EditReelScript(origin.neutral(), path.last().recipe, fitLength(steps))
        }
    }
}

/** Whether [this] step makes or changes a crop: it is shown as the editor's crop, frame first and zoom after. */
internal fun ReelStep.crops(): Boolean = ReelControl.Crop in controls

/** [this] recipe with the whole photo in frame: what the editor's crop shows under the frame. */
internal fun EditRecipe.uncropped(): EditRecipe = copy(geometry = geometry.copy(cropRect = CropRect()))

private fun lerpCrop(a: CropRect, b: CropRect, t: Float) = CropRect(
    x = a.x + (b.x - a.x) * t,
    y = a.y + (b.y - a.y) * t,
    w = a.w + (b.w - a.w) * t,
    h = a.h + (b.h - a.h) * t,
)

/** The part of the introduction in which a brush is painted; the finished mask stays in view after it. */
private const val REEL_MASK_DRAW_SHARE = 0.7f

/** The part of the introduction the red takes to come in, and again to go out. */
private const val REEL_MASK_FADE_SHARE = 0.2f

/**
 * The mask a step brings in, if it does: the one its title names, or one whose shapes the step
 * changes. A step that only moves the sliders of a mask already on screen has no introduction.
 */
internal fun ReelStep.revealedMask(): LocalMask? {
    val id = (title as? ReelTitle.Mask)?.maskId
        ?: controls.firstNotNullOfOrNull { (it as? ReelControl.MaskArea)?.maskId }
        ?: return null
    return to.mask(id)?.takeIf { it.enabled && it.components.isNotEmpty() }
}

/** This component with only the first [t] of its brush strokes painted: the finger's path, point by point. */
internal fun MaskComponent.paintedTo(t: Float): MaskComponent {
    val brush = brush?.takeIf { it.strokes.isNotEmpty() } ?: return this
    if (t >= 1f) return this
    val total = brush.strokes.sumOf { it.points.size / 2 }
    var budget = t * total
    val strokes = mutableListOf<BrushStroke>()
    for (stroke in brush.strokes) {
        if (budget <= 0f) break
        val count = stroke.points.size / 2
        if (budget >= count) {
            strokes += stroke
            budget -= count
            continue
        }
        // The last point is part way along its segment, so the stroke grows smoothly.
        val whole = budget.toInt()
        val fraction = budget - whole
        val points = stroke.points.take(whole * 2).toMutableList()
        if (whole == 0) {
            points += stroke.points.take(2)
        } else if (fraction > 0f) {
            val x = points[whole * 2 - 2]
            val y = points[whole * 2 - 1]
            points += listOf(x + (stroke.points[whole * 2] - x) * fraction, y + (stroke.points[whole * 2 + 1] - y) * fraction)
        }
        strokes += stroke.copy(points = points)
        break
    }
    return copy(brush = brush.copy(strokes = strokes))
}

/** The steps from the untouched photo to [target], one per panel and one per mask. */
private fun stepsToward(target: EditRecipe): List<ReelStep> {
    val steps = mutableListOf<ReelStep>()
    var current = target.neutral()
    fun moveTo(next: EditRecipe, title: ReelTitle) {
        if (!next.drawsLike(current)) steps += ReelStep(current, next, changedControls(current, next), title)
        current = next
    }
    for (panel in ReelPanel.entries) {
        if (panel == ReelPanel.MASKS) {
            // The untouched recipe already has the same masks, in the same order, with zero adjustments.
            target.masks.forEachIndexed { index, mask ->
                moveTo(current.copy(masks = current.masks.toMutableList().also { it[index] = mask }), ReelTitle.Mask(mask.id))
            }
        } else {
            moveTo(current.withPanelOf(target, panel), ReelTitle.Panel(panel))
        }
    }
    return absorbSilentSteps(steps)
}

private fun EditRecipe.withPanelOf(target: EditRecipe, panel: ReelPanel): EditRecipe = when (panel) {
    ReelPanel.GEOMETRY -> copy(geometry = target.geometry, lens = target.lens)
    ReelPanel.LIGHT -> copy(tone = target.tone)
    ReelPanel.CURVE -> copy(toneCurve = target.toneCurve)
    ReelPanel.COLOR -> copy(
        whiteBalance = target.whiteBalance,
        calibration = target.calibration,
        presence = presence.copy(vibrance = target.presence.vibrance, saturation = target.presence.saturation),
    )
    ReelPanel.HSL -> copy(hsl = target.hsl)
    ReelPanel.COLOR_GRADING -> copy(colorGrading = target.colorGrading)
    ReelPanel.DETAIL -> copy(
        presence = presence.copy(texture = target.presence.texture, clarity = target.presence.clarity, dehaze = target.presence.dehaze),
        detail = target.detail,
    )
    ReelPanel.EFFECTS -> copy(effects = target.effects)
    ReelPanel.MASKS -> copy(masks = target.masks)
}

/**
 * A step with nothing to show — a change to a field with no slider, like sharpening — joins
 * the next one, or the previous one when it is the last. The photo still changes; it just does so
 * under someone else's sliders.
 */
private fun absorbSilentSteps(steps: List<ReelStep>): List<ReelStep> {
    val result = steps.toMutableList()
    var index = 0
    while (index < result.size && result.size > 1) {
        if (result[index].controls.isNotEmpty()) {
            index++
            continue
        }
        if (index < result.lastIndex) {
            result[index] = join(result[index], result[index + 1])
            result.removeAt(index + 1)
        } else {
            result[index - 1] = join(result[index - 1], result[index])
            result.removeAt(index)
        }
    }
    return result
}

/** Five drags of the exposure are one rise of the exposure on screen. */
private fun joinRepeats(steps: List<ReelStep>): List<ReelStep> {
    val result = mutableListOf<ReelStep>()
    for (step in steps) {
        val last = result.lastOrNull()
        if (last != null && last.controls.toSet() == step.controls.toSet()) {
            result[result.lastIndex] = join(last, step)
        } else {
            result += step
        }
    }
    return absorbSilentSteps(result)
}

/**
 * Joins neighbours until the video fits [REEL_MAX_MS]: first those of the same panel, then, if that
 * is not enough, the pair with the fewest sliders between them, so the busiest steps stay readable.
 */
private fun fitLength(steps: List<ReelStep>): List<ReelStep> {
    if (steps.size <= REEL_MAX_STEPS) return steps
    val result = mutableListOf<ReelStep>()
    for (step in steps) {
        val last = result.lastOrNull()
        if (last != null && last.panels.size == 1 && last.panels == step.panels) {
            result[result.lastIndex] = join(last, step)
        } else {
            result += step
        }
    }
    while (result.size > REEL_MAX_STEPS) {
        val pair = (0 until result.lastIndex).minBy { result[it].controls.size + result[it + 1].controls.size }
        result[pair] = join(result[pair], result[pair + 1])
        result.removeAt(pair + 1)
    }
    return result
}

/**
 * One step from where [first] starts to where [second] ends. The sliders are those that end up
 * somewhere else: a slider that went up and came back down has nothing to show.
 */
private fun join(first: ReelStep, second: ReelStep): ReelStep {
    val controls = changedControls(first.from, second.to)
    val title = when {
        first.controls.isEmpty() -> second.title
        second.controls.isEmpty() -> first.title
        first.title == second.title -> second.title
        first.controls.toSet() == second.controls.toSet() -> second.title
        else -> controls.mapTo(linkedSetOf()) { it.panel }.singleOrNull()
            ?.takeUnless { it == ReelPanel.MASKS }
            ?.let { ReelTitle.Panel(it) }
            ?: ReelTitle.Combined
    }
    return ReelStep(first.from, second.to, controls, title)
}

/** Slow out of the start, slow into the value: how a hand moves a slider. */
/** The part of the comparison the line takes to cross the photo, and as much to come back. */
internal const val REEL_COMPARE_SWEEP_SHARE = 0.36f

/**
 * Where the comparison's line is, from 0 (the edit, whole) to 1 (the original, whole), at [progress]
 * through the segment: across, a rest on the original, and back.
 */
internal fun compareReveal(progress: Float): Float = when {
    progress < REEL_COMPARE_SWEEP_SHARE -> easeInOut(progress / REEL_COMPARE_SWEEP_SHARE)
    progress > 1f - REEL_COMPARE_SWEEP_SHARE -> easeInOut((1f - progress) / REEL_COMPARE_SWEEP_SHARE)
    else -> 1f
}

internal fun easeInOut(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
}
