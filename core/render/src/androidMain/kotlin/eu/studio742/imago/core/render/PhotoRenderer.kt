package eu.studio742.imago.core.render

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MAX_MASK_COMPONENTS
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.LocalAdjustments

/**
 * The preview pipeline, in several passes.
 *
 * Steps 1 to 8 run once into an `RGBA16F` target at the image's resolution. From there a blur
 * pyramid is built — it gives the neighbourhood context texture, clarity and dehaze need — and a
 * last pass brings together step 9, step 13 and the geometry in a single draw to the screen.
 *
 * The pyramid is only built when one of the three step 9 parameters is active: in an edit that only
 * touches light and colour, the cost per frame stays that of two passes.
 */
internal class PhotoRenderer : GLSurfaceView.Renderer {
    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var pendingUpload = false
    @Volatile private var parameters = RenderParameters()
    @Volatile private var showOriginal = false
    @Volatile private var maskOverlay = -1
    @Volatile private var minZoom = MIN_PHOTO_ZOOM
    @Volatile private var zoom = MIN_PHOTO_ZOOM
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f

    private var maskFieldProgram: GlProgram? = null
    private var localBase: GlProgram? = null
    private var tone: GlProgram? = null
    private var downsample: GlProgram? = null
    private var blur: GlProgram? = null
    private var statistics: GlProgram? = null
    private var composite: GlProgram? = null

    private var sourceTexture = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var imageWidth = 1
    private var imageHeight = 1

    private var halfFloatTargets = true
    private val stage = GlRenderTarget()
    private val half = GlRenderTarget()
    private val halfScratch = GlRenderTarget()
    private val bandBlur = GlRenderTarget()
    private val quarter = GlRenderTarget()
    private val eighth = GlRenderTarget()
    private val eighthScratch = GlRenderTarget()
    private val coarseBlur = GlRenderTarget()
    private val stats = GlRenderTarget()
    private val statsScratch = GlRenderTarget()
    private val darkBlur = GlRenderTarget()
    private val localBlur = GlRenderTarget()
    private val maskField = GlRenderTarget()
    private val allTargets
        get() = listOf(
            stage, half, halfScratch, bandBlur, quarter,
            eighth, eighthScratch, coarseBlur, stats, statsScratch, darkBlur, localBlur, maskField,
        )

    // Filled once per frame and reused: allocating thirteen vectors per pass would be garbage to
    // collect within the 16 ms budget, with nothing in return.
    private val maskWeightsBuffer = FloatArray(MAX_LOCAL_MASKS)
    private val maskComponentA = FloatArray(MAX_MASK_COMPONENTS * 4)
    private val maskComponentB = FloatArray(MAX_MASK_COMPONENTS * 4)
    private val maskComponentShape = IntArray(MAX_MASK_COMPONENTS)
    private val maskComponentOp = IntArray(MAX_MASK_COMPONENTS)
    private val maskComponentInverted = IntArray(MAX_MASK_COMPONENTS)
    private val maskComponentStart = IntArray(MAX_LOCAL_MASKS)
    private val maskComponentCount = IntArray(MAX_LOCAL_MASKS)
    private val maskInverted = FloatArray(MAX_LOCAL_MASKS)

    // The kernels are fixed: filling the uniform vector once avoids three allocations per frame in a
    // place with no slack to collect them.
    private val bandKernel = BlurKernel(PhotoEffects.TEXTURE_BAND_SIGMA / 2f)
    private val coarseKernel = BlurKernel(PhotoEffects.CLARITY_SIGMA / 8f)
    private val darkKernel = BlurKernel(PhotoEffects.DEHAZE_REFINE_SIGMA / 8f)
    private val localKernel = BlurKernel(PhotoEffects.LOCAL_TONE_SIGMA / 8f)

    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(PhotoShaders.QUAD.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(PhotoShaders.QUAD).position(0) }

    fun setBitmap(value: Bitmap?) {
        if (value !== bitmap) {
            bitmap = value
            pendingUpload = value != null
        }
    }

    fun setParameters(value: RenderParameters) { parameters = value }
    fun setShowOriginal(value: Boolean) { showOriginal = value }

    /**
     * Index of the mask to paint red on top, or -1 for none.
     *
     * It does not go through [RenderParameters] on purpose: those are the contract the export
     * honours and what decides whether a recipe is neutral, and a visual aid of the editor has no
     * business in either.
     */
    fun setMaskOverlay(value: Int) { maskOverlay = value }
    /**
     * @param minZoom the minimum this mode accepts. Crop passes [MIN_CROP_ZOOM] so it can zoom the
     *   photo out; viewing leaves the default and nothing changes for it.
     */
    fun setTransform(transform: PhotoTransform, minZoom: Float = MIN_PHOTO_ZOOM) {
        this.minZoom = minZoom
        this.zoom = transform.zoom.coerceIn(minZoom, MAX_PHOTO_ZOOM)
        this.panX = transform.panX
        this.panY = transform.panY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        maskFieldProgram = GlProgram(PhotoShaders.VERTEX, PhotoShaders.MASK_FIELD)
        localBase = GlProgram(PhotoShaders.VERTEX, PhotoShaders.LOCAL_BASE)
        tone = GlProgram(PhotoShaders.VERTEX, PhotoShaders.TONE)
        downsample = GlProgram(PhotoShaders.VERTEX, PhotoShaders.DOWNSAMPLE)
        blur = GlProgram(PhotoShaders.VERTEX, PhotoShaders.BLUR)
        statistics = GlProgram(PhotoShaders.VERTEX, PhotoShaders.STATS)
        composite = GlProgram(PhotoShaders.VERTEX, PhotoShaders.COMPOSITE)
        // The context is new: the previous handles no longer exist and deleting them now would
        // destroy the objects that inherited the same names.
        allTargets.forEach(GlRenderTarget::forget)
        halfFloatTargets = true
        sourceTexture = createSourceTexture()
        pendingUpload = bitmap != null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val source = bitmap ?: return
        if (pendingUpload) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, source, 0)
            imageWidth = source.width.coerceAtLeast(1)
            imageHeight = source.height.coerceAtLeast(1)
            pendingUpload = false
        }

        val active = if (showOriginal) RenderParameters() else parameters
        if (!ensureStage()) return
        val masked = active.needsMaskField && buildMaskField(active)
        val local = active.needsLocalToneMask && buildLocalToneMask(active, masked)
        renderTone(active, local, masked)
        val detail = active.needsDetailStage && buildDetailPyramid(active)
        renderComposite(active, detail, masked)
    }

    /**
     * The weight of each mask at each point, at one eighth of the resolution, one channel per mask.
     *
     * It runs before everything else because everything else consumes it — including the local
     * adaptation's base layer, which without it would describe an image that is not the one entering
     * step 8.
     *
     * The field lives in image coordinates, like the `stage`, and so this pass knows nothing about
     * crop or rotation. That is what makes geometry a one-sided problem.
     */
    private fun buildMaskField(active: RenderParameters): Boolean {
        val program = maskFieldProgram ?: return false
        val divisor = PhotoEffects.MASK_FIELD_DIVISOR
        val floor = PhotoEffects.MASK_FIELD_MIN
        maskField.ensure(
            (imageWidth / divisor).coerceAtLeast(floor),
            (imageHeight / divisor).coerceAtLeast(floor),
            halfFloatTargets,
        )
        if (!maskField.isComplete) return false

        val masks = active.masks.take(MAX_LOCAL_MASKS)
        var componentIndex = 0
        masks.forEachIndexed { maskIndex, mask ->
            maskComponentStart[maskIndex] = componentIndex
            maskInverted[maskIndex] = if (mask.inverted) 1f else 0f
            var written = 0
            for (component in mask.components) {
                if (componentIndex >= MAX_MASK_COMPONENTS) break
                val slot = componentIndex * 4
                maskComponentA[slot] = component.centreX
                maskComponentA[slot + 1] = component.centreY
                maskComponentA[slot + 2] = component.cosAngle
                maskComponentA[slot + 3] = component.sinAngle
                // The linear uses `x` for the transition's width and ignores the rest; the radial uses
                // all four. They share the same vector so the layout is the same on both sides of the
                // pipeline and parity can be checked by reading.
                maskComponentB[slot] = if (component.shape == MaskShape.LINEAR) {
                    component.width
                } else {
                    component.radiusX
                }
                maskComponentB[slot + 1] = component.radiusY
                maskComponentB[slot + 2] = component.feather
                maskComponentB[slot + 3] = component.roundness
                maskComponentShape[componentIndex] = component.shape.ordinal
                maskComponentOp[componentIndex] = component.op.ordinal
                maskComponentInverted[componentIndex] = if (component.inverted) 1 else 0
                componentIndex++
                written++
            }
            maskComponentCount[maskIndex] = written
        }
        for (index in masks.size until MAX_LOCAL_MASKS) {
            maskComponentStart[index] = 0
            maskComponentCount[index] = 0
            maskInverted[index] = 0f
        }

        maskField.bindAsTarget()
        program.use()
        bindQuad()
        program.float("uImageAspect", imageWidth.toFloat() / imageHeight.coerceAtLeast(1))
        program.int("uMaskCount", masks.size)
        program.vec4("uMaskInverted", maskInverted)
        program.ints("uComponentStart", MAX_LOCAL_MASKS, maskComponentStart)
        program.ints("uComponentCount", MAX_LOCAL_MASKS, maskComponentCount)
        program.ints("uComponentShape", MAX_MASK_COMPONENTS, maskComponentShape)
        program.ints("uComponentOp", MAX_MASK_COMPONENTS, maskComponentOp)
        program.ints("uComponentInverted", MAX_MASK_COMPONENTS, maskComponentInverted)
        program.vec4s("uComponentA", MAX_MASK_COMPONENTS, maskComponentA)
        program.vec4s("uComponentB", MAX_MASK_COMPONENTS, maskComponentB)
        drawQuad()
        return true
    }

    /**
     * Binds the field and a local adjustment, on the same scale the global adjustment reaches the
     * shader with.
     *
     * The global uniforms all come divided by a hundred except exposure, which is EV. A delta on
     * another scale would give no error at all — it would give a mask a hundred times too strong.
     */
    private fun bindMaskAdjustment(
        program: GlProgram,
        name: String,
        masks: List<MaskRenderSpec>,
        scale: Float,
        select: (LocalAdjustments) -> Float,
    ) {
        for (index in 0 until MAX_LOCAL_MASKS) {
            maskWeightsBuffer[index] = masks.getOrNull(index)?.let { select(it.adjustments) * scale } ?: 0f
        }
        program.vec4(name, maskWeightsBuffer)
    }

    /** The five adjustments `applyBaseTone` consumes, shared by the tonal step and the base. */
    private fun bindMaskBase(program: GlProgram, active: RenderParameters, enabled: Boolean) {
        program.sampler("uMaskField", if (enabled) maskField.texture else sourceTexture, MASK_FIELD_UNIT)
        program.boolean("uMaskEnabled", enabled)
        val masks = if (enabled) active.masks else emptyList()
        bindMaskAdjustment(program, "uMaskTemperature", masks, 0.01f) { it.temp }
        bindMaskAdjustment(program, "uMaskTint", masks, 0.01f) { it.tint }
        bindMaskAdjustment(program, "uMaskExposure", masks, 1f) { it.exposure }
        bindMaskAdjustment(program, "uMaskWhites", masks, 0.01f) { it.whites }
        bindMaskAdjustment(program, "uMaskBlacks", masks, 0.01f) { it.blacks }
    }

    /**
     * Allocates the target of steps 1 to 8. If `RGBA16F` is not renderable on this device — the
     * extension is practically universal on ES 3.x, but not mandatory — it falls back to 8 bits on
     * the same frame, instead of leaving the screen black. In that case the shadows may gain
     * *banding*, which is the lesser evil.
     */
    private fun ensureStage(): Boolean {
        stage.ensure(imageWidth, imageHeight, halfFloatTargets)
        if (stage.isComplete) return true
        if (!halfFloatTargets) return false
        halfFloatTargets = false
        allTargets.forEach(GlRenderTarget::release)
        stage.ensure(imageWidth, imageHeight, halfFloat = false)
        return stage.isComplete
    }

    /**
     * The local adaptation's base layer, in three tiny passes before step 8.
     *
     * It runs before the detail pyramid and so can use as scratch the levels the pyramid will only
     * fill later — [localBlur] is the only texture this path adds to the frame, and it has a
     * sixty-fourth of the image's texels.
     */
    private fun buildLocalToneMask(active: RenderParameters, masked: Boolean): Boolean {
        val program = localBase ?: return false
        val precision = halfFloatTargets
        half.ensure(imageWidth / 2, imageHeight / 2, precision)
        quarter.ensure(half.width / 2, half.height / 2, precision)
        eighth.ensure(quarter.width / 2, quarter.height / 2, precision)
        eighthScratch.ensure(eighth.width, eighth.height, precision)
        localBlur.ensure(eighth.width, eighth.height, precision)
        if (!half.isComplete || !eighth.isComplete || !localBlur.isComplete) return false

        half.bindAsTarget()
        program.use()
        bindQuad()
        program.sampler("uTexture", sourceTexture, 0)
        program.vec2("uSourceTexel", 1f / imageWidth, 1f / imageHeight)
        program.float("uTemperature", active.temperature / 100f)
        program.float("uTint", active.tint / 100f)
        program.float("uExposure", active.exposure)
        program.float("uWhites", active.whites / 100f)
        program.float("uBlacks", active.blacks / 100f)
        bindMaskBase(program, active, masked)
        drawQuad()

        reduce(half, quarter)
        reduce(quarter, eighth)
        applyBlur(eighth, eighthScratch, localBlur, localKernel)
        return true
    }

    private fun renderTone(active: RenderParameters, localEnabled: Boolean, masked: Boolean) {
        val program = tone ?: return
        stage.bindAsTarget()
        program.use()
        bindQuad()
        program.sampler("uTexture", sourceTexture, 0)
        // The `stage` does not serve as a fallback here as it does in the composite: it is bound as
        // this very pass's target, and sampling the texture being written to is undefined.
        program.sampler("uLocalBlur", if (localEnabled) localBlur.texture else sourceTexture, 1)
        program.boolean("uLocalEnabled", localEnabled)

        program.float("uTemperature", active.temperature / 100f)
        program.float("uTint", active.tint / 100f)
        program.float("uExposure", active.exposure)
        program.float("uContrast", active.contrast / 100f)
        program.float("uHighlights", active.highlights / 100f)
        program.float("uShadows", active.shadows / 100f)
        program.float("uWhites", active.whites / 100f)
        program.float("uBlacks", active.blacks / 100f)
        program.floats("uToneCurve", CURVE_SAMPLE_COUNT, active.toneCurveRgb.toFloatArray())
        val hslValues = active.hslBands.flatMap { band ->
            listOf(band.hue / 100f, band.saturation / 100f, band.luminance / 100f)
        }.toFloatArray()
        program.vec3s("uHslBands", HSL_BAND_COUNT, hslValues)
        program.float("uVibrance", active.vibrance / 100f)
        program.float("uSaturation", active.saturation / 100f)

        bindMaskBase(program, active, masked)
        val masks = if (masked) active.masks else emptyList()
        bindMaskAdjustment(program, "uMaskContrast", masks, 0.01f) { it.contrast }
        bindMaskAdjustment(program, "uMaskHighlights", masks, 0.01f) { it.highlights }
        bindMaskAdjustment(program, "uMaskShadows", masks, 0.01f) { it.shadows }
        bindMaskAdjustment(program, "uMaskVibrance", masks, 0.01f) { it.vibrance }
        bindMaskAdjustment(program, "uMaskSaturation", masks, 0.01f) { it.saturation }

        drawQuad()
    }

    /** Returns false if any pyramid target cannot be created; step 9 is then skipped. */
    private fun buildDetailPyramid(active: RenderParameters): Boolean {
        val precision = halfFloatTargets
        half.ensure(imageWidth / 2, imageHeight / 2, precision)
        halfScratch.ensure(half.width, half.height, precision)
        bandBlur.ensure(half.width, half.height, precision)
        quarter.ensure(half.width / 2, half.height / 2, precision)
        eighth.ensure(quarter.width / 2, quarter.height / 2, precision)
        eighthScratch.ensure(eighth.width, eighth.height, precision)
        coarseBlur.ensure(eighth.width, eighth.height, precision)
        if (!half.isComplete || !eighth.isComplete || !bandBlur.isComplete || !coarseBlur.isComplete) {
            return false
        }

        reduce(stage, half)
        reduce(half, quarter)
        reduce(quarter, eighth)
        applyBlur(half, halfScratch, bandBlur, bandKernel)
        applyBlur(eighth, eighthScratch, coarseBlur, coarseKernel)

        if (active.needsDehazeStats) {
            stats.ensure(eighth.width, eighth.height, precision, mipmapped = true)
            statsScratch.ensure(eighth.width, eighth.height, precision)
            darkBlur.ensure(eighth.width, eighth.height, precision)
            if (!stats.isComplete || !darkBlur.isComplete) return false
            renderStats()
            applyBlur(stats, statsScratch, darkBlur, darkKernel)
        }
        return true
    }

    private fun reduce(from: GlRenderTarget, to: GlRenderTarget) {
        val program = downsample ?: return
        to.bindAsTarget()
        program.use()
        bindQuad()
        program.sampler("uSource", from.texture, 0)
        program.vec2("uSourceTexel", from.texelWidth, from.texelHeight)
        drawQuad()
    }

    private fun applyBlur(
        from: GlRenderTarget,
        scratch: GlRenderTarget,
        to: GlRenderTarget,
        kernel: BlurKernel,
    ) {
        val program = blur ?: return
        program.use()
        program.int("uRadius", kernel.radius)
        program.floats("uWeights", kernel.padded.size, kernel.padded)

        scratch.bindAsTarget()
        bindQuad()
        program.sampler("uSource", from.texture, 0)
        program.vec2("uStep", from.texelWidth, 0f)
        drawQuad()

        to.bindAsTarget()
        bindQuad()
        program.sampler("uSource", scratch.texture, 0)
        program.vec2("uStep", 0f, scratch.texelHeight)
        drawQuad()
    }

    private fun renderStats() {
        val program = statistics ?: return
        stats.bindAsTarget()
        program.use()
        bindQuad()
        program.sampler("uSource", quarter.texture, 0)
        program.vec2("uSourceTexel", quarter.texelWidth, quarter.texelHeight)
        drawQuad()
        stats.generateMipmaps()
    }

    private fun renderComposite(active: RenderParameters, detailEnabled: Boolean, masked: Boolean) {
        val program = composite ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val viewport = applyFitViewport(active)
        program.use()
        bindQuad()

        program.sampler("uStage", stage.texture, 0)
        program.sampler("uBandBlur", fallbackTexture(bandBlur), 1)
        program.sampler("uCoarseBlur", fallbackTexture(coarseBlur), 2)
        program.sampler("uDarkBlur", fallbackTexture(darkBlur), 3)
        program.sampler("uStats", fallbackTexture(stats), 4)

        program.vec2("uStageTexel", stage.texelWidth, stage.texelHeight)
        program.float("uTexture", if (detailEnabled) active.texture / 100f else 0f)
        program.float("uClarity", if (detailEnabled) active.clarity / 100f else 0f)
        program.float("uDehaze", if (detailEnabled) active.dehaze / 100f else 0f)
        bindColorGrade(program, active.colorGrade)
        program.float("uVignetteAmount", active.vignetteAmount)
        program.float("uVignetteMidpoint", active.vignetteMidpoint)
        program.float("uVignetteRoundness", active.vignetteRoundness)
        program.float("uVignetteFeather", active.vignetteFeather)
        program.float("uGrainAmount", active.grainAmount)
        program.float("uGrainRoughness", active.grainRoughness)
        program.float("uGrainCell", PhotoEffects.grainCellSize(active.grainSize))
        program.float("uGrainGain", PhotoEffects.grainSizeGain(active.grainSize))
        program.vec2("uOutputSize", viewport.width.toFloat(), viewport.height.toFloat())
        program.float("uAirlightLod", (stats.mipmapLevels() - 3).coerceAtLeast(0).toFloat())
        program.vec2("uCropOrigin", active.cropX, active.cropY)
        program.vec2("uCropSize", active.cropWidth, active.cropHeight)
        val quarterTurns = active.normalizedQuarterTurns()
        val swapsDimensions = quarterTurns % 2 == 1
        val orientedWidth = if (swapsDimensions) imageHeight else imageWidth
        val orientedHeight = if (swapsDimensions) imageWidth else imageHeight
        val imageAspect = orientedWidth.toFloat() / orientedHeight.coerceAtLeast(1)
        program.mat3("uImageFromView", frameTransform(imageAspect, active.straighten, active.perspective).inverse().values())
        program.boolean("uShowOutside", !active.perspective.constrainCrop)
        program.float("uImageAspect", imageAspect)
        // Grain is measured in image pixels, and what is drawn here is the cropped frame: this is the
        // extent the shader's `screen` covers, and the denominator that gives it its scale.
        program.vec2(
            "uImagePixels",
            orientedWidth * active.cropWidth,
            orientedHeight * active.cropHeight,
        )
        program.int("uRotation", quarterTurns)
        program.boolean("uMirrorH", active.mirrorH)
        program.boolean("uMirrorV", active.mirrorV)
        program.boolean("uDetailEnabled", detailEnabled)
        program.boolean("uGradingEnabled", active.needsColorGradingStage)
        program.boolean("uEffectsEnabled", active.needsEffectsStage)

        program.sampler("uMaskField", if (masked) maskField.texture else stage.texture, MASK_FIELD_UNIT)
        program.boolean("uMaskEnabled", masked)
        val masks = if (masked) active.masks else emptyList()
        bindMaskAdjustment(program, "uMaskTexture", masks, 0.01f) { it.texture }
        bindMaskAdjustment(program, "uMaskClarity", masks, 0.01f) { it.clarity }
        bindMaskAdjustment(program, "uMaskDehaze", masks, 0.01f) { it.dehaze }
        program.int("uMaskOverlay", if (masked && maskOverlay < masks.size) maskOverlay else -1)

        drawQuad()
    }

    /**
     * Step 12, already translated: one chroma per wheel and the four luminances in a vec4.
     *
     * The order of the vec4 — shadows, midtones, highlights, global — is the same the shader uses
     * for the dot product with the weights; swapping it here would swap the luminances of the tonal
     * zones.
     */
    private fun bindColorGrade(program: GlProgram, grade: ColorGrade) {
        program.vec3("uGradeShadows", grade.shadows.chromaRed, grade.shadows.chromaGreen, grade.shadows.chromaBlue)
        program.vec3("uGradeMidtones", grade.midtones.chromaRed, grade.midtones.chromaGreen, grade.midtones.chromaBlue)
        program.vec3(
            "uGradeHighlights",
            grade.highlights.chromaRed,
            grade.highlights.chromaGreen,
            grade.highlights.chromaBlue,
        )
        program.vec3("uGradeGlobal", grade.global.chromaRed, grade.global.chromaGreen, grade.global.chromaBlue)
        program.vec4(
            "uGradeLuminance",
            floatArrayOf(
                grade.shadows.luminance,
                grade.midtones.luminance,
                grade.highlights.luminance,
                grade.global.luminance,
            ),
        )
        program.float("uGradeBlending", grade.blending)
        program.float("uGradeBalance", grade.balance)
    }

    /** A target not yet allocated is replaced by the `stage`, so no sampler is left unbound. */
    private fun fallbackTexture(target: GlRenderTarget): Int =
        if (target.texture != 0) target.texture else stage.texture

    private fun applyFitViewport(active: RenderParameters): PhotoViewport {
        val quarterTurns = ((active.rotation % 360) + 360) % 360 / 90
        val swapsDimensions = quarterTurns % 2 == 1
        val rotatedWidth = if (swapsDimensions) imageHeight else imageWidth
        val rotatedHeight = if (swapsDimensions) imageWidth else imageHeight
        val croppedWidth = (rotatedWidth * active.cropWidth).roundToInt().coerceAtLeast(1)
        val croppedHeight = (rotatedHeight * active.cropHeight).roundToInt().coerceAtLeast(1)
        val viewport = calculatePhotoViewport(
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            imageWidth = croppedWidth,
            imageHeight = croppedHeight,
            zoom = zoom,
            panX = panX,
            panY = panY,
            minZoom = minZoom,
        )
        GLES30.glViewport(viewport.left, viewport.bottom, viewport.width, viewport.height)
        return viewport
    }

    private fun bindQuad() {
        vertices.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, STRIDE, vertices)
        vertices.position(2)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, STRIDE, vertices)
    }

    private fun drawQuad() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun createSourceTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private companion object {
        const val STRIDE = 4 * Float.SIZE_BYTES
    }
}

/**
 * The texture unit of the mask field, the same in the three passes that read it.
 *
 * The tonal step takes 0 and 1, the composite goes from 0 to 4. Five is the first free one in all
 * of them, and keeping it the same everywhere spares an error that would give no error at all — it
 * would give a mask reading the wrong texture.
 */
private const val MASK_FIELD_UNIT = 5
