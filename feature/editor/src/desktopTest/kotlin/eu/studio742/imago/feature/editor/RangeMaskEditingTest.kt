package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import eu.studio742.imago.core.model.ColorRange
import eu.studio742.imago.core.model.ColorSample
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.LuminanceRange
import eu.studio742.imago.core.model.MAX_COLOR_SAMPLES
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.RadialMask

/** The range masks' mutations, without a view model: what a tap, a drag or a slider writes. */
class RangeMaskEditingTest {
    private fun recipe(vararg components: MaskComponent) = EditRecipe(
        assetId = "a",
        originalChecksum = "",
        createdAt = "",
        updatedAt = "",
        masks = listOf(LocalMask(id = "m", components = components.toList())),
    )

    private val color = MaskComponent(shape = MaskShape.COLOR, color = ColorRange())
    private val luminance = MaskComponent(shape = MaskShape.LUMINANCE, luminance = LuminanceRange())
    private val radial = MaskComponent(shape = MaskShape.RADIAL, radial = RadialMask())

    private fun sample(x: Float) = ColorSample(x = x, y = 0.5f, l = 0.6f, a = 0.01f, b = -0.1f)

    private fun EditRecipe.samples(component: Int = 0) = masks.single().components[component].color!!.samples

    @Test
    fun aPlainTapReplacesTheSamplesAndAnAddedOneJoinsThem() {
        val first = recipe(color).withColorSample("m", 0, sample(0.1f), add = false)
        val second = first.withColorSample("m", 0, sample(0.2f), add = true)
        val replaced = second.withColorSample("m", 0, sample(0.3f), add = false)

        assertEquals(listOf(0.1f), first.samples().map { it.x })
        assertEquals(listOf(0.1f, 0.2f), second.samples().map { it.x })
        assertEquals(listOf(0.3f), replaced.samples().map { it.x })
    }

    /** Lightroom stops at five; past it the oldest gives way, so the newest tap is never ignored. */
    @Test
    fun theSamplesStopAtFive() {
        var current = recipe(color)
        for (i in 1..MAX_COLOR_SAMPLES + 2) current = current.withColorSample("m", 0, sample(i / 10f), add = true)

        assertEquals(MAX_COLOR_SAMPLES, current.samples().size)
        assertEquals(0.3f, current.samples().first().x, 1e-6f)
        assertEquals(0.7f, current.samples().last().x, 1e-6f)
    }

    @Test
    fun aSampleCanBeRemoved() {
        val two = recipe(color).withColorSample("m", 0, sample(0.1f), add = true).withColorSample("m", 0, sample(0.2f), add = true)

        assertEquals(listOf(0.2f), two.withoutColorSample("m", 0, 0).samples().map { it.x })
        assertSame(two, two.withoutColorSample("m", 0, 5))
    }

    @Test
    fun theLuminanceRangeIsOrderedRoundedAndClamped() {
        val updated = recipe(luminance).withLuminanceRange("m", 0, 72.345f, -3f)
        val range = updated.masks.single().components.single().luminance!!

        assertEquals(0f, range.low, 0f)
        assertEquals(72.3f, range.high, 1e-4f)
    }

    @Test
    fun eachSettingOnlyTouchesItsRange() {
        val both = recipe(color, luminance)

        val refined = both.withRangeSetting("m", 0, RangeSetting.REFINE, 31.6f)
        assertEquals(32f, refined.rangeSettingValue("m", 0, RangeSetting.REFINE), 0f)
        assertSame(both, both.withRangeSetting("m", 0, RangeSetting.SMOOTHNESS, 10f))

        val smoothed = both.withRangeSetting("m", 1, RangeSetting.SMOOTHNESS, 140f)
        assertEquals(100f, smoothed.rangeSettingValue("m", 1, RangeSetting.SMOOTHNESS), 0f)
    }

    /** A tap that arrives as the selection moves to a radial writes nothing into it. */
    @Test
    fun aRangeEditOnAnotherShapeChangesNothing() {
        val shapes = recipe(radial)

        assertSame(shapes, shapes.withColorSample("m", 0, sample(0.1f), add = false))
        assertSame(shapes, shapes.withLuminanceRange("m", 0, 10f, 20f))
        assertSame(shapes, shapes.withColorSample("other", 0, sample(0.1f), add = false))
        assertEquals(RangeSetting.REFINE.neutral, shapes.rangeSettingValue("m", 0, RangeSetting.REFINE), 0f)
    }
}
