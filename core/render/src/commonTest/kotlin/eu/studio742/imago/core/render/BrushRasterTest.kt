package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.BrushMask
import eu.studio742.imago.core.model.BrushStroke
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.RadialMask

class BrushRasterTest {
    private val aspect = 1.5f

    private fun stroke(
        vararg points: Float,
        radius: Float = 0.05f,
        feather: Float = 0f,
        flow: Float = 100f,
        density: Float = 100f,
        erase: Boolean = false,
        autoMask: Boolean = false,
    ) = BrushStroke(points.toList(), radius, feather, flow, density, erase, autoMask)

    private fun parameters(vararg strokes: BrushStroke, op: MaskOp = MaskOp.ADD, withRadial: Boolean = false): RenderParameters {
        val brush = MaskComponent(shape = MaskShape.BRUSH, op = op, brush = BrushMask(strokes.toList()))
        val components = if (withRadial) {
            listOf(MaskComponent(shape = MaskShape.RADIAL, radial = RadialMask(radiusX = 0.4f, radiusY = 0.4f, feather = 0f)), brush)
        } else {
            listOf(brush)
        }
        return EditRecipe(
            assetId = "a",
            originalChecksum = "",
            createdAt = "",
            updatedAt = "",
            masks = listOf(LocalMask(id = "m", components = components, adjustments = LocalAdjustments(exposure = 1f))),
        ).toRenderParameters()
    }

    private fun rasterOf(vararg strokes: BrushStroke): BrushRaster {
        val parameters = parameters(*strokes)
        return checkNotNull(BrushRasterCache().rasters(parameters, aspect, Any()) { error("no reference") }[0])
    }

    @Test
    fun aHardDabIsFullInsideAndEmptyOutside() {
        val raster = rasterOf(stroke(0.5f, 0.5f))

        assertEquals(1f, raster.sample(0.5f, 0.5f), 1e-3f)
        assertEquals(0f, raster.sample(0.5f, 0.5f + 0.06f), 1e-3f)
        assertEquals(0f, raster.sample(0.5f + 0.06f / aspect, 0.5f), 1e-3f)
    }

    /** The radius is in units of image height: the dab is round on the photo, not on the raster's grid. */
    @Test
    fun theDabIsRoundInImageSpace() {
        val raster = rasterOf(stroke(0.5f, 0.5f, radius = 0.1f))

        assertEquals(raster.sample(0.5f, 0.5f + 0.09f), raster.sample(0.5f + 0.09f / aspect, 0.5f), 0.02f)
    }

    @Test
    fun flowBuildsUpAndDensityCaps() {
        val once = rasterOf(stroke(0.5f, 0.5f, flow = 30f)).sample(0.5f, 0.5f)
        val twice = rasterOf(stroke(0.5f, 0.5f, flow = 30f), stroke(0.5f, 0.5f, flow = 30f)).sample(0.5f, 0.5f)
        val capped = rasterOf(*Array(20) { stroke(0.5f, 0.5f, density = 40f) }).sample(0.5f, 0.5f)

        assertEquals(0.3f, once, 1e-3f)
        assertEquals(0.51f, twice, 1e-3f)
        assertEquals(0.4f, capped, 1e-3f)
    }

    /** A weaker stroke over a stronger one leaves it: density is a ceiling, not a target to fall to. */
    @Test
    fun paintingNeverLowersTheWeight() {
        val raster = rasterOf(stroke(0.5f, 0.5f), stroke(0.5f, 0.5f, density = 30f))

        assertEquals(1f, raster.sample(0.5f, 0.5f), 1e-3f)
    }

    @Test
    fun theEraserTakesAway() {
        val raster = rasterOf(stroke(0.2f, 0.5f, 0.8f, 0.5f), stroke(0.5f, 0.2f, 0.5f, 0.8f, erase = true))

        assertEquals(1f, raster.sample(0.3f, 0.5f), 1e-3f)
        assertEquals(0f, raster.sample(0.5f, 0.5f), 1e-3f)
    }

    @Test
    fun theFeatherFallsSmoothly() {
        val raster = rasterOf(stroke(0.5f, 0.5f, radius = 0.1f, feather = 100f))
        val profile = (0..10).map { raster.sample(0.5f, 0.5f + it * 0.0095f) }

        assertEquals(1f, profile.first(), 0.02f)
        assertTrue("The feather has to fall monotonically", profile.zipWithNext().all { (a, b) -> b <= a + 1e-3f })
        assertEquals(0f, profile.last(), 0.02f)
    }

    /**
     * A stroke grown point by point, as the finger draws it, paints the same raster as the finished
     * stroke painted at once — that is what lets the preview paint only the newest dabs.
     */
    @Test
    fun aStrokeDrawnPointByPointEqualsTheFinishedStroke() {
        val path = (0..40).flatMap { listOf(0.2f + it * 0.015f, 0.5f + 0.2f * kotlin.math.sin(it / 6f)) }
        val cache = BrushRasterCache()
        val token = Any()
        var growing: BrushRaster? = null
        for (end in 2..path.size step 2) {
            growing = cache.rasters(parameters(stroke(*path.take(end).toFloatArray(), flow = 40f)), aspect, token) { error("no reference") }[0]
        }
        val whole = rasterOf(stroke(*path.toFloatArray(), flow = 40f))

        assertTrue(checkNotNull(growing).data.contentEquals(whole.data))
    }

    /** An undo takes a stroke away; the cache cannot extend from there and paints everything again. */
    @Test
    fun anUndoRepaintsFromNothing() {
        val cache = BrushRasterCache()
        val token = Any()
        val first = stroke(0.3f, 0.5f)
        cache.rasters(parameters(first, stroke(0.7f, 0.5f)), aspect, token) { error("no reference") }
        val undone = checkNotNull(cache.rasters(parameters(first), aspect, token) { error("no reference") }[0])

        assertEquals(1f, undone.sample(0.3f, 0.5f), 1e-3f)
        assertEquals(0f, undone.sample(0.7f, 0.5f), 1e-3f)
    }

    /** The Auto Mask stops at an edge of colour: a stroke over a black and white border stays on its side. */
    @Test
    fun theAutoMaskStopsAtAColourEdge() {
        val source = PixelBuffer(300, 200)
        for (y in 0 until 200) for (x in 0 until 300) source.pixels[y * 300 + x] = if (x < 150) 0xFF202020.toInt() else 0xFFE0E0E0.toInt()
        // A radius of 0.3 of the height reaches 0.2 of the width each side: well past the border at 0.5.
        val parameters = parameters(stroke(0.45f, 0.3f, 0.45f, 0.7f, radius = 0.3f, autoMask = true))
        val raster = checkNotNull(BrushRasters.of(parameters, source)[0])

        assertEquals(1f, raster.sample(0.45f, 0.5f), 1e-3f)
        assertEquals(0f, raster.sample(0.58f, 0.5f), 1e-3f)
        val plain = checkNotNull(BrushRasters.of(this.parameters(stroke(0.45f, 0.3f, 0.45f, 0.7f, radius = 0.3f)), source)[0])
        assertEquals(1f, plain.sample(0.58f, 0.5f), 1e-3f)
    }

    /** A brush is a component like the others: subtracted from a radial, it takes its area away. */
    @Test
    fun aSubtractedBrushCutsTheRadial() {
        val parameters = parameters(stroke(0.5f, 0.5f), op = MaskOp.SUBTRACT, withRadial = true)
        val brushes = BrushRasterCache().rasters(parameters, aspect, Any()) { error("no reference") }
        val mask = parameters.masks.single()

        assertEquals(0f, LocalMaskField.weightOf(mask, 0.5f, 0.5f, aspect, brushes), 1e-3f)
        assertEquals(1f, LocalMaskField.weightOf(mask, 0.5f, 0.68f, aspect, brushes), 1e-3f)
    }

    /** An empty brush still counts: intersected, it leaves nothing — as an empty selection would. */
    @Test
    fun anEmptyBrushWeighsZeroAndTakesNoLayer() {
        val parameters = parameters(op = MaskOp.INTERSECT, withRadial = true)
        val mask = parameters.masks.single()

        assertEquals(-1, mask.components.last().brushIndex)
        assertTrue(!parameters.hasBrushes)
        assertEquals(0f, LocalMaskField.weightOf(mask, 0.5f, 0.5f, aspect), 1e-6f)
    }

    /** A brush in a recipe from before process 16 is not drawn. */
    @Test
    fun brushesAreGatedByTheProcessVersion() {
        val brush = MaskComponent(shape = MaskShape.BRUSH, brush = BrushMask(listOf(stroke(0.5f, 0.5f))))
        val recipe = EditRecipe(
            assetId = "a",
            originalChecksum = "",
            createdAt = "",
            updatedAt = "",
            masks = listOf(LocalMask(id = "m", components = listOf(brush), adjustments = LocalAdjustments(exposure = 1f))),
        )

        assertEquals(0, recipe.toRenderParameters().masks.single().components.single().brushIndex)
        assertTrue(recipe.copy(processVersion = 15).toRenderParameters().masks.isEmpty())
    }

    /** With a brush the field is finer, but never past the raster it samples. */
    @Test
    fun theFieldIsFinerWithABrushAndCapped() {
        assertEquals(750 to 500, LocalMaskField.fieldSize(6000, 4000, brushes = false))
        assertEquals(BRUSH_RASTER_SIDE to 1365, LocalMaskField.fieldSize(6000, 4000, brushes = true))
        assertEquals(500 to 333, LocalMaskField.fieldSize(1000, 667, brushes = true))
    }

    /** The open mask reaches the render with no adjustments, so its red can show while it is drawn. */
    @Test
    fun theShownMaskReachesTheRenderWithoutAdjustments() {
        val recipe = EditRecipe(
            assetId = "a",
            originalChecksum = "",
            createdAt = "",
            updatedAt = "",
            masks = listOf(LocalMask(id = "m", components = listOf(MaskComponent(shape = MaskShape.RADIAL, radial = RadialMask())))),
        )

        assertTrue(recipe.toRenderParameters().masks.isEmpty())
        assertEquals("m", recipe.toRenderParameters(showMask = "m").masks.single().id)
    }
}
