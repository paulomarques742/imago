package eu.studio742.imago.feature.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.render.FrameGeometry
import eu.studio742.imago.core.render.PhotoBounds

/**
 * A new radial or linear is centred on, and sized to, the part of the photo on screen. Sized by the
 * whole image, a shape created on a hard crop or under a zoom had its handles off screen.
 */
class MaskPlacementTest {
    private val neutral = EditRecipe(
        assetId = "asset-1",
        originalChecksum = "checksum",
        createdAt = "2026-10-21T12:00:00Z",
        updatedAt = "2026-10-21T12:00:00Z",
    )

    /** A 3:2 photo cropped to a fifth of each side, in its lower right corner. */
    private val cornerCrop = FrameGeometry(
        sourceWidth = 3000,
        sourceHeight = 2000,
        cropX = 0.7f,
        cropY = 0.7f,
        cropWidth = 0.2f,
        cropHeight = 0.2f,
    )

    private val screen = Rect(0f, 0f, 1500f, 1000f)

    @Test
    fun theWholeImagePlacementKeepsTheShapesAsTheyWere() {
        val (recipe, id) = neutral.withNewMask(MaskShape.RADIAL, "Radial 1")
        val radial = checkNotNull(recipe.mask(checkNotNull(id))).components.single().radial!!
        assertEquals(0.5f, radial.x, 0f)
        assertEquals(0.5f, radial.y, 0f)
        assertEquals(0.3f, radial.radiusX, 1e-6f)
        assertEquals(0.3f, radial.radiusY, 1e-6f)
    }

    @Test
    fun aShapeIsScaledToThePlacementSpan() {
        val placement = MaskPlacement(0.8f, 0.75f, 0.2f)
        val (radialRecipe, radialId) = neutral.withNewMask(MaskShape.RADIAL, "Radial 1", placement)
        val radial = radialRecipe.mask(checkNotNull(radialId))!!.components.single().radial!!
        assertEquals(0.8f, radial.x, 1e-6f)
        assertEquals(0.75f, radial.y, 1e-6f)
        assertEquals(0.06f, radial.radiusX, 1e-6f)

        val (linearRecipe, linearId) = neutral.withNewMask(MaskShape.LINEAR, "Linear 1", placement)
        val linear = linearRecipe.mask(checkNotNull(linearId))!!.components.single().linear!!
        assertEquals(0.8f, linear.x, 1e-6f)
        assertEquals(0.1f, linear.width, 1e-6f)
    }

    @Test
    fun anAddedComponentFollowsThePlacementToo() {
        val (recipe, id) = neutral.withNewMask(MaskShape.RADIAL, "Radial 1")
        val (combined, index) = recipe.withNewMaskComponent(
            checkNotNull(id),
            MaskShape.RADIAL,
            MaskOp.SUBTRACT,
            MaskPlacement(0.2f, 0.3f, 0.5f),
        )
        val added = combined.mask(id)!!.components[checkNotNull(index)].radial!!
        assertEquals(0.2f, added.x, 1e-6f)
        assertEquals(0.15f, added.radiusX, 1e-6f)
    }

    /** A projection can give NaN beyond the perspective's horizon; such a shape could never be grabbed. */
    @Test
    fun anUnusablePlacementFallsBackToTheWholeImage() {
        val (recipe, id) = neutral.withNewMask(MaskShape.RADIAL, "Radial 1", MaskPlacement(Float.NaN, 0.5f, Float.NaN))
        val radial = recipe.mask(checkNotNull(id))!!.components.single().radial!!
        assertEquals(0.5f, radial.x, 0f)
        assertEquals(0.3f, radial.radiusX, 1e-6f)
    }

    @Test
    fun onAHardCropTheShapeIsCentredOnTheCropAndSizedToIt() {
        val projection = MaskProjection(PhotoBounds(0f, 0f, 1500f, 1000f), cornerCrop)
        val placement = projection.newShapePlacement(screen)
        assertEquals(0.8f, placement.x, 1e-4f)
        assertEquals(0.8f, placement.y, 1e-4f)
        // The crop's shorter side is its height: a fifth of the image's.
        assertEquals(0.2f, placement.span, 1e-4f)
        assertHandlesOnScreen(MaskShape.RADIAL, placement, projection, screen)
        assertHandlesOnScreen(MaskShape.LINEAR, placement, projection, screen)
    }

    @Test
    fun underAZoomOnlyWhatIsSeenCounts() {
        // Zoomed 4x on the uncropped photo, with its upper left quarter on screen.
        val projection = MaskProjection(PhotoBounds(0f, 0f, 6000f, 4000f), FrameGeometry(3000, 2000))
        val placement = projection.newShapePlacement(screen)
        assertEquals(0.125f, placement.x, 1e-4f)
        assertEquals(0.125f, placement.y, 1e-4f)
        assertEquals(0.25f, placement.span, 1e-4f)
        assertHandlesOnScreen(MaskShape.RADIAL, placement, projection, screen)
        assertHandlesOnScreen(MaskShape.LINEAR, placement, projection, screen)
    }

    /** The drawer and the top bar cover part of the stage; the shape goes in the middle of the rest. */
    @Test
    fun whatTheChromeCoversIsLeftOut() {
        val projection = MaskProjection(PhotoBounds(0f, 0f, 1500f, 1000f), FrameGeometry(3000, 2000))
        val uncovered = Rect(0f, 100f, 1500f, 500f)
        val placement = projection.newShapePlacement(uncovered)
        assertEquals(0.5f, placement.x, 1e-4f)
        assertEquals(0.3f, placement.y, 1e-4f)
        assertEquals(0.4f, placement.span, 1e-4f)
        assertHandlesOnScreen(MaskShape.RADIAL, placement, projection, uncovered)
    }

    private fun assertHandlesOnScreen(shape: MaskShape, placement: MaskPlacement, projection: MaskProjection, seen: Rect) {
        val component = newMaskComponent(shape, placement = placement)
        val handles = checkNotNull(maskHandlesOf(LocalMask("m", components = listOf(component)), projection, 0, ROTATE_GAP_PX))
        listOfNotNull(handles.centre, handles.radiusX, handles.radiusY, handles.width, handles.rotate).forEach { handle ->
            assertTrue("$shape handle $handle is outside $seen", seen.contains(handle) || onEdge(seen, handle))
        }
    }

    private fun onEdge(rect: Rect, point: Offset) =
        point.x in rect.left - 0.5f..rect.right + 0.5f && point.y in rect.top - 0.5f..rect.bottom + 0.5f

    private companion object {
        /** [MASK_ROTATE_HANDLE_GAP] at a density of 2.75, a phone's. */
        const val ROTATE_GAP_PX = 110f
    }
}
