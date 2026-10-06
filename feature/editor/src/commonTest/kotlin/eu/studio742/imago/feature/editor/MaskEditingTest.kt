package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.render.toRenderParameters

class MaskEditingTest {
    private val neutral = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-08-25T12:00:00Z",
        updatedAt = "2026-08-25T12:00:00Z",
    )

    private fun withRadial(): Pair<EditRecipe, String> {
        val (recipe, id) = neutral.withNewMask(MaskShape.RADIAL, "Radial 1")
        return recipe to checkNotNull(id)
    }

    /**
     * The contract that replaces the exhaustiveness the compiler does not give: the thirteen [isLocal]
     * promises are exactly the thirteen `MaskEditing`'s `when`s can handle. Without this, a new
     * adjustment fell into the throwing branch and was only found in production.
     */
    @Test
    fun everyLocalAdjustmentRoundTripsAndNoOtherDoes() {
        val (recipe, id) = withRadial()
        Adjustment.entries.forEach { adjustment ->
            val value = adjustment.range.endInclusive / 2f
            if (adjustment.isLocal()) {
                val changed = recipe.withLocalAdjustment(id, adjustment, value)
                assertEquals(
                    "\"${adjustment.name}\" says it is local but does not survive the round trip",
                    value,
                    changed.localAdjustmentValue(id, adjustment),
                )
            } else {
                val failure = runCatching { recipe.withLocalAdjustment(id, adjustment, value) }
                assertTrue(
                    "\"${adjustment.name}\" is not local, and was accepted anyway",
                    failure.isFailure,
                )
            }
        }
    }

    /** A local adjustment cannot slip into the global recipe, nor the other way round. */
    @Test
    fun aLocalAdjustmentLeavesTheGlobalRecipeAlone() {
        val (recipe, id) = withRadial()
        val changed = recipe.withLocalAdjustment(id, Adjustment.EXPOSURE, 2f)

        assertEquals(2f, changed.localAdjustmentValue(id, Adjustment.EXPOSURE))
        assertEquals(0f, changed.adjustmentValue(Adjustment.EXPOSURE))
    }

    @Test
    fun theMaskLimitIsEnforced() {
        var recipe = neutral
        repeat(MAX_LOCAL_MASKS) {
            val (next, id) = recipe.withNewMask(MaskShape.RADIAL, "Radial")
            assertNotNull(id)
            recipe = next
        }
        val (unchanged, refused) = recipe.withNewMask(MaskShape.RADIAL, "Radial")

        assertNull(refused)
        assertEquals(MAX_LOCAL_MASKS, unchanged.masks.size)
    }

    @Test
    fun anUnknownMaskIdChangesNothing() {
        val (recipe, _) = withRadial()

        assertEquals(recipe, recipe.withLocalAdjustment("other", Adjustment.EXPOSURE, 1f))
        assertEquals(recipe, recipe.withoutMask("other"))
        assertEquals(recipe, recipe.withMaskMoved("other", 0.1f, 0.1f))
    }

    /** The names count by shape, so that deleting a linear does not rename the radials. */
    @Test
    fun defaultNamesCountPerShape() {
        val (first, _) = neutral.withNewMask(MaskShape.RADIAL, defaultMaskName(MaskShape.RADIAL, neutral.masks))
        val (second, _) = first.withNewMask(MaskShape.LINEAR, defaultMaskName(MaskShape.LINEAR, first.masks))

        assertEquals("Radial 1", first.masks[0].name)
        assertEquals("Linear 1", second.masks[1].name)
        assertEquals("Radial 2", defaultMaskName(MaskShape.RADIAL, second.masks))
    }

    /** A centre can leave the frame — that is how only one end of the gradient is put in view. */
    @Test
    fun aCentreMayLeaveTheFrameButNotWander() {
        val (recipe, id) = neutral.withNewMask(MaskShape.LINEAR, "Linear 1")
        val moved = recipe.withMaskMoved(checkNotNull(id), 10f, -10f)
        val centre = checkNotNull(moved.maskCentre(id))

        assertEquals(MAX_MASK_CENTRE, centre.first)
        assertEquals(MIN_MASK_CENTRE, centre.second)
    }

    @Test
    fun radiiAndWidthAreClampedAndRotationIsNormalised() {
        val (recipe, id) = withRadial()

        val huge = recipe.withMaskRadius(id, radiusX = 99f, radiusY = 0f)
        assertEquals(MAX_MASK_RADIUS to MIN_MASK_RADIUS, huge.maskRadii(id))

        val turned = recipe.withMaskRotation(id, -90f)
        assertEquals(270f, turned.maskRotation(id))

        val (linear, linearId) = neutral.withNewMask(MaskShape.LINEAR, "Linear 1")
        val thin = linear.withMaskWidth(checkNotNull(linearId), 0f)
        assertEquals(MIN_MASK_WIDTH, thin.maskWidth(linearId))
    }

    /** Width only applies to linears and radius only to radials; swapping them breaks nothing. */
    @Test
    fun aShapeIgnoresTheOtherShapesHandles() {
        val (recipe, id) = withRadial()

        assertEquals(recipe, recipe.withMaskWidth(id, 0.9f))
        assertNull(recipe.maskWidth(id))
        assertEquals(MaskShape.RADIAL, recipe.maskShape(id))
    }

    /**
     * A mask without adjustments does not reach the render, and so its index in the recipe is not the
     * channel it takes in the weight field. It is this mismatch that the `id` in the spec solves.
     */
    @Test
    fun onlyMasksWithWorkReachTheRenderer() {
        val (first, firstId) = neutral.withNewMask(MaskShape.RADIAL, "Radial 1")
        val (both, secondId) = first.withNewMask(MaskShape.LINEAR, "Linear 1")
        val recipe = both.withLocalAdjustment(checkNotNull(secondId), Adjustment.EXPOSURE, 1f)

        val specs = recipe.toRenderParameters().masks

        assertEquals(1, specs.size)
        assertEquals(secondId, specs.first().id)
        assertEquals(2, recipe.masks.size)
        assertNotNull(firstId)
    }

    /** Turning a mask off takes it out of the render without deleting it from the recipe. */
    @Test
    fun aDisabledMaskIsDroppedBeforeRendering() {
        val (recipe, id) = withRadial()
        val working = recipe.withLocalAdjustment(id, Adjustment.EXPOSURE, 1f)

        assertEquals(1, working.toRenderParameters().masks.size)

        val off = working.withMask(id) { it.copy(enabled = false) }

        assertTrue(off.toRenderParameters().masks.isEmpty())
        assertEquals(1, off.masks.size)
    }

    /** A recipe from before version 8 never had masks, and the gate has to keep them out. */
    @Test
    fun masksAreGatedByTheProcessVersion() {
        val (recipe, id) = withRadial()
        val working = recipe.withLocalAdjustment(id, Adjustment.EXPOSURE, 1f)

        assertEquals(1, working.toRenderParameters().masks.size)
        assertTrue(working.copy(processVersion = 7).toRenderParameters().masks.isEmpty())
    }
}
