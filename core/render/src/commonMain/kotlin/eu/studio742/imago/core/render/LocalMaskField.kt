package eu.studio742.imago.core.render

import kotlin.math.floor
import kotlin.math.max
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MaskShape

/**
 * The weight of each mask at each point of the photo, in a low-resolution field.
 *
 * It is the CPU counterpart of [PhotoRenderer]'s `MASK_FIELD` pass, and the reason for both is the
 * usual one: the preview runs in GLSL and the export in Kotlin, and the maths both transcribe lives
 * in [PhotoEffects].
 *
 * There is a deliberate difference between the two sides. The GPU builds the field in **image**
 * coordinates, because that is where the `stage` lives and the tonal step reads it with the same
 * coordinate it reads the image with — geometry never comes near it. The CPU receives the bitmap
 * already cropped and rotated, and so builds the field in **frame** coordinates, taking each texel
 * back to image space with [FrameGeometry.imageFromFramed] to know what it is worth there. The two
 * grids do not coincide texel for texel, but they sample the same continuous function, and a mask is
 * smooth by construction: the difference is sub-texel, of the same order as the two divergences the
 * pipeline already accepts.
 *
 * Doing the opposite — building the field in image space on the CPU too — would require transforming
 * the geometry once per pixel on a 24 Mpx image, instead of once per texel of a field with a
 * sixty-fourth of the points.
 */
internal class LocalMaskField private constructor(
    private val data: FloatArray,
    private val width: Int,
    private val height: Int,
    private val count: Int,
) {
    /**
     * The mask weights at this point of the frame, sampled as the GL sampler would. Writes into [out],
     * which must have [MAX_LOCAL_MASKS] slots.
     */
    fun at(u: Float, v: Float, out: FloatArray) {
        val x = u * width - 0.5f
        val y = v * height - 0.5f
        val left = floor(x).toInt()
        val top = floor(y).toInt()
        val fractionX = x - left
        val fractionY = y - top
        val x0 = left.coerceIn(0, width - 1)
        val x1 = (left + 1).coerceIn(0, width - 1)
        val y0 = top.coerceIn(0, height - 1)
        val y1 = (top + 1).coerceIn(0, height - 1)
        val topLeft = (y0 * width + x0) * MAX_LOCAL_MASKS
        val topRight = (y0 * width + x1) * MAX_LOCAL_MASKS
        val bottomLeft = (y1 * width + x0) * MAX_LOCAL_MASKS
        val bottomRight = (y1 * width + x1) * MAX_LOCAL_MASKS
        for (channel in 0 until MAX_LOCAL_MASKS) {
            if (channel >= count) {
                out[channel] = 0f
                continue
            }
            val upper = data[topLeft + channel] +
                (data[topRight + channel] - data[topLeft + channel]) * fractionX
            val lower = data[bottomLeft + channel] +
                (data[bottomRight + channel] - data[bottomLeft + channel]) * fractionX
            out[channel] = upper + (lower - upper) * fractionY
        }
    }

    /**
     * The effective adjustments at this point: the global ones plus the sum of the local ones weighted
     * by the masks.
     *
     * The sum is the right operation and not a convenient approximation. Two overlapping masks, each
     * at plus one stop of exposure, give two — which is what whoever drew them expects, and which is
     * exact, because exposures compose additively in EV. Mixing the two outputs instead of the
     * parameters would give an order-dependent, meaningless result.
     *
     * Exposure is the only one that does not limit itself, and so it gets an explicit limit; the
     * others are already clamped inside the functions that consume them.
     */
    fun resolve(
        parameters: RenderParameters,
        u: Float,
        v: Float,
        weights: FloatArray,
        out: EffectiveTone,
    ) {
        at(u, v, weights)
        out.loadGlobal(parameters)
        for (index in 0 until count) {
            val weight = weights[index]
            if (weight == 0f) continue
            val local = parameters.masks[index].adjustments
            out.exposure += weight * local.exposure
            out.contrast += weight * local.contrast
            out.highlights += weight * local.highlights
            out.shadows += weight * local.shadows
            out.whites += weight * local.whites
            out.blacks += weight * local.blacks
            out.temperature += weight * local.temp
            out.tint += weight * local.tint
            out.vibrance += weight * local.vibrance
            out.saturation += weight * local.saturation
            out.texture += weight * local.texture
            out.clarity += weight * local.clarity
            out.dehaze += weight * local.dehaze
        }
        out.exposure = out.exposure.coerceIn(MIN_LOCAL_EXPOSURE, MAX_LOCAL_EXPOSURE)
    }

    companion object {
        fun build(
            framedWidth: Int,
            framedHeight: Int,
            parameters: RenderParameters,
            geometry: FrameGeometry,
        ): LocalMaskField {
            val count = parameters.masks.size.coerceAtMost(MAX_LOCAL_MASKS)
            val width = max(framedWidth / PhotoEffects.MASK_FIELD_DIVISOR, PhotoEffects.MASK_FIELD_MIN)
            val height = max(framedHeight / PhotoEffects.MASK_FIELD_DIVISOR, PhotoEffects.MASK_FIELD_MIN)
            val data = FloatArray(width * height * MAX_LOCAL_MASKS)
            val point = FloatArray(2)
            val aspect = geometry.imageAspect
            for (row in 0 until height) {
                val v = (row + 0.5f) / height
                for (column in 0 until width) {
                    val u = (column + 0.5f) / width
                    geometry.imageFromFramed(u, v, point)
                    val base = (row * width + column) * MAX_LOCAL_MASKS
                    for (index in 0 until count) {
                        data[base + index] = weightOf(parameters.masks[index], point[0], point[1], aspect)
                    }
                }
            }
            return LocalMaskField(data, width, height, count)
        }

        /**
         * The weight of a mask at a point, in normalised coordinates of the original image.
         *
         * The first component seeds the accumulator instead of combining with it: subtracting or
         * intersecting against nothing would always give zero, and a mask that started with one of
         * those components would be invisible with no explanation at all.
         */
        fun weightOf(mask: MaskRenderSpec, x: Float, y: Float, aspect: Float): Float {
            var accumulated = 0f
            mask.components.forEachIndexed { index, component ->
                var weight = when (component.shape) {
                    MaskShape.LINEAR -> PhotoEffects.maskLinearWeight(
                        x = x,
                        y = y,
                        centreX = component.centreX,
                        centreY = component.centreY,
                        cosAngle = component.cosAngle,
                        sinAngle = component.sinAngle,
                        width = component.width,
                        aspect = aspect,
                    )
                    MaskShape.RADIAL -> PhotoEffects.maskRadialWeight(
                        x = x,
                        y = y,
                        centreX = component.centreX,
                        centreY = component.centreY,
                        cosAngle = component.cosAngle,
                        sinAngle = component.sinAngle,
                        radiusX = component.radiusX,
                        radiusY = component.radiusY,
                        feather = component.feather,
                        roundness = component.roundness,
                        aspect = aspect,
                    )
                }
                if (component.inverted) weight = 1f - weight
                accumulated = if (index == 0) {
                    weight
                } else {
                    PhotoEffects.combineMaskComponent(accumulated, weight, component.op)
                }
            }
            return if (mask.inverted) 1f - accumulated else accumulated
        }
    }
}

/** The global adjustments, ready to be overridden by the masks — or to stand on their own, without them. */
internal fun RenderParameters.effectiveTone() = EffectiveTone().apply { loadGlobal(this@effectiveTone) }

/** Limits of the effective exposure, the same as the global slider's. */
internal const val MIN_LOCAL_EXPOSURE = -5f
internal const val MAX_LOCAL_EXPOSURE = 5f

/**
 * The scalar adjustments a mask can move, at the value they have at a specific pixel.
 *
 * It is mutable and reused throughout the pass on purpose: one object per pixel in a 24 Mpx export
 * would be twenty-four million allocations to carry thirteen floats. The tone curve and HSL are not
 * here because they are not local — they keep coming from the global parameters.
 */
internal class EffectiveTone {
    var temperature = 0f
    var tint = 0f
    var exposure = 0f
    var contrast = 0f
    var highlights = 0f
    var shadows = 0f
    var whites = 0f
    var blacks = 0f
    var vibrance = 0f
    var saturation = 0f
    var texture = 0f
    var clarity = 0f
    var dehaze = 0f
    /** From process 13 the white balance is a light and an adaptation; before it, three gains. */
    var lightWhiteBalance = true

    private var matrixTemperature = Float.NaN
    private var matrixTint = Float.NaN
    private var matrix = FloatArray(9)

    /**
     * The white balance matrix for this pixel's Temperature and Tint. Kept while they repeat — the
     * whole photo, without masks — since building it is far dearer than using it.
     */
    fun whiteBalanceMatrix(): FloatArray {
        if (temperature != matrixTemperature || tint != matrixTint) {
            matrix = WhiteBalanceModel.matrix(temperature / 100f, tint / 100f)
            matrixTemperature = temperature
            matrixTint = tint
        }
        return matrix
    }

    fun loadGlobal(parameters: RenderParameters) {
        temperature = parameters.temperature
        tint = parameters.tint
        exposure = parameters.exposure
        contrast = parameters.contrast
        highlights = parameters.highlights
        shadows = parameters.shadows
        whites = parameters.whites
        blacks = parameters.blacks
        vibrance = parameters.vibrance
        saturation = parameters.saturation
        texture = parameters.texture
        clarity = parameters.clarity
        dehaze = parameters.dehaze
        lightWhiteBalance = parameters.lightWhiteBalance
    }
}
