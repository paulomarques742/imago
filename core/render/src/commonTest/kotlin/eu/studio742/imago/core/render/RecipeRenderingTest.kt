package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.CURRENT_PROCESS_VERSION
import eu.studio742.imago.core.model.ColorGrading
import eu.studio742.imago.core.model.ColorWheel
import eu.studio742.imago.core.model.CropRect
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.Geometry
import eu.studio742.imago.core.model.Perspective
import eu.studio742.imago.core.model.Tone

private fun recipe(
    processVersion: Int = CURRENT_PROCESS_VERSION,
    geometry: Geometry = Geometry(),
    tone: Tone = Tone(),
    colorGrading: ColorGrading = ColorGrading(),
) = EditRecipe(
    assetId = "a1",
    originalChecksum = "c1",
    processVersion = processVersion,
    createdAt = "2026-01-01T10:00:00Z",
    updatedAt = "2026-01-01T10:00:00Z",
    geometry = geometry,
    tone = tone,
    colorGrading = colorGrading,
)

class RecipeRenderingTest {
    /** A recipe from before version 10 cannot carry a perspective; if one arrives, it stays inert. */
    @Test
    fun perspectiveOnlyCountsFromProcessTen() {
        val geometry = Geometry(perspective = Perspective(vertical = -30f, constrainCrop = false))

        assertEquals(PerspectiveParameters(), recipe(processVersion = 9, geometry = geometry).toRenderParameters().perspective)
        assertEquals(Perspective(), recipe(processVersion = 9, geometry = geometry).activeGeometry().perspective)
        assertEquals(-30f, recipe(processVersion = 10, geometry = geometry).toRenderParameters().perspective.vertical)
        assertTrue(recipe(processVersion = 10, geometry = geometry).changesTheImage())
    }

    /** An Upright mode that is off finds nothing, whatever angles a recipe carries. */
    @Test
    fun anUprightThatIsOffFindsNothing() {
        val leftover = Perspective(upright = eu.studio742.imago.core.model.UPRIGHT_OFF, uprightPitch = 12f, uprightYaw = -3f)
        val guided = leftover.copy(upright = eu.studio742.imago.core.model.UPRIGHT_GUIDED)

        assertEquals(PerspectiveParameters(), recipe(geometry = Geometry(perspective = leftover)).toRenderParameters().perspective)
        assertEquals(12f, recipe(geometry = Geometry(perspective = guided)).toRenderParameters().perspective.uprightPitch)
    }

    /** The perspective is geometry: a photo with only that does not go through the colour pipeline. */
    @Test
    fun perspectiveAloneIsNotAColourEdit() {
        val parameters = recipe(geometry = Geometry(perspective = Perspective(horizontal = 20f))).toRenderParameters()

        assertTrue(parameters.isColorNeutral)
        assertFalse(parameters.isNeutral)
    }

    /**
     * Step 12's gate. The field existed in the schema from the start and no earlier recipe could have it
     * off neutral — but it is the gate that makes that promise verifiable.
     */
    @Test
    fun colorGradingOnlyCountsFromProcessNine() {
        val grading = ColorGrading(shadows = ColorWheel(hue = 220f, saturation = 60f))
        assertTrue(recipe(colorGrading = grading).changesTheImage())
        assertFalse(recipe(processVersion = 8, colorGrading = grading).changesTheImage())
    }

    /** Blending and balance with no colour chosen are not an edit. */
    @Test
    fun blendingAndBalanceAloneAreNotAnEdit() {
        val grading = ColorGrading(blending = 80f, balance = -40f)
        assertFalse(recipe(colorGrading = grading).changesTheImage())
    }

    /** The fourth wheel tints regardless of tone, and counts as an edit like the other three. */
    @Test
    fun theGlobalWheelCountsAsAnEdit() {
        val grading = ColorGrading(global = ColorWheel(hue = 40f, saturation = 25f))
        assertTrue(recipe(colorGrading = grading).changesTheImage())
    }

    @Test
    fun neutralRecipeChangesNothing() {
        assertFalse(recipe().changesTheImage())
        assertTrue(recipe(tone = Tone(exposure = 0.4f)).changesTheImage())
    }

    /** A crop is an edit even without any colour change: the photo stops being the same. */
    @Test
    fun cropAloneCountsAsAnEdit() {
        val cropped = recipe(geometry = Geometry(cropRect = CropRect(x = 0.1f, y = 0f, w = 0.8f, h = 1f)))
        assertTrue(cropped.changesTheImage())
    }

    @Test
    fun cropNarrowsTheAspectRatio() {
        val cropped = recipe(geometry = Geometry(cropRect = CropRect(w = 0.5f, h = 1f)))
        assertEquals(2000f / 3000f, cropped.editedAspectRatio(4000L, 3000L)!!, 0.0001f)
    }

    @Test
    fun quarterTurnSwapsTheSides() {
        val rotated = recipe(geometry = Geometry(rotation = 90))
        assertEquals(3000f / 4000f, rotated.editedAspectRatio(4000L, 3000L)!!, 0.0001f)
        val halfTurn = recipe(geometry = Geometry(rotation = 180))
        assertEquals(4000f / 3000f, halfTurn.editedAspectRatio(4000L, 3000L)!!, 0.0001f)
    }

    @Test
    fun unknownDimensionsHaveNoAspectRatio() {
        assertNull(recipe().editedAspectRatio(0L, 3000L))
    }

    /**
     * A recipe saved before crop existed cannot gain a crop now: the tile would take another shape
     * without anyone having edited anything.
     */
    @Test
    fun geometryOlderThanTheProcessStaysInert() {
        val legacy = recipe(
            processVersion = 5,
            geometry = Geometry(cropRect = CropRect(w = 0.5f, h = 0.5f), rotation = 90, straighten = 10f),
        )
        assertEquals(CropRect(), legacy.activeGeometry().cropRect)
        assertEquals(0f, legacy.activeGeometry().straighten, 0f)
        assertEquals(90, legacy.activeGeometry().rotation)
        assertEquals(1f, legacy.toRenderParameters().cropWidth, 0f)
        // Rotation already existed in version 4, so it still counts — but without the crop.
        assertEquals(3000f / 4000f, legacy.editedAspectRatio(4000L, 3000L)!!, 0.0001f)
    }
}
