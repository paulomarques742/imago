package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.asUiText
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.CurvePoint
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.RadialMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditReelScriptTest {
    private val base = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-10-19T12:00:00Z",
        updatedAt = "2026-10-19T12:00:00Z",
    )

    private val edited = base.copy(
        tone = base.tone.copy(exposure = 0.8f, contrast = 20f),
        whiteBalance = base.whiteBalance.copy(temp = 15f),
        hsl = base.hsl.copy(blue = base.hsl.blue.copy(saturation = -30f)),
        geometry = base.geometry.copy(cropRect = CropRect(0.1f, 0.1f, 0.8f, 0.8f), straighten = 2f),
        masks = listOf(
            LocalMask(
                id = "sky",
                name = "Sky",
                components = listOf(MaskComponent(MaskShape.RADIAL, radial = RadialMask())),
                adjustments = LocalAdjustments(exposure = -0.5f),
            ),
        ),
    )

    private fun entry(id: String, recipe: EditRecipe, label: String = id) = HistoryEntry(
        id = id,
        label = label.asUiText(),
        detail = "Manual".asUiText(),
        valueText = null,
        icon = HistoryIcon.EXPOSURE,
        timestamp = "2026-10-19T12:00:00Z",
        recipe = recipe,
    )

    private fun stepAt(script: EditReelScript, index: Int, elapsedMs: Long): ReelFrame =
        script.frameAt(REEL_BEFORE_MS + index * script.stepMs + elapsedMs)

    @Test
    fun `from the recipe, panels come in order and the video ends on the saved recipe`() {
        val script = EditReelScript.fromRecipe(edited)

        assertEquals(
            listOf(
                ReelTitle.Panel(ReelPanel.GEOMETRY),
                ReelTitle.Panel(ReelPanel.LIGHT),
                ReelTitle.Panel(ReelPanel.COLOR),
                ReelTitle.Panel(ReelPanel.HSL),
                ReelTitle.Mask("sky"),
            ),
            script.steps.map { it.title },
        )
        assertEquals(edited.neutral(), script.before)
        assertEquals(edited, script.steps.last().to)
        script.steps.zipWithNext { a, b -> assertEquals(a.to, b.from) }
        assertEquals(edited, stepAt(script, script.steps.lastIndex, script.stepMoveMs).recipe)
        assertEquals(edited, script.frameAt(script.durationMs - 1).recipe)
    }

    @Test
    fun `the first frame is the untouched photo with the masks still there`() {
        val frame = EditReelScript.fromRecipe(edited).frameAt(0)

        assertEquals(ReelSegment.Before, frame.segment)
        assertEquals(0f, frame.recipe.tone.exposure)
        assertEquals(CropRect(), frame.recipe.geometry.cropRect)
        assertEquals(listOf("sky"), frame.recipe.masks.map { it.id })
        assertTrue(frame.recipe.masks.single().adjustments.isNeutral)
    }

    @Test
    fun `sliders show the value the photo under them was rendered with`() {
        val script = EditReelScript.fromRecipe(edited)
        val toneIndex = script.steps.indexOfFirst { it.title == ReelTitle.Panel(ReelPanel.LIGHT) }
        val halfway = stepAt(script, toneIndex, script.stepMoveMs / 2)

        assertEquals(
            listOf(ReelControl.Slider(Adjustment.EXPOSURE), ReelControl.Slider(Adjustment.CONTRAST)),
            halfway.controls.map { it.control },
        )
        assertEquals(halfway.recipe.tone.exposure, halfway.controls.first().value)
        assertEquals(0.4f, halfway.recipe.tone.exposure, 0.001f)

        val holding = stepAt(script, toneIndex, script.stepMoveMs + 100)
        assertEquals(1f, holding.progress)
        assertEquals(0.8f, holding.controls.first().value!!, 0f)
    }

    @Test
    fun `an untouched photo is only the before, the comparison and the closing`() {
        val script = EditReelScript.fromRecipe(base)

        assertEquals(emptyList<ReelStep>(), script.steps)
        assertEquals(REEL_BEFORE_MS + REEL_COMPARE_MS + REEL_CLOSING_MS, script.durationMs)
    }

    @Test
    fun `a change with no slider rides along with a neighbouring step`() {
        val recipe = base.copy(
            detail = base.detail.copy(noiseReductionLuminance = 40f),
            effects = base.effects.copy(vignetteAmount = -20f),
        )
        val script = EditReelScript.fromRecipe(recipe)

        assertEquals(1, script.steps.size)
        assertEquals(ReelTitle.Panel(ReelPanel.EFFECTS), script.steps.single().title)
        assertEquals(recipe, script.steps.single().to)
    }

    @Test
    fun `history plays only up to the current entry`() {
        val first = base.copy(tone = base.tone.copy(exposure = 1f))
        val second = first.copy(presence = first.presence.copy(vibrance = 30f))
        val undone = second.copy(effects = second.effects.copy(vignetteAmount = -40f))
        val entries = listOf(entry("original", base), entry("1", first), entry("2", second), entry("3", undone))

        val script = EditReelScript.fromHistory(entries, index = 2)

        assertEquals(second, script.after)
        assertEquals(
            listOf(ReelTitle.History("1".asUiText()), ReelTitle.History("2".asUiText())),
            script.steps.map { it.title },
        )
        assertEquals(second, script.frameAt(script.durationMs - 1).recipe)
    }

    @Test
    fun `repeated drags of one slider become one step`() {
        val drags = listOf(0.3f, 0.9f, 0.6f).map { base.copy(tone = base.tone.copy(exposure = it)) }
        val entries = listOf(entry("original", base)) +
            drags.mapIndexed { i, recipe -> entry("$i", recipe, "Exposure") }

        val script = EditReelScript.fromHistory(entries, entries.lastIndex)

        assertEquals(1, script.steps.size)
        assertEquals(base, script.steps.single().from)
        assertEquals(drags.last(), script.steps.single().to)
        assertEquals(listOf(ReelControl.Slider(Adjustment.EXPOSURE)), script.steps.single().controls)
    }

    @Test
    fun `a session on a photo already edited first plays the earlier edit`() {
        val earlier = base.copy(tone = base.tone.copy(contrast = 25f))
        val later = earlier.copy(whiteBalance = earlier.whiteBalance.copy(tint = 10f))

        val script = EditReelScript.fromHistory(listOf(entry("original", earlier), entry("1", later)), 1)

        assertEquals(base.neutral(), script.before)
        assertEquals(
            listOf(ReelTitle.Panel(ReelPanel.LIGHT), ReelTitle.History("1".asUiText())),
            script.steps.map { it.title },
        )
    }

    @Test
    fun `entries that change nothing in the photo are left out`() {
        val touched = base.copy(updatedAt = "2026-10-19T12:05:00Z")
        val script = EditReelScript.fromHistory(listOf(entry("original", base), entry("1", touched)), 1)

        assertEquals(emptyList<ReelStep>(), script.steps)
    }

    @Test
    fun `too many steps are joined by panel and the video stays within its length`() {
        var recipe = base
        val entries = mutableListOf(entry("original", base))
        // Twenty HSL moves on alternating components, so no two neighbours repeat the same slider.
        repeat(20) { i ->
            val band = HslColorBand.entries[i % HslColorBand.entries.size]
            recipe = when (band) {
                HslColorBand.RED -> recipe.copy(hsl = recipe.hsl.copy(red = recipe.hsl.red.copy(hue = i + 1f)))
                HslColorBand.ORANGE -> recipe.copy(hsl = recipe.hsl.copy(orange = recipe.hsl.orange.copy(hue = i + 1f)))
                HslColorBand.YELLOW -> recipe.copy(hsl = recipe.hsl.copy(yellow = recipe.hsl.yellow.copy(hue = i + 1f)))
                HslColorBand.GREEN -> recipe.copy(hsl = recipe.hsl.copy(green = recipe.hsl.green.copy(hue = i + 1f)))
                HslColorBand.AQUA -> recipe.copy(hsl = recipe.hsl.copy(aqua = recipe.hsl.aqua.copy(hue = i + 1f)))
                HslColorBand.BLUE -> recipe.copy(hsl = recipe.hsl.copy(blue = recipe.hsl.blue.copy(hue = i + 1f)))
                HslColorBand.PURPLE -> recipe.copy(hsl = recipe.hsl.copy(purple = recipe.hsl.purple.copy(hue = i + 1f)))
                HslColorBand.MAGENTA -> recipe.copy(hsl = recipe.hsl.copy(magenta = recipe.hsl.magenta.copy(hue = i + 1f)))
            }
            entries += entry("$i", recipe, "HSL")
        }
        repeat(15) { i ->
            recipe = if (i % 2 == 0) {
                recipe.copy(tone = recipe.tone.copy(exposure = i / 10f))
            } else {
                recipe.copy(effects = recipe.effects.copy(grainAmount = i.toFloat()))
            }
            entries += entry("t$i", recipe)
        }

        val script = EditReelScript.fromHistory(entries, entries.lastIndex)

        assertTrue(script.durationMs <= REEL_MAX_MS)
        assertTrue(script.steps.size <= REEL_MAX_STEPS)
        assertEquals(ReelTitle.History("HSL".asUiText()), script.steps.first().title)
        assertEquals(8, script.steps.first().controls.size)
        script.steps.zipWithNext { a, b -> assertEquals(a.to, b.from) }
        assertEquals(recipe, script.frameAt(script.durationMs - 1).recipe)
    }

    @Test
    fun `curves with different points blend through the points of both`() {
        val from = base.copy(toneCurve = base.toneCurve.copy(rgb = listOf(CurvePoint(0, 0), CurvePoint(255, 255))))
        val to = base.copy(
            toneCurve = base.toneCurve.copy(rgb = listOf(CurvePoint(0, 20), CurvePoint(128, 160), CurvePoint(255, 241))),
        )

        val halfway = interpolateRecipe(from, to, 0.5f).toneCurve.rgb

        assertEquals(listOf(0, 128, 255), halfway.map { it.x })
        assertEquals(10, halfway[0].y)
        assertEquals(144, halfway[1].y)
        assertEquals(248, halfway[2].y)
        assertEquals(to, interpolateRecipe(from, to, 1f))
    }

    @Test
    fun `curves with as many points move point by point`() {
        val from = base.copy(toneCurve = base.toneCurve.copy(red = listOf(CurvePoint(0, 0), CurvePoint(255, 255))))
        val to = base.copy(toneCurve = base.toneCurve.copy(red = listOf(CurvePoint(0, 40), CurvePoint(200, 255))))

        assertEquals(
            listOf(CurvePoint(0, 20), CurvePoint(228, 255)),
            interpolateRecipe(from, to, 0.5f).toneCurve.red,
        )
        assertEquals(listOf(ReelControl.Curve(CurveChannel.RED)), changedControls(from, to))
    }

    @Test
    fun `a quarter turn and a flip happen halfway, while the crop slides`() {
        val to = base.copy(
            geometry = base.geometry.copy(rotation = 90, mirrorH = true, cropRect = CropRect(0.2f, 0f, 0.6f, 1f)),
        )

        val early = interpolateRecipe(base, to, 0.49f).geometry
        val late = interpolateRecipe(base, to, 0.5f).geometry

        assertEquals(0, early.rotation)
        assertEquals(false, early.mirrorH)
        assertEquals(90, late.rotation)
        assertEquals(true, late.mirrorH)
        assertEquals(0.1f, late.cropRect.x, 0.0001f)
        assertEquals(
            listOf(ReelControl.Rotation, ReelControl.FlipHorizontal, ReelControl.Crop),
            changedControls(base, to),
        )
    }

    @Test
    fun `a grading wheel leaves the centre already pointing at its final hue`() {
        val to = base.copy(
            colorGrading = base.colorGrading.copy(shadows = ColorWheel(hue = 220f, saturation = 40f)),
        )

        val quarter = interpolateRecipe(base, to, 0.25f).colorGrading.shadows

        assertEquals(220f, quarter.hue)
        assertEquals(10f, quarter.saturation, 0.0001f)
    }

    @Test
    fun `a grading wheel turns the short way round`() {
        val from = base.copy(colorGrading = base.colorGrading.copy(global = ColorWheel(hue = 350f, saturation = 20f)))
        val to = base.copy(colorGrading = base.colorGrading.copy(global = ColorWheel(hue = 30f, saturation = 20f)))

        assertEquals(10f, interpolateRecipe(from, to, 0.5f).colorGrading.global.hue, 0.0001f)
    }

    @Test
    fun `a mask added in the session appears and raises its sliders`() {
        val withMask = base.copy(masks = edited.masks)
        val script = EditReelScript.fromHistory(listOf(entry("original", base), entry("1", withMask)), 1)

        val step = script.steps.single()
        assertEquals(
            listOf(ReelControl.MaskArea("sky"), ReelControl.MaskSlider("sky", Adjustment.EXPOSURE)),
            step.controls,
        )
        val halfway = stepAt(script, 0, script.stepMoveMs / 2)
        assertEquals(-0.25f, halfway.recipe.masks.single().adjustments.exposure, 0.0001f)
    }

    @Test
    fun `the comparison shows the photo as it came in, framing included`() {
        val script = EditReelScript.fromRecipe(edited)

        assertEquals(script.before, script.compareBefore)
        assertNotEquals(edited.geometry, script.compareBefore.geometry)
        assertEquals(0f, script.compareBefore.tone.exposure)
        assertTrue(script.compareBefore.masks.all { it.adjustments.isNeutral })
    }

    @Test
    fun `the comparison's line crosses the whole photo, rests on the original and comes back`() {
        assertEquals(0f, compareReveal(0f), 0.0001f)
        assertEquals(1f, compareReveal(REEL_COMPARE_SWEEP_SHARE), 0.0001f)
        assertEquals(1f, compareReveal(0.5f), 0.0001f)
        assertEquals(1f, compareReveal(1f - REEL_COMPARE_SWEEP_SHARE), 0.0001f)
        assertEquals(0f, compareReveal(1f), 0.0001f)
        assertTrue(compareReveal(0.1f) < compareReveal(0.2f))
        assertTrue(compareReveal(0.8f) > compareReveal(0.9f))
    }

    @Test
    fun `a few steps take their time and many share the fifteen seconds`() {
        assertEquals(REEL_STEP_MAX_MS, reelStepMs(2))
        assertEquals(REEL_STEP_MAX_MS, reelStepMs(5))
        assertEquals(2_500L, reelStepMs(6))
        assertEquals(1_500L, reelStepMs(10))
        assertEquals(REEL_STEP_MIN_MS, reelStepMs(REEL_MAX_STEPS))
        val script = EditReelScript.fromRecipe(edited)
        assertEquals(REEL_BEFORE_MS + 5 * REEL_STEP_MAX_MS + REEL_COMPARE_MS + REEL_CLOSING_MS, script.durationMs)
    }

    @Test
    fun `easing starts and ends still`() {
        assertEquals(0f, easeInOut(0f))
        assertEquals(0.5f, easeInOut(0.5f), 0.0001f)
        assertEquals(1f, easeInOut(1f))
        assertTrue(easeInOut(0.1f) < 0.1f)
        assertTrue(easeInOut(0.9f) > 0.9f)
    }
}
