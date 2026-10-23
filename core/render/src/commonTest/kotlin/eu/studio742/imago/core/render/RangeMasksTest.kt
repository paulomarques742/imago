package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.ColorRange
import eu.studio742.imago.core.model.EditRecipe
import eu.studio742.imago.core.model.LinearMask
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.LocalMask
import eu.studio742.imago.core.model.LuminanceRange
import eu.studio742.imago.core.model.MaskComponent
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.RANGE_MASKS_PROCESS_VERSION

class RangeMasksTest {
    private val sky = 0xFF5B8FD6.toInt()
    private val grass = 0xFF4C8A3A.toInt()

    /** The top half sky blue, the bottom half grass green: the edge is at y = 0.5. */
    private val photo = PixelBuffer(60, 40).apply {
        for (y in 0 until height) for (x in 0 until width) pixels[y * width + x] = if (y < height / 2) sky else grass
    }

    /** A left-to-right ramp from black to white, the same down every column. */
    private val ramp = PixelBuffer(101, 10).apply {
        for (y in 0 until height) for (x in 0 until width) {
            val v = (x * 255 / (width - 1))
            pixels[y * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    private fun recipe(vararg components: MaskComponent, processVersion: Int = RANGE_MASKS_PROCESS_VERSION) = EditRecipe(
        assetId = "a",
        originalChecksum = "",
        createdAt = "",
        updatedAt = "",
        processVersion = processVersion,
        masks = listOf(LocalMask(id = "m", components = components.toList(), adjustments = LocalAdjustments(exposure = -1f))),
    )

    private fun luminance(range: LuminanceRange, op: MaskOp = MaskOp.ADD) =
        MaskComponent(shape = MaskShape.LUMINANCE, op = op, luminance = range)

    private fun color(range: ColorRange, op: MaskOp = MaskOp.ADD) = MaskComponent(shape = MaskShape.COLOR, op = op, color = range)

    private fun rasterOf(parameters: RenderParameters, source: PixelBuffer): BrushRaster =
        checkNotNull(BrushRasters.of(parameters, source)[0])

    @Test
    fun oklabPutsWhiteAtOneAndGreysOnTheAxis() {
        val out = FloatArray(3)
        Oklab.fromSrgb(1f, 1f, 1f, out)
        assertEquals(1f, out[0], 1e-3f)
        assertEquals(0f, out[1], 1e-3f)
        assertEquals(0f, out[2], 1e-3f)
        Oklab.fromSrgb(0f, 0f, 0f, out)
        assertEquals(0f, out[0], 1e-3f)
        Oklab.fromSrgb(0.5f, 0.5f, 0.5f, out)
        assertEquals(0f, out[1], 1e-3f)
        assertEquals(0f, out[2], 1e-3f)
        assertTrue(out[0] in 0.55f..0.65f)
    }

    /** The panel's swatch shows the colour that was sampled, not an approximation of it. */
    @Test
    fun aSampleComesBackAsTheColourItRead() {
        val sample = RangeMasks.sampleColor(BrushReference.of(photo), 0.5f, 0.2f)

        assertEquals(sky, RangeMasks.sampleArgb(sample))
    }

    @Test
    fun aLuminanceRangeIsFullInsideAndFadesOverTheSmoothness() {
        val range = LuminanceRange(low = 40f, high = 60f, smoothness = 50f)
        val falloff = 0.5f * RangeMasks.MAX_LUMINANCE_FALLOFF

        assertEquals(1f, RangeMasks.luminanceWeight(50f, range), 0f)
        assertEquals(1f, RangeMasks.luminanceWeight(40f, range), 0f)
        assertEquals(0.5f, RangeMasks.luminanceWeight(40f - falloff / 2f, range), 1e-4f)
        assertEquals(0f, RangeMasks.luminanceWeight(40f - falloff, range), 0f)
        assertEquals(0.5f, RangeMasks.luminanceWeight(60f + falloff / 2f, range), 1e-4f)
    }

    @Test
    fun smoothnessZeroIsAHardEdgeAndTheEndsCanArriveSwapped() {
        val hard = LuminanceRange(low = 40f, high = 60f, smoothness = 0f)
        assertEquals(0f, RangeMasks.luminanceWeight(39.9f, hard), 0f)
        assertEquals(1f, RangeMasks.luminanceWeight(40f, hard), 0f)

        val swapped = LuminanceRange(low = 60f, high = 40f, smoothness = 0f)
        assertEquals(1f, RangeMasks.luminanceWeight(50f, swapped), 0f)
    }

    @Test
    fun aColorRangeSelectsTheSampleAndLeavesAFarColour() {
        val reference = BrushReference.of(photo)
        val blue = RangeMasks.sampleColor(reference, 0.5f, 0.2f)
        val range = ColorRange(samples = listOf(blue))
        val green = FloatArray(3).also { Oklab.fromSrgb(0x4C / 255f, 0x8A / 255f, 0x3A / 255f, it) }

        assertEquals(1f, RangeMasks.colorWeight(blue.l, blue.a, blue.b, range), 0f)
        assertEquals(0f, RangeMasks.colorWeight(green[0], green[1], green[2], range), 0f)
        assertEquals(0f, RangeMasks.colorWeight(blue.l, blue.a, blue.b, ColorRange()), 0f)
    }

    @Test
    fun refineWidensTheSelection() {
        val reference = BrushReference.of(photo)
        val blue = RangeMasks.sampleColor(reference, 0.5f, 0.2f)
        // A lighter, greyer blue: the same sky in the haze near the horizon.
        val haze = FloatArray(3).also { Oklab.fromSrgb(0x8F / 255f, 0xAE / 255f, 0xD6 / 255f, it) }
        val narrow = RangeMasks.colorWeight(haze[0], haze[1], haze[2], ColorRange(listOf(blue), refine = 10f))
        val wide = RangeMasks.colorWeight(haze[0], haze[1], haze[2], ColorRange(listOf(blue), refine = 90f))

        assertTrue("narrow $narrow, wide $wide", wide > narrow)
        assertEquals(1f, wide, 0f)
    }

    /** An area sample keeps how varied its colours were, so a dragged rectangle of sky selects its range of blues. */
    @Test
    fun anAreaSampleCarriesItsSpread() {
        val reference = BrushReference.of(photo)
        val point = RangeMasks.sampleColor(reference, 0.5f, 0.2f)
        val acrossTheEdge = RangeMasks.sampleColor(reference, 0.5f, 0.5f, width = 0.2f, height = 0.4f)

        assertEquals(0f, point.spread, 1e-6f)
        assertTrue(acrossTheEdge.spread > 0.05f)
        assertEquals(0.2f, acrossTheEdge.width, 0f)
    }

    @Test
    fun aColorRangeRasterFollowsTheEdgeOfTheOriginal() {
        val sample = RangeMasks.sampleColor(BrushReference.of(photo), 0.3f, 0.25f)
        val raster = rasterOf(recipe(color(ColorRange(listOf(sample)))).toRenderParameters(), photo)

        assertEquals(1f, raster.sample(0.5f, 0.2f), 1e-3f)
        assertEquals(0f, raster.sample(0.5f, 0.8f), 1e-3f)
    }

    @Test
    fun aLuminanceRangeRasterSelectsTheMiddleOfARamp() {
        val raster = rasterOf(recipe(luminance(LuminanceRange(low = 40f, high = 70f, smoothness = 0f))).toRenderParameters(), ramp)
        val lightness = FloatArray(3)

        for (x in listOf(0.05f, 0.3f, 0.5f, 0.6f, 0.95f)) {
            Oklab.fromSrgb(x, x, x, lightness)
            val expected = if (lightness[0] * 100f in 40f..70f) 1f else 0f
            assertEquals("at $x (L ${lightness[0] * 100f})", expected, raster.sample(x, 0.5f), 0.02f)
        }
    }

    @Test
    fun anEyedropperSetsTheRangeAroundAPointOrAcrossAnArea() {
        val reference = BrushReference.of(ramp)
        val point = RangeMasks.sampleLuminance(reference, 0.5f, 0.5f, smoothness = 30f)
        val grey = FloatArray(3).also { Oklab.fromSrgb(0.5f, 0.5f, 0.5f, it) }

        assertEquals(grey[0] * 100f - RangeMasks.LUMINANCE_POINT_HALF_RANGE, point.low, 0.6f)
        assertEquals(grey[0] * 100f + RangeMasks.LUMINANCE_POINT_HALF_RANGE, point.high, 0.6f)
        assertEquals(30f, point.smoothness, 0f)

        val area = RangeMasks.sampleLuminance(reference, 0.5f, 0.5f, width = 1f, height = 1f, smoothness = 50f)
        assertTrue("trimmed, not black: ${area.low}", area.low > 5f)
        assertTrue("trimmed, not white: ${area.high}", area.high < 99f)
    }

    /** The point of the feature: darkening the sky by its colour leaves the grass as it was. */
    @Test
    fun aColorRangeMaskOnlyChangesTheSampledColour() {
        val sample = RangeMasks.sampleColor(BrushReference.of(photo), 0.5f, 0.2f)
        val out = RecipePixels.render(photo, recipe(color(ColorRange(listOf(sample)))))
        val untouched = RecipePixels.render(photo, recipe())

        val skyIndex = 5 * photo.width + 30
        val grassIndex = 35 * photo.width + 30
        assertTrue(brightness(out.pixels[skyIndex]) < brightness(untouched.pixels[skyIndex]) - 20)
        assertEquals(brightness(untouched.pixels[grassIndex]).toFloat(), brightness(out.pixels[grassIndex]).toFloat(), 1f)
    }

    /** Lightroom's most common use of a range: a linear for the sky, intersected with the sky's colour. */
    @Test
    fun aRangeIntersectsAShape() {
        val sample = RangeMasks.sampleColor(BrushReference.of(photo), 0.5f, 0.2f)
        val parameters = recipe(
            MaskComponent(shape = MaskShape.LINEAR, linear = LinearMask(x = 0.5f, y = 0.5f, angle = 90f, width = 0.01f)),
            color(ColorRange(listOf(sample)), op = MaskOp.INTERSECT),
        ).toRenderParameters()
        val mask = parameters.masks.single()
        val rasters = BrushRasters.of(parameters, photo)
        val left = LocalMaskField.weightOf(mask, 0.1f, 0.2f, 1.5f, rasters)
        val right = LocalMaskField.weightOf(mask, 0.9f, 0.2f, 1.5f, rasters)

        assertEquals("one side of the linear keeps the sky", 1f, maxOf(left, right), 1e-3f)
        assertEquals("the other side loses it", 0f, minOf(left, right), 1e-3f)
        assertEquals(0f, LocalMaskField.weightOf(mask, 0.1f, 0.8f, 1.5f, rasters), 1e-3f)
    }

    @Test
    fun rangesAreGatedByTheProcessVersion() {
        val range = luminance(LuminanceRange(low = 0f, high = 100f))

        assertTrue(recipe(range, processVersion = RANGE_MASKS_PROCESS_VERSION - 1).toRenderParameters().masks.isEmpty())
        assertEquals(1, recipe(range).toRenderParameters().masks.size)
    }

    /** To the shader a range is one more raster layer: it travels with the brush's shape code. */
    @Test
    fun rangesTravelToTheShaderAsRasters() {
        val parameters = recipe(luminance(LuminanceRange()), color(ColorRange(listOf(RangeMasks.sampleColor(BrushReference.of(photo), 0.5f, 0.2f))))).toRenderParameters()
        val components = parameters.masks.single().components

        assertEquals(listOf(0, 1), components.map { it.brushIndex })
        assertTrue(components.all { (it.packedKind and 3) == MaskShape.BRUSH.ordinal })
        assertTrue(parameters.hasBrushes)
    }

    /** A Color Range not sampled yet weighs nothing and takes no layer, like a brush not painted yet. */
    @Test
    fun anUnsampledColorRangeTakesNoLayer() {
        val parameters = recipe(color(ColorRange())).toRenderParameters()
        val component = parameters.masks.single().components.single()

        assertEquals(-1, component.brushIndex)
        assertEquals(0f, LocalMaskField.weightOf(parameters.masks.single(), 0.5f, 0.5f, 1.5f, BrushRasters.NONE), 0f)
    }

    @Test
    fun theCacheRepaintsARangeOnlyWhenItChanges() {
        val cache = BrushRasterCache()
        val reference = BrushReference.of(ramp)
        var built = 0
        fun rastersFor(range: LuminanceRange) = cache.rasters(recipe(luminance(range)).toRenderParameters(), ramp.width.toFloat() / ramp.height, ramp) {
            built++
            reference
        }

        val first = checkNotNull(rastersFor(LuminanceRange(low = 0f, high = 50f, smoothness = 0f))[0])
        val before = first.sample(0.75f, 0.5f)
        first.takeDirty()
        rastersFor(LuminanceRange(low = 0f, high = 50f, smoothness = 0f))
        assertNull("the same range paints nothing", first.takeDirty())

        val second = checkNotNull(rastersFor(LuminanceRange(low = 50f, high = 100f, smoothness = 0f))[0])
        assertSame(first, second)
        assertTrue(second.sample(0.75f, 0.5f) > before)
        assertEquals("the reference is built once per photo", 1, built)
    }

    /** A layer that held a brush and now holds a range — or the other way round — starts from nothing. */
    @Test
    fun aLayerSwitchingKindsIsRepainted() {
        val cache = BrushRasterCache()
        val aspect = ramp.width.toFloat() / ramp.height
        val range = cache.rasters(recipe(luminance(LuminanceRange(0f, 100f, 0f))).toRenderParameters(), aspect, ramp) { BrushReference.of(ramp) }[0]
        assertEquals(1f, checkNotNull(range).sample(0.1f, 0.5f), 1e-3f)

        val brush = MaskComponent(
            shape = MaskShape.BRUSH,
            brush = eu.studio742.imago.core.model.BrushMask(listOf(eu.studio742.imago.core.model.BrushStroke(listOf(0.9f, 0.5f, 0.91f, 0.5f), 0.05f, 0f))),
        )
        val painted = checkNotNull(cache.rasters(recipe(brush).toRenderParameters(), aspect, ramp) { BrushReference.of(ramp) }[0])
        assertEquals("the range is gone", 0f, painted.sample(0.1f, 0.5f), 1e-3f)
        assertEquals(1f, painted.sample(0.905f, 0.5f), 1e-3f)
    }

    private fun brightness(argb: Int): Int = ((argb ushr 16) and 0xFF) + ((argb ushr 8) and 0xFF) + (argb and 0xFF)
}
