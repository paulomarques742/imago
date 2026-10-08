package eu.studio742.imago.core.render

/**
 * The bridge between the original image's space and the visible frame's space.
 *
 * It exists because of the masks. Everything else in the pipeline lives in one of the two spaces and
 * never needs the other: the GPU keeps the `stage` in image space and only applies the geometry when
 * writing to the screen, and the CPU receives the bitmap already cropped and rotated. A mask is the
 * first concept defined in one space and consumed in the other — it is stuck to the **content**, but
 * the user draws it over the **framing**.
 *
 * [imageFromFramed] is the literal transcription of [PhotoShaders.COMPOSITE]'s `geometryCoordinate`,
 * with the crop step on top. [framedFromImage] is its inverse, and `FrameGeometryTest` checks that
 * they cancel out — it is the only way for the two not to diverge silently.
 *
 * Straighten and perspective are one matrix, [frameTransform], and the shader and the Android CPU
 * path receive that same matrix: there is no second reading of the sliders anywhere.
 */
data class FrameGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val cropWidth: Float = 1f,
    val cropHeight: Float = 1f,
    val straighten: Float = 0f,
    val quarterTurns: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    val perspective: PerspectiveParameters = PerspectiveParameters(),
) {
    /**
     * The aspect ratio **after** the quarter turns.
     *
     * It is the same `PhotoRenderer` puts in `uImageAspect`, and has to be: it is what straightening
     * uses so as not to tilt, and using the sensor's aspect ratio on a photo rotated 90° gave a
     * different angle from what the preview shows.
     */
    internal val orientedAspect: Float = run {
        val swaps = quarterTurns % 2 == 1
        val width = if (swaps) sourceHeight else sourceWidth
        val height = if (swaps) sourceWidth else sourceHeight
        width.toFloat() / height.coerceAtLeast(1)
    }

    /**
     * The original image's aspect ratio, **before** the quarter turns.
     *
     * It is not the same as [orientedAspect] and cannot be: masks live in original image coordinates,
     * and that is the aspect ratio that keeps a radial circular.
     */
    val imageAspect: Float = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1)

    /** From the oriented photo to the frame, centred and in photo heights. */
    val viewFromImage: Homography = frameTransform(orientedAspect, straighten, perspective)

    /** The inverse, which is what sampling needs: for each point of the frame, where in the photo. */
    val imageFromView: Homography = viewFromImage.inverse()

    /**
     * Whether the frame may show what lies beyond the photo — white, by decision. Without it every
     * consumer clamps to the edge, as it always did, and a point a hair outside from rounding never
     * turns white.
     */
    val showsOutside: Boolean get() = !perspective.constrainCrop

    /**
     * From the visible frame to the original image, both normalised with the origin at the top-left
     * corner. Writes into [out] so as not to allocate per texel.
     *
     * A point the photo does not cover comes out of `[0, 1]` — or as NaN, when it is beyond the virtual
     * camera's horizon. Only [showsOutside] makes that reachable; [isOutside] tells it apart.
     */
    fun imageFromFramed(u: Float, v: Float, out: FloatArray) {
        val croppedX = cropX + u * cropWidth
        val croppedY = cropY + v * cropHeight

        val pixelX = (croppedX - 0.5f) * orientedAspect
        val pixelY = croppedY - 0.5f
        if (!imageFromView.map(pixelX, pixelY, out)) {
            out[0] = Float.NaN
            out[1] = Float.NaN
            return
        }
        var x = out[0] / orientedAspect + 0.5f
        var y = out[1] + 0.5f

        if (mirrorH) x = 1f - x
        if (mirrorV) y = 1f - y

        when (quarterTurns) {
            1 -> { out[0] = y; out[1] = 1f - x }
            2 -> { out[0] = 1f - x; out[1] = 1f - y }
            3 -> { out[0] = 1f - y; out[1] = x }
            else -> { out[0] = x; out[1] = y }
        }
    }

    /** From the original image to the visible frame. The exact inverse of [imageFromFramed]. */
    fun framedFromImage(u: Float, v: Float, out: FloatArray) {
        var x: Float
        var y: Float
        when (quarterTurns) {
            1 -> { x = 1f - v; y = u }
            2 -> { x = 1f - u; y = 1f - v }
            3 -> { x = v; y = 1f - u }
            else -> { x = u; y = v }
        }

        if (mirrorH) x = 1f - x
        if (mirrorV) y = 1f - y

        if (!viewFromImage.map((x - 0.5f) * orientedAspect, y - 0.5f, out)) {
            out[0] = Float.NaN
            out[1] = Float.NaN
            return
        }
        val croppedX = out[0] / orientedAspect + 0.5f
        val croppedY = out[1] + 0.5f

        out[0] = if (cropWidth != 0f) (croppedX - cropX) / cropWidth else 0f
        out[1] = if (cropHeight != 0f) (croppedY - cropY) / cropHeight else 0f
    }

    /**
     * From the original image to the oriented photo, centred and in photo heights: the space
     * [viewFromImage] starts from, and where the Upright solver measures its guides.
     */
    fun orientedFromImage(u: Float, v: Float, out: FloatArray) {
        var x: Float
        var y: Float
        when (quarterTurns) {
            1 -> { x = 1f - v; y = u }
            2 -> { x = 1f - u; y = 1f - v }
            3 -> { x = v; y = 1f - u }
            else -> { x = u; y = v }
        }
        if (mirrorH) x = 1f - x
        if (mirrorV) y = 1f - y
        out[0] = (x - 0.5f) * orientedAspect
        out[1] = y - 0.5f
    }

    /**
     * [framedFromImage] as a single matrix, from the original image's pixels to the frame's
     * normalised coordinates: what a canvas needs to draw the photo as the stage frames it.
     */
    fun framedFromImagePixels(): Homography {
        val toNormalised = Homography.scale(1.0 / sourceWidth.coerceAtLeast(1), 1.0 / sourceHeight.coerceAtLeast(1))
        val turned = when (quarterTurns) {
            1 -> Homography.of(0.0, -1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            2 -> Homography.of(-1.0, 0.0, 1.0, 0.0, -1.0, 1.0, 0.0, 0.0, 1.0)
            3 -> Homography.of(0.0, 1.0, 0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 1.0)
            else -> Homography.scale(1.0, 1.0)
        }
        val mirrored = Homography.of(
            if (mirrorH) -1.0 else 1.0, 0.0, if (mirrorH) 1.0 else 0.0,
            0.0, if (mirrorV) -1.0 else 1.0, if (mirrorV) 1.0 else 0.0,
            0.0, 0.0, 1.0,
        )
        val aspect = orientedAspect.toDouble()
        val centred = Homography.of(aspect, 0.0, -aspect / 2, 0.0, 1.0, -0.5, 0.0, 0.0, 1.0)
        val uncentred = Homography.of(1 / aspect, 0.0, 0.5, 0.0, 1.0, 0.5, 0.0, 0.0, 1.0)
        val cropped = Homography.of(
            1.0 / cropWidth, 0.0, -cropX.toDouble() / cropWidth,
            0.0, 1.0 / cropHeight, -cropY.toDouble() / cropHeight,
            0.0, 0.0, 1.0,
        )
        return cropped * uncentred * viewFromImage * centred * mirrored * turned * toNormalised
    }

    /** Whether a point [imageFromFramed] wrote lies beyond the photo. */
    fun isOutside(point: FloatArray): Boolean {
        val x = point[0]
        val y = point[1]
        return x.isNaN() || y.isNaN() || x < 0f || x > 1f || y < 0f || y > 1f
    }

}

/**
 * The geometry of these parameters, for a source image with these dimensions.
 *
 * The dimensions are those from **before** crop and rotation. A caller on the CPU path has to capture
 * them before transforming the bitmap, because `applyRecipeGeometry` and
 * `FullResolutionExporter.applyGeometry` throw them away when cropping.
 */
fun RenderParameters.frameGeometry(sourceWidth: Int, sourceHeight: Int) = FrameGeometry(
    sourceWidth = sourceWidth,
    sourceHeight = sourceHeight,
    cropX = cropX,
    cropY = cropY,
    cropWidth = cropWidth,
    cropHeight = cropHeight,
    straighten = straighten,
    quarterTurns = normalizedQuarterTurns(),
    mirrorH = mirrorH,
    mirrorV = mirrorV,
    perspective = perspective,
)
