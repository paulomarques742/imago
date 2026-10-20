package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.appString
import eu.studio742.imago.core.designsystem.i18n.resolveNow
import eu.studio742.imago.feature.editor.resources.Res
import eu.studio742.imago.feature.editor.resources.editor_color_grading
import eu.studio742.imago.feature.editor.resources.editor_color_mixer
import eu.studio742.imago.feature.editor.resources.editor_history_crop
import eu.studio742.imago.feature.editor.resources.editor_history_flip_horizontal
import eu.studio742.imago.feature.editor.resources.editor_history_flip_vertical
import eu.studio742.imago.feature.editor.resources.editor_history_geometry
import eu.studio742.imago.feature.editor.resources.editor_history_rotated
import eu.studio742.imago.feature.editor.resources.editor_panel_detail
import eu.studio742.imago.feature.editor.resources.editor_panel_effects
import eu.studio742.imago.feature.editor.resources.editor_panel_light
import eu.studio742.imago.feature.editor.resources.editor_panel_masks
import eu.studio742.imago.feature.editor.resources.editor_straighten
import eu.studio742.imago.feature.editor.resources.editor_tone_curve
import eu.studio742.imago.feature.editor.resources.editor_upright
import eu.studio742.imago.feature.editor.resources.editor_panel_color
import eu.studio742.imago.feature.editor.resources.reel_after
import eu.studio742.imago.feature.editor.resources.reel_before
import eu.studio742.imago.feature.editor.resources.reel_tagline

/**
 * Every word a video shows, resolved once in the app's language before the first frame.
 *
 * The frames are drawn outside any composition, hundreds of times; resolving here keeps them from
 * suspending and keeps one language from start to finish.
 */
class ReelTexts internal constructor(
    val before: String,
    val after: String,
    val tagline: String,
    /** The person's own line over the video, or null. */
    val title: String?,
    private val controls: Map<ReelControl, String>,
    private val titles: Map<ReelTitle, String>,
) {
    fun label(control: ReelControl): String = controls.getValue(control)

    /**
     * The label where no step title stands beside it, as in the list: a mask's slider says which
     * mask, or it would read as the global one.
     */
    fun fullLabel(control: ReelControl): String = when (control) {
        is ReelControl.MaskSlider -> "${controls.getValue(ReelControl.MaskArea(control.maskId))} · ${label(control)}"
        else -> label(control)
    }

    /** Null for a step that joined several panels: its sliders say what it is. */
    fun title(title: ReelTitle): String? = titles[title]
}

suspend fun reelTexts(script: EditReelScript, title: String?): ReelTexts {
    val masks = script.steps.flatMap { it.from.masks + it.to.masks }.associateBy { it.id }
    suspend fun maskName(id: String) = masks[id]?.name?.takeIf { it.isNotBlank() } ?: appString(Res.string.editor_panel_masks)

    val shown = script.steps.flatMap { it.controls }
    val maskAreas = shown.filterIsInstance<ReelControl.MaskSlider>().map { ReelControl.MaskArea(it.maskId) }
    val controls = (shown + maskAreas).distinct().associateWith { control ->
        when (control) {
            is ReelControl.Slider -> appString(control.adjustment.labelRes)
            is ReelControl.HslSlider -> "${appString(control.band.labelRes)} · ${appString(control.component.labelRes)}"
            is ReelControl.GradeSlider -> "${appString(control.wheel.labelRes)} · ${appString(control.component.labelRes)}"
            is ReelControl.Curve -> "${appString(Res.string.editor_tone_curve)} · ${appString(control.channel.labelRes)}"
            ReelControl.Rotation -> appString(Res.string.editor_history_rotated)
            ReelControl.FlipHorizontal -> appString(Res.string.editor_history_flip_horizontal)
            ReelControl.FlipVertical -> appString(Res.string.editor_history_flip_vertical)
            ReelControl.Straighten -> appString(Res.string.editor_straighten)
            ReelControl.Crop -> appString(Res.string.editor_history_crop)
            ReelControl.Upright -> appString(Res.string.editor_upright)
            is ReelControl.MaskArea -> maskName(control.maskId)
            is ReelControl.MaskSlider -> appString(control.adjustment.labelRes)
        }
    }
    val titles = script.steps.map { it.title }.distinct().mapNotNull { stepTitle ->
        val text = when (stepTitle) {
            is ReelTitle.Panel -> appString(stepTitle.panel.labelRes())
            is ReelTitle.Mask -> maskName(stepTitle.maskId)
            // The history mostly names the slider that moved; above that same slider it would only
            // repeat it, so the step takes its panel's name, or its mask's.
            is ReelTitle.History -> {
                val text = stepTitle.label.resolveNow()
                val steps = script.steps.filter { it.title == stepTitle }
                if (steps.flatMap { it.controls }.none { controls.getValue(it) == text }) {
                    text
                } else {
                    when (val panel = steps.flatMap { it.panels }.distinct().singleOrNull()) {
                        null -> null
                        ReelPanel.MASKS -> steps.firstNotNullOfOrNull { it.maskId() }?.let { maskName(it) }
                        else -> appString(panel.labelRes())
                    }
                }
            }
            ReelTitle.Combined -> null
        }
        text?.let { stepTitle to it }
    }.toMap()
    return ReelTexts(
        before = appString(Res.string.reel_before),
        after = appString(Res.string.reel_after),
        tagline = appString(Res.string.reel_tagline),
        title = title?.trim()?.takeIf { it.isNotEmpty() },
        controls = controls,
        titles = titles,
    )
}

private fun ReelPanel.labelRes() = when (this) {
    ReelPanel.GEOMETRY -> Res.string.editor_history_geometry
    ReelPanel.LIGHT -> Res.string.editor_panel_light
    ReelPanel.CURVE -> Res.string.editor_tone_curve
    ReelPanel.COLOR -> Res.string.editor_panel_color
    ReelPanel.HSL -> Res.string.editor_color_mixer
    ReelPanel.COLOR_GRADING -> Res.string.editor_color_grading
    ReelPanel.DETAIL -> Res.string.editor_panel_detail
    ReelPanel.EFFECTS -> Res.string.editor_panel_effects
    ReelPanel.MASKS -> Res.string.editor_panel_masks
}

/**
 * The number beside a slider, as the editor writes it. A value on its way out of zero would read
 * "-0" for a few frames; zero has no sign to show.
 */
internal fun ReelControl.formatValue(value: Float): String = formatRawValue(value).let { text ->
    if (text.startsWith("-") && text.none { it in '1'..'9' }) "+" + text.drop(1) else text
}

private fun ReelControl.formatRawValue(value: Float): String = when (this) {
    is ReelControl.Slider -> formatAdjustmentValue(adjustment, value)
    is ReelControl.MaskSlider -> formatAdjustmentValue(adjustment, value)
    is ReelControl.HslSlider -> "%+.0f".format(value)
    is ReelControl.GradeSlider -> component.format(value)
    ReelControl.Straighten -> "%+.1f°".format(value)
    else -> ""
}

/** Where a slider sits: its range and the value it starts from. */
internal fun ReelControl.scale(): Pair<ClosedFloatingPointRange<Float>, Float>? = when (this) {
    is ReelControl.Slider -> adjustment.range to adjustment.neutral
    is ReelControl.MaskSlider -> adjustment.range to adjustment.neutral
    is ReelControl.HslSlider -> -100f..100f to 0f
    is ReelControl.GradeSlider -> component.range() to 0f
    ReelControl.Straighten -> -45f..45f to 0f
    else -> null
}

/** The mask a step is about, if it is about one: its outline is drawn while the sliders move. */
internal fun ReelStep.maskId(): String? =
    (title as? ReelTitle.Mask)?.maskId
        ?: controls.firstNotNullOfOrNull {
            when (it) {
                is ReelControl.MaskArea -> it.maskId
                is ReelControl.MaskSlider -> it.maskId
                else -> null
            }
        }
