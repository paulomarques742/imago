package eu.studio742.imago.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MAX_MASK_COMPONENTS
import eu.studio742.imago.core.model.MaskOp
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

    /** A combined shape goes at the end, with its operation, and reaches the render in that order. */
    @Test
    fun combinedShapesKeepTheirOrderAndOperation() {
        val (recipe, id) = withRadial()
        val (subtracted, first) = recipe.withNewMaskComponent(id, MaskShape.LINEAR, MaskOp.SUBTRACT)
        val (intersected, second) = subtracted.withNewMaskComponent(id, MaskShape.RADIAL, MaskOp.INTERSECT)

        assertEquals(1, first)
        assertEquals(2, second)
        val working = intersected.withLocalAdjustment(id, Adjustment.EXPOSURE, 1f)
        val rendered = working.toRenderParameters().masks.single().components
        assertEquals(listOf(MaskOp.ADD, MaskOp.SUBTRACT, MaskOp.INTERSECT), rendered.map { it.op })
        assertEquals(listOf(MaskShape.RADIAL, MaskShape.LINEAR, MaskShape.RADIAL), rendered.map { it.shape })
    }

    /** The handles of the second shape move the second shape, and leave the first where it was. */
    @Test
    fun geometryEditsTargetTheChosenShape() {
        val (recipe, id) = withRadial()
        val (combined, index) = recipe.withNewMaskComponent(id, MaskShape.RADIAL, MaskOp.SUBTRACT)
        val shape = checkNotNull(index)

        val moved = combined.withMaskMoved(id, 0.1f, 0f, component = shape)
            .withMaskRadius(id, radiusX = 0.5f, component = shape)
            .withMaskRotation(id, 45f, component = shape)

        assertEquals(0.5f to 0.5f, moved.maskCentre(id))
        assertEquals(0.6f, checkNotNull(moved.maskCentre(id, shape)).first, 1e-6f)
        assertEquals(0.5f, checkNotNull(moved.maskRadii(id, shape)).first)
        assertEquals(45f, moved.maskRotation(id, shape))
        assertEquals(0f, moved.maskRotation(id))
    }

    /** Removing the first shape makes the next one the seed, written as Add; removing the last removes the mask. */
    @Test
    fun removingShapesReseedsAndFinallyRemovesTheMask() {
        val (recipe, id) = withRadial()
        val (combined, _) = recipe.withNewMaskComponent(id, MaskShape.LINEAR, MaskOp.SUBTRACT)

        val reseeded = combined.withoutMaskComponent(id, 0)
        val left = checkNotNull(reseeded.mask(id)).components.single()
        assertEquals(MaskShape.LINEAR, left.shape)
        assertEquals(MaskOp.ADD, left.op)

        assertNull(reseeded.withoutMaskComponent(id, 0).mask(id))
    }

    /** The copy goes right after the original, with its shapes and adjustments, under its own id. */
    @Test
    fun duplicatingPutsTheCopyRightAfterTheOriginal() {
        val (first, firstId) = neutral.withNewMask(MaskShape.RADIAL, "Radial 1")
        val (both, _) = first.withNewMask(MaskShape.LINEAR, "Linear 1")
        val working = both.withLocalAdjustment(checkNotNull(firstId), Adjustment.CLARITY, 30f)

        val (duplicated, copyId) = working.withDuplicatedMask(firstId, invert = false)
        assertEquals(listOf(firstId, copyId), duplicated.masks.take(2).map { it.id })
        val copy = duplicated.masks[1]
        assertEquals(working.masks[0].components, copy.components)
        assertEquals(30f, copy.adjustments.clarity)
        assertEquals("Radial 2", copy.name)
        assertFalse(copy.inverted)

        val (inverted, invertedId) = working.withDuplicatedMask(firstId, invert = true)
        assertTrue(checkNotNull(inverted.mask(checkNotNull(invertedId))).inverted)
    }

    @Test
    fun duplicatingRespectsTheMaskLimit() {
        var recipe = neutral
        repeat(MAX_LOCAL_MASKS) { recipe = recipe.withNewMask(MaskShape.RADIAL, "Radial").first }

        val (unchanged, refused) = recipe.withDuplicatedMask(recipe.masks.first().id, invert = false)
        assertNull(refused)
        assertEquals(recipe, unchanged)
    }

    /** The shapes are counted across masks: the field shader's arrays are shared. */
    @Test
    fun theShapeBudgetIsSharedByAllMasks() {
        val (recipe, id) = withRadial()
        var working = recipe
        repeat(MAX_MASK_COMPONENTS - 1) {
            working = working.withNewMaskComponent(id, MaskShape.RADIAL, MaskOp.ADD).first
        }

        assertEquals(MAX_MASK_COMPONENTS, working.maskComponentCount)
        assertNull(working.withNewMaskComponent(id, MaskShape.RADIAL, MaskOp.ADD).second)
        assertNull(working.withNewMask(MaskShape.LINEAR, "Linear 1").second)
    }

    /** The Amount is a mask slider, kept in the mask and clamped to Lightroom's 0 to 200. */
    @Test
    fun theAmountIsAMaskSlider() {
        val (recipe, id) = withRadial()

        assertTrue(Adjustment.MASK_AMOUNT.isMaskSlider())
        assertFalse(Adjustment.MASK_AMOUNT.isLocal())
        assertEquals(100f, recipe.maskSliderValue(id, Adjustment.MASK_AMOUNT))
        assertEquals(200f, recipe.withMaskSlider(id, Adjustment.MASK_AMOUNT, 500f).maskSliderValue(id, Adjustment.MASK_AMOUNT))
        assertEquals(
            1.5f,
            recipe.withMaskSlider(id, Adjustment.EXPOSURE, 1.5f).maskSliderValue(id, Adjustment.EXPOSURE),
        )
    }
}
