package eu.studio742.imago.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import eu.studio742.imago.core.model.MaskOp
import eu.studio742.imago.core.model.MaskShape

/**
 * Step 9 and step 13 exist twice: in Kotlin, for the export, and in GLSL, for the preview. There is no
 * way to run the shader in a JVM test, but there is a way to make sure nobody tunes one of the copies
 * and forgets the other — that is what these assertions do.
 *
 * When one of these tests fails, the fix is almost never to change the test: it is to go to the other
 * copy.
 */
class PhotoShaderParityTest {
    private val composite = PhotoShaders.COMPOSITE

    /**
     * The pipeline has several passes and each one reads a texture to write to another. If the quad's
     * texture coordinate does not follow NDC, every pass flips the image: the photo appears upside
     * down, 90° rotations become transpositions, and the blur pyramid — which goes through a different
     * number of passes than the `stage` — gets misaligned, with clarity and texture reading the
     * mirrored neighbourhood.
     */
    @Test
    fun theQuadDoesNotFlipBetweenPasses() {
        val quad = PhotoShaders.QUAD
        assertEquals("The quad has four vertices of x, y, u, v", 16, quad.size)
        for (vertex in 0 until 4) {
            val y = quad[vertex * 4 + 1]
            val v = quad[vertex * 4 + 3]
            assertEquals(
                "Vertex $vertex flips v relative to NDC; see the comment on PhotoShaders.QUAD",
                (y + 1f) / 2f,
                v,
                0.0001f,
            )
        }
    }

    /** The flip the bitmap's origin requires happens only once, when writing to the screen. */
    @Test
    fun theCompositeFlipsExactlyOnceForTheScreen() {
        assertContains("vec2 screen = vec2(vTexCoord.x, 1.0 - vTexCoord.y)", "the screen flip")
        assertContains("vec2 cropped = uCropOrigin + screen * uCropSize", "o corte normalizado")
        assertContains("geometryCoordinate(cropped)", "the crop geometry in screen space")
        assertContains("applyVignette(color, screen)", "the vignette in screen space")
        assertContains("applyGrain(color, screen)", "the grain in screen space")
    }

    @Test
    fun detailConstantsMatchTheKotlinImplementation() {
        assertContains("softLimit(value - coarse, 0.32)", "the clarity signal limit")
        assertContains("softLimit(fine - band, 0.18)", "the texture signal limit")
        assertContains("1.15 * detail * midtoneWeight(value)", "the clarity gain")
        assertContains("1.6 * detail * edgeMask(band, coarse)", "the texture gain")
        assertContains("1.0 - 0.85 * smoothstep(0.05, 0.24", "texture's edge mask")
        assertContains("4.0 * v * (1.0 - v)", "the clarity midtone weight")
        assertContains("limit * tanh(value / limit)", "the soft limiter")
    }

    /**
     * Step 12 is the easiest case of all to let diverge: the maths is short, there are four constants,
     * and one of them changed on one side only gives an export with a different colour intensity from
     * what the preview showed.
     */
    @Test
    fun colorGradingConstantsMatchTheKotlinImplementation() {
        assertEquals(0.30f, PhotoEffects.COLOR_GRADE_CHROMA, 0f)
        assertEquals(0.5f, PhotoEffects.COLOR_GRADE_LUMINANCE, 0f)
        assertEquals(0.40f, PhotoEffects.colorGradeWidth(0f), 1e-6f)
        assertEquals(1.0f, PhotoEffects.colorGradeWidth(100f), 1e-6f)
        assertEquals(0.5f, PhotoEffects.colorGradeMidtone(0f), 1e-6f)
        assertEquals(0.35f, PhotoEffects.colorGradeMidtone(100f), 1e-6f)
        assertContains("return 0.40 + 0.60 * clamp(blending / 100.0, 0.0, 1.0)", "the width of the tonal weights")
        assertContains("return 0.5 - 0.15 * clamp(balance / 100.0, -1.0, 1.0)", "the midtone pivot")
        assertContains("max(1.0 - abs(value - vec3(0.0, midtone, 1.0)) / width, 0.0)", "the three weights")
        assertContains("if (total > 0.0) weight /= total", "the normalisation of the weights")
        assertContains("weight.z * uGradeHighlights + uGradeGlobal", "the global wheel outside the weights")
        assertContains("clamp(color + 0.30 * chroma, 0.0, 1.0)", "the chroma amplitude")
        assertContains("dot(vec4(weight, 1.0), uGradeLuminance) / 100.0 * 0.5", "the wheels' luminance")
        assertContains("remapLuminance(graded, clamp(value + offset, 0.0, 1.0))", "the luminance remapping")
    }

    /** Step 12 sits between detail and the vignette, where the pipeline places it. */
    @Test
    fun colorGradingRunsBetweenDetailAndTheEffects() {
        val grading = composite.indexOf("color = applyColorGrading(color)")
        val detail = composite.indexOf("color = applyDetail(")
        val vignette = composite.indexOf("color = applyVignette(color, screen)")
        assertTrue("step 12 is not in the composite", grading > 0)
        assertTrue("step 12 runs before the detail", detail < grading)
        assertTrue("step 12 runs after the vignette", grading < vignette)
    }

    /**
     * [PhotoShaders.TONE] and [PhotoShaders.LOCAL_BASE] are assembled by concatenating shared
     * constants, and nothing in this module compiles them — the GLSL compiler only sees them on the
     * device. A constant inserted in the wrong order gives a black screen and no message; these
     * assertions catch the class of error concatenation introduces.
     */
    @Test
    fun theAssembledShadersAreStructurallyWellFormed() {
        listOf(
            "TONE" to PhotoShaders.TONE,
            "LOCAL_BASE" to PhotoShaders.LOCAL_BASE,
            "COMPOSITE" to PhotoShaders.COMPOSITE,
            "MASK_FIELD" to PhotoShaders.MASK_FIELD,
        ).forEach { (name, source) ->
            assertTrue("$name has to start with the version directive", source.startsWith("#version 300 es"))
            assertEquals(
                "$name has unbalanced braces",
                source.count { it == '{' },
                source.count { it == '}' },
            )
            assertEquals(
                "$name has unbalanced parentheses",
                source.count { it == '(' },
                source.count { it == ')' },
            )
            assertTrue("$name has no main", source.contains("void main()"))
        }

        // GLSL ES requires declaration before use, and it is the order of the constants that ensures it.
        val tone = PhotoShaders.TONE
        val main = tone.indexOf("void main()")
        listOf(
            "vec3 applyBaseTone",
            "float luminance",
            "float locallyAdaptedHighlights",
            "float locallyAdaptedShadows",
            "vec3 remapLuminance",
        ).forEach { declaration ->
            val at = tone.indexOf(declaration)
            assertTrue("TONE should declare \"$declaration\"", at >= 0)
            assertTrue("\"$declaration\" has to come before main", at < main)
        }
        // Concatenation can also duplicate: BASE_TONE brings `luminance` and the body of TONE
        // stopped declaring it on its own.
        listOf("float luminance(vec3", "vec3 toSrgb(vec3", "vec3 toLinear(vec3").forEach { declaration ->
            assertEquals("TONE declares \"$declaration\" more than once", 1, tone.split(declaration).size - 1)
        }
        assertTrue(
            "Shadows have to be declared after highlights, which is what they call.",
            tone.indexOf("float locallyAdaptedHighlights") < tone.indexOf("float locallyAdaptedShadows"),
        )
    }

    /**
     * Step 8 also lives twice, and highlights and shadows are the part of it where a swapped constant
     * does not give a subtle difference: it gives a preview that blows out where the export does not.
     */
    @Test
    fun selectiveToneConstantsMatchTheKotlinImplementation() {
        val tone = PhotoShaders.TONE
        listOf(
            "a * (a < 0.0 ? 1.8 : 1.3)" to "the two asymmetric gains",
            "if (pixel <= 0.35) return pixel" to "the highlights pivot",
            "float band = clamp((reference - 0.35) / 0.65, 0.0, 1.0)" to "the neighbourhood's band",
            "float local = band + 0.35 * (t - band)"
                to "the global part of the adaptation",
            "float flow = exp(selectiveHighlightGain(amount) * local)"
                to "the logistic flow's time given by the neighbourhood",
            "0.35 + 0.65 * (t * flow / (1.0 - t + t * flow))"
                to "the logistic flow integrated over the band",
            "1.0 - locallyAdaptedHighlights(1.0 - pixel, mirrored, -amount)"
                to "shadows as the mirror of highlights",
        ).forEach { (fragment, what) ->
            assertTrue(
                "The tone shader should contain $what — \"$fragment\". " +
                    "If it changed in BitmapPhotoProcessor, change it here too.",
                tone.contains(fragment),
            )
        }
    }

    /**
     * The local adaptation mask has to describe the same image on both sides, and that is two
     * things: the same steps 1 to 7, and the same 2×2 average before them.
     *
     * The steps live in a constant shared by [PhotoShaders.TONE] and [PhotoShaders.LOCAL_BASE]
     * precisely so they cannot diverge between themselves; what this test guards is that they keep
     * saying what `BitmapPhotoProcessor.applyBaseTone` says.
     */
    @Test
    fun theLocalToneMaskSeesTheSameImageOnBothSides() {
        listOf(
            "vec3(1.0 + 0.20 * temperature, 1.0 + 0.05 * tint, 1.0 - 0.20 * temperature)"
                to "the temperature multipliers",
            "vec3(1.0 + 0.08 * tint, 1.0 - 0.10 * tint, 1.0 + 0.08 * tint)"
                to "the tint multipliers",
            "blacks * (1.0 - smoothstep(0.0, 0.35, l)) * 0.18" to "the lift of the blacks",
            "whites * smoothstep(0.55, 1.0, l) * 0.22" to "the lift of the whites",
        ).forEach { (fragment, what) ->
            assertTrue(
                "Both base step shaders should contain $what — \"$fragment\".",
                PhotoShaders.TONE.contains(fragment) && PhotoShaders.LOCAL_BASE.contains(fragment),
            )
        }
        assertTrue(
            "The mask's 2×2 average has to be explicit, like LocalToneMask.averageChannel's.",
            PhotoShaders.LOCAL_BASE.contains("vec3 averaged = 0.25 * ("),
        )
        assertTrue(
            "Steps 1 to 7 come after the average, as in the CPU counterpart.",
            PhotoShaders.LOCAL_BASE.indexOf("vec3 averaged") <
                PhotoShaders.LOCAL_BASE.indexOf("applyBaseTone(averaged"),
        )
    }

    /** Negative is the value that says "no mask", on both sides. */
    @Test
    fun theAbsentMaskIsSpelledTheSameWayInBothImplementations() {
        assertTrue(NO_LOCAL_BASE < 0f)
        assertTrue(
            "The shader has to treat a missing mask as a negative value.",
            PhotoShaders.TONE.contains("uLocalEnabled == 1 ? texture(uLocalBlur, vTexCoord).r : -1.0"),
        )
        assertTrue(
            "And the curves have to recognise it.",
            PhotoShaders.TONE.contains("base < 0.0 ? pixel : clamp(base, 0.0, 1.0)"),
        )
    }

    @Test
    fun dehazeConstantsMatchTheKotlinImplementation() {
        assertContains("1.0 - 0.95 * (dark / airlight)", "the dark channel prior's omega")
        assertContains("max(transmission, 0.1)", "the minimum transmission")
    }

    @Test
    fun effectConstantsMatchTheKotlinImplementation() {
        assertContains("0.35 + clamp(uVignetteMidpoint / 100.0, 0.0, 1.0) * 1.10", "the vignette radius")
        assertContains("0.03 + clamp(uVignetteFeather / 100.0, 0.0, 1.0) * 0.85", "the vignette's feathering")
        assertContains("2.0 + max(-round, 0.0) * 6.0", "the vignette's Minkowski exponent")
        assertContains("smoothstep(0.0, 0.14, v) * (1.0 - 0.55 * smoothstep(0.72, 1.0, v))", "the grain weight")
        assertContains("clamp(uGrainAmount / 100.0, 0.0, 1.0) * 0.34 * uGrainGain", "the grain amplitude")
        assertContains("valueNoise(position * 0.22, 1) - 0.5) * 1.6", "the grain's clumping envelope")
    }

    /**
     * The particles have to come out with the same radius, the same ceiling, the same spread and the
     * same edge on both sides. A constant diverging here would break nothing: it would give a preview
     * with different grain, which is worse, because it looks like an option.
     */
    @Test
    fun grainParticleConstantsMatchTheKotlinImplementation() {
        listOf(
            "float spread = 0.30 + 0.25 * rough" to "the spread of the radii",
            "float jitter = 0.80 + 0.20 * rough" to "the offset within the cell",
            "float footprint = 0.75 * pixelInCells" to "the target pixel measured in cells",
            "0.60 * (1.0 +" to "the particle's mean radius",
            "radius = min(radius, 0.80)" to "the radius ceiling",
            "float edge = min(max(0.20 * radius, footprint), 1.0)" to "the edge and its ceiling",
            "min(1.0, (radius / edge) * (radius / edge))" to "the mass-preserving kernel",
            "0.289 / sqrt(variance)" to "the standard deviation the field is normalised to",
            "3.14159265 * 0.90 * 0.60 * 0.60" to "the variance predicted for the sum",
        ).forEach { (fragment, what) -> assertContains(fragment, what) }
    }

    /**
     * The cell is measured in image pixels, and [uImagePixels] is what brings it to target pixels.
     * Without that conversion grain would go back to being a viewport texture, with one granularity on
     * screen and another in the file.
     */
    @Test
    fun theGrainIsScaledFromImagePixels() {
        assertContains(
            "max(uGrainCell, 0.0001) * (uOutputSize.x / max(uImagePixels.x, 1.0))",
            "the cell converted to target pixels",
        )
        assertContains("grainField(position, uGrainRoughness, 1.0 / cellStep)", "the particle field")
    }

    @Test
    fun grainHashConstantsMatchTheKotlinImplementation() {
        // The hash is an integer precisely to give the same pattern on the GPU and the JVM. If one of
        // these constants changes on one side, the preview's grain stops describing the export's.
        listOf("374761393u", "668265263u", "0x9e3779b9u", "0x85ebca6bu", "0xc2b2ae35u", ">> 15u", ">> 13u", ">> 16u")
            .forEach { assertContains(it, "the grain hash's finaliser") }
        // And the slices have to be the same: offset in the low bits, radius in the third byte,
        // sign in bit 24.
        listOf("hash & 0xFFu", "(hash >> 8u) & 0xFFu", "(hash >> 16u) & 0xFFu", "(hash >> 24u) & 1u")
            .forEach { assertContains(it, "the hash slice") }
        // The grid noise survived only as the clumping envelope, and uses the old hash.
        listOf("2246822519u", "<< 10u", ">> 6u", "<< 3u", ">> 11u", "<< 15u")
            .forEach { assertContains(it, "the envelope grid hash") }
        assertContains("float((hash >> 8u) & 0xFFFFu) / 65536.0", "the hash normalisation")
    }

    @Test
    fun theCompositeDeclaresHighpIntegersForTheGrainHash() {
        // Without this the hash runs at mediump, which the ES 3.00 spec only guarantees at 16 bits:
        // the 32-bit constants do not fit, the pattern collapses and the preview is left with no grain.
        assertTrue(
            "The grain pass needs 32-bit integers to give the same pattern as the JVM.",
            PhotoShaders.COMPOSITE.contains("precision highp int;"),
        )
    }

    @Test
    fun theBlurLoopCoversTheRadiusTheKernelCanProduce() {
        assertTrue(
            "The shader has to be able to walk the whole radius halfGaussianKernel returns.",
            PhotoShaders.BLUR.contains("uWeights[${PhotoEffects.MAX_BLUR_RADIUS + 1}]"),
        )
        assertTrue(PhotoShaders.BLUR.contains("index <= ${PhotoEffects.MAX_BLUR_RADIUS}"))
    }

    @Test
    fun luminanceWeightsAreTheSameInEveryStage() {
        val weights = "vec3(0.2126, 0.7152, 0.0722)"
        assertTrue(PhotoShaders.TONE.contains(weights))
        assertTrue(composite.contains(weights))
        assertEquals(0.2126f + 0.7152f + 0.0722f, PhotoEffects.luminance(1f, 1f, 1f), 1e-6f)
    }

    @Test
    fun geometryStillAppliesMirrorBeforeRotation() {
        // Swapping this order would change the result of saved recipes that combine the two.
        val mirror = composite.indexOf("if (uMirrorH == 1)")
        val rotation = composite.indexOf("if (uRotation == 1)")
        assertTrue(mirror in 0 until rotation)
    }

    /**
     * Straighten and perspective reach the shader as `FrameGeometry.imageFromView` and nothing else:
     * the sampling multiplies by it and divides by the depth, and a point the photo does not cover
     * leaves white before any adjustment.
     */
    @Test
    fun theFineGeometryIsTheSharedMatrix() {
        assertContains("uniform mat3 uImageFromView;", "the matrix FrameGeometry computes")
        assertContains("vec3 image = uImageFromView * view;", "the sampling through that matrix")
        assertContains("image.x / image.z / uImageAspect + 0.5", "the projective division")
        assertContains("if (image.z <= 0.0) return vec2(-1.0);", "the horizon of the virtual camera")
        assertContains("fragColor = vec4(1.0);", "white for what the photo does not cover")
        assertTrue(composite.indexOf("fragColor = vec4(1.0);") < composite.indexOf("vec4 staged = texture(uStage, source);"))
    }

    /**
     * The error the mask architecture can produce on its own, and the most dangerous of all: sampling
     * the field in screen space instead of image space.
     *
     * A mask is stuck to the content, and the field is built in image coordinates. In the composite,
     * `screen` is the visible frame and `source` is the image; reading the field at `screen` is
     * **right** as long as there is no crop or rotation, and turns wrong the moment the user crops —
     * without warning, without error, and only on their photo.
     */
    @Test
    fun theMaskFieldIsSampledInImageSpaceEverywhere() {
        assertTrue(
            "The tonal step has to read the field with the image coordinate",
            PhotoShaders.TONE.contains("texture(uMaskField, vTexCoord)"),
        )
        assertTrue(
            "The local adaptation base has to read the field with the image coordinate",
            PhotoShaders.LOCAL_BASE.contains("texture(uMaskField, vTexCoord)"),
        )
        assertTrue(
            "The composite has to read the field at \"source\", which is the image coordinate",
            composite.contains("texture(uMaskField, source)"),
        )
        assertTrue(
            "The composite must NOT read the field at \"screen\": that is the frame's space, not the image's",
            !composite.contains("texture(uMaskField, screen)"),
        )
    }

    /**
     * Without this, local adaptation decides what is a highlight and what is a shadow from an image
     * that never existed — the one with the global exposure where the mask put another.
     */
    @Test
    fun theLocalBaseSeesTheSameLocalExposureAsTheToneStage() {
        listOf("uMaskExposure", "uMaskTemperature", "uMaskTint", "uMaskWhites", "uMaskBlacks").forEach { name ->
            assertTrue(
                "The local adaptation base should add \"$name\", as the tonal step does",
                PhotoShaders.LOCAL_BASE.contains("dot(w, $name)"),
            )
        }
    }

    /** The mask shapes are a transcription of [PhotoEffects]; the floors are the ones from there. */
    @Test
    fun maskFalloffConstantsMatchTheKotlinImplementation() {
        val field = PhotoShaders.MASK_FIELD
        listOf(
            "max(b.x, ${PhotoEffects.MASK_MIN_WIDTH}) * 0.5" to "the linear gradient width floor",
            "clamp(b.z / 100.0, ${PhotoEffects.MASK_MIN_FEATHER}, 1.0)" to "the radial transition's floor",
            "2.0 + clamp(b.w / 100.0, 0.0, 1.0) * 6.0" to "the Minkowski norm, the same as the vignette's",
            "vec2 local = vec2(delta.x * a.z + delta.y * a.w, -delta.x * a.w + delta.y * a.z)"
                to "the component's rotation with the cosine and sine coming from Kotlin",
        ).forEach { (fragment, what) ->
            assertTrue(
                "The field shader should contain $what — \"$fragment\". " +
                    "If it changed in PhotoEffects, change it here too.",
                field.contains(fragment),
            )
        }
    }

    /**
     * The shape and operation codes travel as integers. Reordering either `enum` in `core:model` would
     * not give a compile error — it would give radials drawn as gradients.
     */
    @Test
    fun theMaskShapeAndOperationCodesMatchTheModelOrdinals() {
        assertEquals("LINEAR has to be zero, which is what the shader compares", 0, MaskShape.LINEAR.ordinal)
        assertEquals(1, MaskShape.RADIAL.ordinal)
        assertEquals(0, MaskOp.ADD.ordinal)
        assertEquals("SUBTRACT has to be one", 1, MaskOp.SUBTRACT.ordinal)
        assertEquals("INTERSECT has to be two", 2, MaskOp.INTERSECT.ordinal)
        assertTrue(
            "The shader compares the shape with zero for the linear",
            PhotoShaders.MASK_FIELD.contains("uComponentShape[index] == 0"),
        )
        assertTrue(
            "The shader treats code one as subtraction",
            PhotoShaders.MASK_FIELD.contains("if (op == 1) accumulated = accumulated * (1.0 - weight)"),
        )
        assertTrue(
            "The shader treats code two as intersection",
            PhotoShaders.MASK_FIELD.contains("else if (op == 2) accumulated = accumulated * weight"),
        )
    }

    /**
     * Masks add up in parameter space, and that is what makes them predictable when they overlap.
     * Swapping the sum for a mix of outputs would give an order-dependent result.
     */
    @Test
    fun theMaskAccumulationIsASumOfWeightedDeltas() {
        val tone = PhotoShaders.TONE
        listOf(
            "clamp(uExposure + dot(w, uMaskExposure), ${MIN_LOCAL_EXPOSURE}, ${MAX_LOCAL_EXPOSURE})"
                to "the local exposure, limited like the slider",
            "uContrast + dot(w, uMaskContrast)" to "o contraste local",
            "uHighlights + dot(w, uMaskHighlights)" to "the local highlights",
            "uShadows + dot(w, uMaskShadows)" to "the local shadows",
            "uSaturation + dot(w, uMaskSaturation)" to "the local saturation",
            "uVibrance + dot(w, uMaskVibrance)" to "the local vibrance",
        ).forEach { (fragment, what) ->
            assertTrue(
                "The tonal step should contain $what — \"$fragment\"",
                tone.contains(fragment),
            )
        }
        listOf("uMaskTexture", "uMaskClarity", "uMaskDehaze").forEach { name ->
            assertTrue(
                "The composite should add \"$name\" to step 9's global adjustment",
                composite.contains("dot(w, $name)"),
            )
        }
    }

    /**
     * The red overlay is a visual aid and not a pipeline step: it comes out of the field, to show
     * exactly what the render sees, but it cannot take part in anything the export honours.
     */
    @Test
    fun theMaskOverlayIsDrawnFromTheFieldAndNotFromTheParameters() {
        assertTrue(composite.contains("if (uMaskOverlay >= 0)"))
        assertTrue(composite.contains("dot(w, pick) * 0.45"))
        assertTrue(
            "The overlay cannot go into RenderParameters",
            RenderParameters::class.java.declaredFields.none { it.name.contains("overlay", ignoreCase = true) },
        )
    }

    private fun assertContains(fragment: String, what: String) {
        assertTrue(
            "The shader should contain $what — \"$fragment\". If it changed in PhotoEffects, change it here too.",
            composite.contains(fragment),
        )
    }
}
