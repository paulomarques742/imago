package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.asUiText
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReelTextsTest {
    private lateinit var previousLocale: Locale

    @Before
    fun english() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restore() = Locale.setDefault(previousLocale)

    private val base = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-10-20T12:00:00Z",
        updatedAt = "2026-10-20T12:00:00Z",
    )

    private fun entry(id: String, recipe: EditRecipe, label: String) = HistoryEntry(
        id = id,
        label = label.asUiText(),
        detail = "Manual".asUiText(),
        valueText = null,
        icon = HistoryIcon.EXPOSURE,
        timestamp = "2026-10-20T12:00:00Z",
        recipe = recipe,
    )

    @Test
    fun `a history step named after its own slider takes its panel's name`() = runBlocking {
        val brighter = base.copy(tone = base.tone.copy(exposure = 0.5f))
        val script = EditReelScript.fromHistory(listOf(entry("0", base, "Original"), entry("1", brighter, "Exposure")), 1)

        val texts = reelTexts(script, null)

        assertEquals("Light", texts.title(script.steps.single().title))
    }

    @Test
    fun `a history step with a name of its own keeps it`() = runBlocking {
        val graded = base.copy(tone = base.tone.copy(exposure = 0.5f, contrast = 20f))
        val script = EditReelScript.fromHistory(listOf(entry("0", base, "Original"), entry("1", graded, "Golden Hour")), 1)

        assertEquals("Golden Hour", reelTexts(script, null).title(script.steps.single().title))
    }

    @Test
    fun `a value leaving zero never reads minus zero`() {
        assertEquals("+0", ReelControl.Slider(Adjustment.HIGHLIGHTS).formatValue(-0.3f))
        assertEquals("-1", ReelControl.Slider(Adjustment.HIGHLIGHTS).formatValue(-0.6f))
        assertEquals("+0.00 EV", ReelControl.Slider(Adjustment.EXPOSURE).formatValue(-0.001f))
    }

    @Test
    fun `in the list a mask's slider says which mask it belongs to`() = runBlocking {
        val masked = base.copy(masks = listOf(LocalMask(id = "m1", name = "Sky", adjustments = LocalAdjustments(exposure = -0.3f))))
        val script = EditReelScript.fromRecipe(masked)

        val texts = reelTexts(script, null)

        assertEquals("Sky · Exposure", texts.fullLabel(ReelControl.MaskSlider("m1", Adjustment.EXPOSURE)))
        assertEquals("Exposure", texts.label(ReelControl.MaskSlider("m1", Adjustment.EXPOSURE)))
    }
}
