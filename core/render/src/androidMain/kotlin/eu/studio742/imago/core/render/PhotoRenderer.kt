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
import eu.studio742.imago.core.model.MASKS_PER_FIELD
import eu.studio742.imago.core.model.MAX_LOCAL_MASKS
import eu.studio742.imago.core.model.MAX_MASK_COMPONENTS
import eu.studio742.imago.core.model.MaskShape
import eu.studio742.imago.core.model.LocalAdjustments
import eu.studio742.imago.core.model.Retouch

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
    @Volatile private var highPrecisionSource: FloatPixelBuffer? = null
    @Volatile private var pendingUpload = false
    @Volatile private var parameters = RenderParameters()
    @Volatile private var showOriginal = false
    @Volatile private var maskOverlay = -1
    @Volatile private var clipping = ClippingWarning.NONE
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
    private var noise: GlProgram? = null
    private var defringe: GlProgram? = null

    private var sourceTexture = 0
    /** The brush rasters, one layer each; a 1 × 1 empty layer while there are none, so the sampler always has a texture. */
    private var brushTexture = 0
    private var brushTextureWidth = 0
    private var brushTextureHeight = 0
    private var brushTextureLayers = 0
    /** The raster each layer last received; a different one means the whole layer goes up again. */
    private var uploadedBrushes = arrayOfNulls<BrushRaster>(0)
    private val brushCache = BrushRasterCache()
    /**
     * The photo with its corrections, kept for as long as they stay the same: they are worked out here,
     * on the GL thread, when they change, and a slider moved afterwards finds them ready. [plain] is the
     * bitmap's pixels, read once per bitmap, the key under which they are kept.
     */
    private val retouched = RetouchedSources(capacity = 2)
    private var plain: PixelBuffer? = null
    private var plainOf: Bitmap? = null
    /** The corrections now in [sourceTexture]; null before the first upload. */
    private var uploadedRetouches: List<Retouch>? = null
    private var toneCurveTexture = 0
    /** The tables now in [toneCurveTexture]; uploaded again only when the curves change. */
    private var uploadedToneCurves: FloatArray? = null
    private val toneCurveTexels = ByteBuffer.allocateDirect(CURVE_SAMPLE_COUNT * 4 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var imageWidth = 1
    private var imageHeight = 1

    private var halfFloatTargets = true
    /** The stage; [spare] takes its place when step 10 has written the cleaned one into it. */
    private var stage = GlRenderTarget()
    private var spare = GlRenderTarget()
    private val noiseHalf = GlRenderTarget()
    private val noiseChromaScratch = GlRenderTarget()
    private val noiseChromaBlur = GlRenderTarget()
    private val noiseLumaScratch = GlRenderTarget()
    private val noiseLumaBlur = GlRenderTarget()
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
    private val sharpScratch = GlRenderTarget()
    private val sharpBlur = GlRenderTarget()
    private val localBlur = GlRenderTarget()
    private val maskField = GlRenderTarget()
    /** Masks five to eight. Allocated the first time a photo has a fifth mask, and kept from then on. */
    private val maskField2 = GlRenderTarget()
    private val allTargets
        get() = listOf(
            stage, spare, noiseHalf, noiseChromaScratch, noiseChromaBlur, noiseLumaScratch, noiseLumaBlur,
            half, halfScratch, bandBlur, quarter,
            eighth, eighthScratch, coarseBlur, stats, statsScratch, darkBlur, localBlur, maskField, maskField2,
        )

    // Filled once per frame and reused: allocating thirteen vectors per pass would be garbage to
    // collect within the 16 ms budget, with nothing in return.
    private val maskWeightsBuffer = FloatArray(MAX_LOCAL_MASKS)
    private val maskComponentA = FloatArray(MAX_MASK_COMPONENTS * 4)
    private val maskComponentB = FloatArray(MAX_MASK_COMPONENTS * 4)
    private val maskComponentKind = IntArray(MAX_MASK_COMPONENTS)
    private val maskComponentStart = IntArray(MAX_LOCAL_MASKS)
    private val maskComponentCount = IntArray(MAX_LOCAL_MASKS)
    private val maskInverted = FloatArray(MASKS_PER_FIELD)

    // The kernels are fixed: filling the uniform vector once avoids three allocations per frame in a
    // place with no slack to collect them.
    private val bandKernel = BlurKernel(PhotoEffects.TEXTURE_BAND_SIGMA / 2f)
    private val coarseKernel = BlurKernel(PhotoEffects.CLARITY_SIGMA / 8f)
    private val darkKernel = BlurKernel(PhotoEffects.DEHAZE_REFINE_SIGMA / 8f)
    private val localKernel = BlurKernel(PhotoEffects.LOCAL_TONE_SIGMA / 8f)
    private val noiseLumaKernel = BlurKernel(NoiseReduction.LUMA_SIGMA)
    private val fringeKernel = BlurKernel(Defringe.BLUR_SIGMA / 2f)
    private var noiseChromaKernel = BlurKernel(1f)
    private var noiseChromaSigma = 1f
    private var sharpKernel = BlurKernel(1f)
    private var sharpKernelSigma = 1f

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

    fun setHighPrecisionSource(value: FloatPixelBuffer?) {
        if (value !== highPrecisionSource) { highPrecisionSource = value; pendingUpload = bitmap != null }
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
    fun setClipping(value: ClippingWarning) { clipping = value }
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
        // Apart from the others on purpose: if this device cannot build it, only step 10 is lost.
        noise = runCatching { GlProgram(PhotoShaders.VERTEX, PhotoShaders.NOISE) }
            .onFailure { android.util.Log.w("imago-gl", "The noise reduction program did not build", it) }.getOrNull()
        defringe = runCatching { GlProgram(PhotoShaders.VERTEX, PhotoShaders.DEFRINGE) }
            .onFailure { android.util.Log.w("imago-gl", "The defringe program did not build", it) }.getOrNull()
        // The context is new: the previous handles no longer exist and deleting them now would
        // destroy the objects that inherited the same names.
        allTargets.forEach(GlRenderTarget::forget)
        halfFloatTargets = true
        sourceTexture = createSourceTexture()
        toneCurveTexture = createToneCurveTexture()
        brushTexture = createBrushTexture()
        brushTextureWidth = 0
        brushTextureHeight = 0
        brushTextureLayers = 0
        uploadedBrushes = arrayOfNulls(0)
        uploadedToneCurves = null
        pendingUpload = bitmap != null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val source = bitmap ?: return
        val active = if (showOriginal) parameters.original else parameters
        // "Before" has no corrections: the texture goes back to the bare photo, and forward again after.
        if (pendingUpload || active.retouches != uploadedRetouches) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
            val raw = highPrecisionSource?.let { retouched.of(it, active.retouches) }
            if (raw == null) {
                val corrected = if (active.retouches.isEmpty()) source else correctedBitmap(source, active.retouches)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, corrected, 0)
                if (corrected !== source) corrected.recycle()
            } else {
                val pixels = ByteBuffer.allocateDirect(Math.multiplyExact(raw.width * raw.height, 16))
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until raw.width * raw.height) {
                    pixels.put(raw.rgb[i * 3]); pixels.put(raw.rgb[i * 3 + 1]); pixels.put(raw.rgb[i * 3 + 2]); pixels.put(1f)
                }
                pixels.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, raw.width, raw.height, 0,
                    GLES30.GL_RGBA, GLES30.GL_FLOAT, pixels)
            }
            imageWidth = source.width.coerceAtLeast(1)
            imageHeight = source.height.coerceAtLeast(1)
            uploadedRetouches = active.retouches
            pendingUpload = false
        }

        if (!ensureStage()) return
        val maskFields = if (active.needsMaskField) buildMaskField(active) else 0
        val local = active.needsLocalToneMask && buildLocalToneMask(active, maskFields)
        renderTone(active, local, maskFields)
        if (active.needsDefringe) removeFringes(active)
        if (active.needsNoiseStage) reduceNoise(active)
        val detail = active.needsDetailStage && buildDetailPyramid(active)
        val sharpen = active.needsSharpenStage && buildSharpening(active)
        renderComposite(active, detail, sharpen, maskFields)
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
     *
     * Returns how many halves of the field were built: none when it could not be allocated, two only
     * when there is a fifth mask.
     */
    private fun buildMaskField(active: RenderParameters): Int {
        val program = maskFieldProgram ?: return 0
        val masks = active.masks.take(MAX_LOCAL_MASKS)
        val halves = if (masks.size > MASKS_PER_FIELD) 2 else 1
        val brushes = active.hasBrushes
        // With a brush, one half and not one eighth: a painted edge is not smooth like a gradient.
        val divisor = if (brushes) PhotoEffects.MASK_FIELD_BRUSH_DIVISOR else PhotoEffects.MASK_FIELD_DIVISOR
        val floor = PhotoEffects.MASK_FIELD_MIN
        val fieldWidth = (imageWidth / divisor).coerceAtLeast(floor)
        val fieldHeight = (imageHeight / divisor).coerceAtLeast(floor)
        maskField.ensure(fieldWidth, fieldHeight, halfFloatTargets)
        if (!maskField.isComplete) return 0
        if (halves == 2) {
            maskField2.ensure(fieldWidth, fieldHeight, halfFloatTargets)
            if (!maskField2.isComplete) return 0
        }

        if (brushes) uploadBrushes(active)

        var componentIndex = 0
        masks.forEachIndexed { maskIndex, mask ->
            maskComponentStart[maskIndex] = componentIndex
            var written = 0
            for (component in mask.components) {
                if (componentIndex >= MAX_MASK_COMPONENTS) break
                val slot = componentIndex * 4
                // A brush or a range only needs its layer; the rest of its vectors are never read.
                maskComponentA[slot] = if (component.usesRaster) component.brushIndex.toFloat() else component.centreX
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
                maskComponentKind[componentIndex] = component.packedKind
                componentIndex++
                written++
            }
            maskComponentCount[maskIndex] = written
        }
        for (index in masks.size until MAX_LOCAL_MASKS) {
            maskComponentStart[index] = 0
            maskComponentCount[index] = 0
        }

        program.use()
        bindQuad()
        program.float("uImageAspect", imageWidth.toFloat() / imageHeight.coerceAtLeast(1))
        program.ints("uComponentStart", MAX_LOCAL_MASKS, maskComponentStart)
        program.ints("uComponentCount", MAX_LOCAL_MASKS, maskComponentCount)
        program.ints("uComponentKind", MAX_MASK_COMPONENTS, maskComponentKind)
        program.vec4s("uComponentA", MAX_MASK_COMPONENTS, maskComponentA)
        program.vec4s("uComponentB", MAX_MASK_COMPONENTS, maskComponentB)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + BRUSH_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, brushTexture)
        program.int("uBrushes", BRUSH_UNIT)
        for (half in 0 until halves) {
            val offset = half * MASKS_PER_FIELD
            for (channel in 0 until MASKS_PER_FIELD) {
                maskInverted[channel] = if (masks.getOrNull(offset + channel)?.inverted == true) 1f else 0f
            }
            (if (half == 0) maskField else maskField2).bindAsTarget()
            program.int("uMaskOffset", offset)
            program.int("uMaskCount", (masks.size - offset).coerceIn(0, MASKS_PER_FIELD))
            program.vec4("uMaskInverted", maskInverted)
            drawQuad()
        }
        return halves
    }

    private fun plainPixels(source: Bitmap): PixelBuffer =
        plain?.takeIf { plainOf === source } ?: source.toPixelBuffer().also { plain = it; plainOf = source }

    /** A new bitmap of [source] with [retouches], for the texture; the caller recycles it once uploaded. */
    private fun correctedBitmap(source: Bitmap, retouches: List<Retouch>): Bitmap =
        retouched.of(plainPixels(source), retouches).toPlatformBitmap()

    /**
     * Brings the brush rasters up to date and uploads what changed: the whole layer when the raster is
     * new, otherwise only the rectangle its newest dabs touched — a stroke being drawn changes a few
     * thousand texels of a few million.
     *
     * The rasters are painted here, on the GL thread, from the proxy this view shows. The Auto Mask
     * reads this proxy's colours where the export reads the full photo's, each averaged to the raster's
     * size, so the two see nearly the same colours and not exactly the same ones.
     */
    private fun uploadBrushes(active: RenderParameters) {
        val source = bitmap ?: return
        // The Auto Mask and the ranges read the photo as the corrections left it, as the export does.
        val reference: Any = if (active.retouches.isEmpty()) source else retouched.of(plainPixels(source), active.retouches)
        val rasters = brushCache.rasters(active, imageWidth.toFloat() / imageHeight.coerceAtLeast(1), reference) {
            if (reference is PixelBuffer) BrushReference.of(reference) else BrushReference.of(BitmapSurface(source))
        }
        val first = (0 until rasters.count).firstNotNullOfOrNull { rasters[it] } ?: return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + BRUSH_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, brushTexture)
        if (first.width != brushTextureWidth || first.height != brushTextureHeight || rasters.count > brushTextureLayers) {
            brushTextureWidth = first.width
            brushTextureHeight = first.height
            brushTextureLayers = maxOf(rasters.count, brushTextureLayers)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_2D_ARRAY, 0, GLES30.GL_R16F,
                brushTextureWidth, brushTextureHeight, brushTextureLayers, 0,
                GLES30.GL_RED, GLES30.GL_HALF_FLOAT, null,
            )
            uploadedBrushes = arrayOfNulls(brushTextureLayers)
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        for (layer in 0 until rasters.count) {
            val raster = rasters[layer] ?: continue
            val dirty = raster.takeDirty()
            val rect = if (uploadedBrushes[layer] !== raster) {
                intArrayOf(0, 0, raster.width, raster.height)
            } else {
                dirty ?: continue
            }
            uploadBrushRect(raster, layer, rect)
            uploadedBrushes[layer] = raster
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
    }

    private fun uploadBrushRect(raster: BrushRaster, layer: Int, rect: IntArray) {
        val (left, top, right, bottom) = rect.toList()
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return
        val texels = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (y in top until bottom) {
            val row = y * raster.width
            for (x in left until right) texels.put(halfOfLevel[raster.data[row + x].toInt() and 0xFFFF])
        }
        texels.position(0)
        GLES30.glTexSubImage3D(
            GLES30.GL_TEXTURE_2D_ARRAY, 0, left, top, layer, width, height, 1,
            GLES30.GL_RED, GLES30.GL_HALF_FLOAT, texels,
        )
    }

    private fun createBrushTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + BRUSH_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val empty = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).asShortBuffer()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_2D_ARRAY, 0, GLES30.GL_R16F, 1, 1, 1, 0,
            GLES30.GL_RED, GLES30.GL_HALF_FLOAT, empty,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        return ids[0]
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
        program.vec4s(name, MAX_LOCAL_MASKS / MASKS_PER_FIELD, maskWeightsBuffer)
    }

    /**
     * The two halves of the field, or [fallback] in the units of the halves not built — a sampler
     * has to point at some texture, even one the shader will not read.
     */
    private fun bindMaskFields(program: GlProgram, fields: Int, fallback: Int) {
        program.sampler("uMaskField", if (fields > 0) maskField.texture else fallback, MASK_FIELD_UNIT)
        program.sampler("uMaskField2", if (fields > 1) maskField2.texture else fallback, MASK_FIELD_2_UNIT)
        program.int("uMaskFields", fields)
    }

    /** The five adjustments `applyBaseTone` consumes, shared by the tonal step and the base. */
    private fun bindMaskBase(program: GlProgram, active: RenderParameters, fields: Int) {
        bindMaskFields(program, fields, sourceTexture)
        val masks = if (fields > 0) active.masks else emptyList()
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
    private fun buildLocalToneMask(active: RenderParameters, maskFields: Int): Boolean {
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
        bindWhiteBalance(program, active)
        program.float("uExposure", active.exposure + active.rawExposure)
        program.float("uWhites", active.whites / 100f)
        program.float("uBlacks", active.blacks / 100f)
        bindMaskBase(program, active, maskFields)
        drawQuad()

        reduce(half, quarter)
        reduce(quarter, eighth)
        applyBlur(eighth, eighthScratch, localBlur, localKernel)
        return true
    }

    private fun renderTone(active: RenderParameters, localEnabled: Boolean, maskFields: Int) {
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
        bindWhiteBalance(program, active)
        program.float("uExposure", active.exposure + active.rawExposure)
        program.float("uContrast", active.contrast / 100f)
        program.float("uHighlights", active.highlights / 100f)
        program.float("uHighlightCeiling", active.highlightCeiling)
        program.boolean("uWideHighlights", active.wideHighlights)
        program.float("uShadows", active.shadows / 100f)
        program.float("uWhites", active.whites / 100f)
        program.float("uBlacks", active.blacks / 100f)
        uploadToneCurves(active.channelToneCurves)
        program.sampler("uToneCurve", toneCurveTexture, TONE_CURVE_UNIT)
        val hslValues = active.hslBands.flatMap { band ->
            listOf(band.hue / 100f, band.saturation / 100f, band.luminance / 100f)
        }.toFloatArray()
        program.vec3s("uHslBands", HSL_BAND_COUNT, hslValues)
        program.float("uVibrance", active.vibrance / 100f)
        program.float("uSaturation", active.saturation / 100f)

        bindMaskBase(program, active, maskFields)
        val masks = if (maskFields > 0) active.masks else emptyList()
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

    /**
     * The defringe: the stage blurred at half resolution, whose slope says where the edges are, and a pass
     * that draws the purple and green near them towards grey. Like [reduceNoise] it writes into [spare] and
     * swaps; the blur targets are the noise reduction's, and each is done with them before the next starts.
     */
    private fun removeFringes(active: RenderParameters) {
        val program = defringe ?: return
        val precision = halfFloatTargets
        val halfWidth = (imageWidth / 2).coerceAtLeast(1)
        val halfHeight = (imageHeight / 2).coerceAtLeast(1)
        spare.ensure(imageWidth, imageHeight, precision)
        noiseHalf.ensure(halfWidth, halfHeight, precision)
        noiseChromaScratch.ensure(halfWidth, halfHeight, precision)
        noiseChromaBlur.ensure(halfWidth, halfHeight, precision)
        if (!spare.isComplete || !noiseHalf.isComplete || !noiseChromaScratch.isComplete || !noiseChromaBlur.isComplete) return
        reduce(stage, noiseHalf)
        applyBlur(noiseHalf, noiseChromaScratch, noiseChromaBlur, fringeKernel)
        spare.bindAsTarget()
        program.use()
        bindQuad()
        program.sampler("uStage", stage.texture, 0)
        program.sampler("uBlur", noiseChromaBlur.texture, 1)
        program.vec2("uTexel", noiseChromaBlur.texelWidth, noiseChromaBlur.texelHeight)
        program.float("uPurpleAmount", active.lensPurpleAmount)
        program.float("uPurpleRange", active.lensPurpleRange)
        program.float("uGreenAmount", active.lensGreenAmount)
        program.float("uGreenRange", active.lensGreenRange)
        drawQuad()
        val cleaned = spare
        spare = stage
        stage = cleaned
    }

    /**
     * Step 10: the stage, cleaned. The blurred copies are made from it, the pass writes the cleaned image
     * into [spare], and [spare] becomes the stage — everything after reads the cleaned one without
     * knowing there was another. If anything cannot be built, the stage is left as it was.
     */
    private fun reduceNoise(active: RenderParameters) {
        val program = noise ?: return
        val precision = halfFloatTargets
        spare.ensure(imageWidth, imageHeight, precision)
        if (!spare.isComplete) return
        if (active.noiseLuminance > 0f) {
            noiseLumaScratch.ensure(imageWidth, imageHeight, precision)
            noiseLumaBlur.ensure(imageWidth, imageHeight, precision)
            if (!noiseLumaScratch.isComplete || !noiseLumaBlur.isComplete) return
            applyBlur(stage, noiseLumaScratch, noiseLumaBlur, noiseLumaKernel)
        }
        if (active.noiseColor > 0f) {
            val halfWidth = (imageWidth / 2).coerceAtLeast(1)
            val halfHeight = (imageHeight / 2).coerceAtLeast(1)
            noiseHalf.ensure(halfWidth, halfHeight, precision)
            noiseChromaScratch.ensure(halfWidth, halfHeight, precision)
            noiseChromaBlur.ensure(halfWidth, halfHeight, precision)
            if (!noiseHalf.isComplete || !noiseChromaScratch.isComplete || !noiseChromaBlur.isComplete) return
            val sigma = NoiseReduction.chromaSigma(active.noiseColorSmoothness) / 2f
            if (sigma != noiseChromaSigma) {
                noiseChromaKernel = BlurKernel(sigma)
                noiseChromaSigma = sigma
            }
            reduce(stage, noiseHalf)
            applyBlur(noiseHalf, noiseChromaScratch, noiseChromaBlur, noiseChromaKernel)
        }
        spare.bindAsTarget()
        program.use()
        bindQuad()
        program.sampler("uStage", stage.texture, 0)
        program.sampler("uLumaBlur", if (active.noiseLuminance > 0f) noiseLumaBlur.texture else stage.texture, 1)
        program.sampler("uChromaBlur", if (active.noiseColor > 0f) noiseChromaBlur.texture else stage.texture, 2)
        program.float("uLumaAmount", active.noiseLuminance)
        program.float("uLumaDetail", active.noiseLuminanceDetail)
        program.float("uLumaContrast", active.noiseContrast)
        program.float("uColorAmount", active.noiseColor)
        program.float("uColorDetail", active.noiseColorDetail)
        drawQuad()
        val cleaned = spare
        spare = stage
        stage = cleaned
    }

    /** Step 11's blurred copy of the stage, at the stage's resolution, with the radius in its pixels. */
    private fun buildSharpening(active: RenderParameters): Boolean {
        val precision = halfFloatTargets
        sharpScratch.ensure(imageWidth, imageHeight, precision)
        sharpBlur.ensure(imageWidth, imageHeight, precision)
        if (!sharpScratch.isComplete || !sharpBlur.isComplete) return false
        if (active.sharpenRadius != sharpKernelSigma) {
            sharpKernel = BlurKernel(active.sharpenRadius)
            sharpKernelSigma = active.sharpenRadius
        }
        applyBlur(stage, sharpScratch, sharpBlur, sharpKernel)
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

    private fun renderComposite(active: RenderParameters, detailEnabled: Boolean, sharpenEnabled: Boolean, maskFields: Int) {
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
        program.sampler("uSharpBlur", if (sharpenEnabled) sharpBlur.texture else fallbackTexture(stats), SHARP_BLUR_UNIT)
        program.boolean("uSharpEnabled", sharpenEnabled)
        program.float("uSharpAmount", active.sharpenAmount)
        program.float("uSharpDetail", active.sharpenDetail)
        program.float("uSharpMasking", active.sharpenMasking)

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
        program.float("uDistortion", LensDistortion.coefficient(active.perspective.distortion).toFloat())
        program.float("uDistortionRadius2", LensDistortion.radiusSquared(imageAspect.toDouble()).toFloat())
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

        bindMaskFields(program, maskFields, stage.texture)
        val masks = if (maskFields > 0) active.masks else emptyList()
        bindMaskAdjustment(program, "uMaskTexture", masks, 0.01f) { it.texture }
        bindMaskAdjustment(program, "uMaskClarity", masks, 0.01f) { it.clarity }
        bindMaskAdjustment(program, "uMaskDehaze", masks, 0.01f) { it.dehaze }
        program.int("uMaskOverlay", if (maskOverlay < masks.size) maskOverlay else -1)
        program.boolean("uClipShadows", clipping.shadows)
        program.boolean("uClipHighlights", clipping.highlights)

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

    /**
     * The white balance of both passes that develop the base tone. The matrix at the global sliders
     * is built once here; the constants let the shader build it per pixel where a mask moves them.
     */
    private fun bindWhiteBalance(program: GlProgram, active: RenderParameters) {
        program.boolean("uSourceLinear", highPrecisionSource?.linear == true)
        program.int("uRawProfile", active.rawProfile)
        program.boolean("uCalibrationOn", active.needsCalibration)
        program.float("uLensVignetteAmount", active.lensVignetteAmount)
        program.float("uLensVignetteMidpoint", active.lensVignetteMidpoint)
        program.float("uLensAspect", imageWidth.toFloat() / imageHeight.coerceAtLeast(1))
        program.mat3("uCalibration", active.calibrationMatrix ?: IDENTITY_3X3)
        program.float("uCalibrationTint", active.calibrationShadowsTint)
        program.float("uRawProfileKnee", RawProfile.KNEE)
        program.boolean("uLightWhiteBalance", active.lightWhiteBalance)
        if (!active.lightWhiteBalance) return
        program.mat3("uWhiteBalance", WhiteBalanceModel.matrix(active.temperature / 100f, active.tint / 100f))
        program.mat3("uWhiteBalanceFromLms", WhiteBalanceModel.fromLms)
        program.mat3("uWhiteBalanceToLms", WhiteBalanceModel.toLms)
        program.mat3("uWhiteBalanceBradford", WhiteBalanceModel.bradford)
        val lms = WhiteBalanceModel.d65Lms
        program.vec3("uWhiteBalanceD65Lms", lms[0], lms[1], lms[2])
        val offset = WhiteBalanceModel.d65Offset
        program.vec2("uWhiteBalanceD65Offset", offset[0], offset[1])
    }

    /** 256 × 1 texels of 32-bit float: no precision lost against the export, which reads the same tables. */
    private fun createToneCurveTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        // Float textures cannot be filtered in ES 3.0; the shader reads texels and interpolates itself.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun uploadToneCurves(tables: FloatArray) {
        if (uploadedToneCurves === tables) return
        toneCurveTexels.position(0)
        for (sample in 0 until CURVE_SAMPLE_COUNT) {
            toneCurveTexels.put(tables[sample * 3]).put(tables[sample * 3 + 1]).put(tables[sample * 3 + 2]).put(1f)
        }
        toneCurveTexels.position(0)
        // Its own unit: whatever unit is active now holds a texture this pass is about to sample.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + TONE_CURVE_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneCurveTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, CURVE_SAMPLE_COUNT, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, toneCurveTexels,
        )
        uploadedToneCurves = tables
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

/** The tone curve's tables, read only by the tonal step; the first unit after the mask field's. */
private const val TONE_CURVE_UNIT = 6

/** The second half of the mask field: after the tone curve's, so that no pass shares a unit. */
private const val MASK_FIELD_2_UNIT = 7

/** The brush rasters, read only by the field pass. */
private const val BRUSH_UNIT = 8

private val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

/** The stage blurred with the sharpening radius; units 0 to 8 are taken. */
private const val SHARP_BLUR_UNIT = 9

/** A raster's sixteen-bit level as the half float the texture holds, computed once for all 65 536. */
private val halfOfLevel = ShortArray(65536) { android.util.Half.toHalf(it / 65535f) }
