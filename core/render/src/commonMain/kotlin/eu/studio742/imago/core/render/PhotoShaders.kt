package eu.studio742.imago.core.render

/**
 * GLSL source of the preview pipeline.
 *
 * The maths blocks of steps 9, 12 and 13 are literal transcriptions of [PhotoEffects]. Whenever one
 * of them changes, the other has to change with it — `PhotoEffectsParityTest` exists so that a
 * slip shows up as a red test and not as an export different from the preview.
 */
internal object PhotoShaders {
    /**
     * The quad every pass draws, as `x, y, u, v`.
     *
     * **The texture coordinate follows NDC, without flipping.** Each intermediate pass reads one
     * texture and writes to another, and a flipped coordinate makes every pass flip the image: with an
     * odd number of passes the photo comes out upside down, and the pyramid textures — which go
     * through a different number of passes — stop being aligned with the `stage`. The flip the
     * bitmap's origin requires is done only once, when writing to the screen, in [COMPOSITE].
     */
    val QUAD = floatArrayOf(
        -1f, -1f, 0f, 0f,
        1f, -1f, 1f, 0f,
        -1f, 1f, 0f, 1f,
        1f, 1f, 1f, 1f,
    )

    const val VERTEX = """#version 300 es
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
    """

    /**
     * Space conversions and steps 1 to 7, shared by [TONE] and [LOCAL_BASE].
     *
     * They are in a single constant and not copied into both because the local adaptation mask is
     * only of any use if it says exactly what step 8 sees: if the two arithmetics diverged, the mask
     * would describe an image that never existed.
     */
    /**
     * The mask shapes, a literal transcription of `PhotoEffects.maskLinearWeight`, `maskRadialWeight`
     * and `combineMaskComponent`.
     *
     * The angle arrives already as cosine and sine in `uComponentA.zw`: it takes the trigonometry out
     * of the loop and, more importantly, guarantees that this side and Kotlin's start from exactly
     * the same two numbers instead of each doing its own calculation.
     *
     * Dynamic indexing of uniform arrays — which elsewhere in the pipeline would be expensive — is not
     * here: this pass runs at one eighth of the resolution, a sixty-fourth of the texels. The hot path
     * is the tonal step, and there is no loop there, just a dot product.
     */
    private const val MASK_SHAPES = """
        float componentWeight(int index, vec2 point) {
            vec4 a = uComponentA[index];
            vec4 b = uComponentB[index];
            vec2 delta = vec2((point.x - a.x) * uImageAspect, point.y - a.y);
            vec2 local = vec2(delta.x * a.z + delta.y * a.w, -delta.x * a.w + delta.y * a.z);
            float weight;
            if (uComponentShape[index] == 0) {
                float halfWidth = max(b.x, 0.004) * 0.5;
                weight = smoothstep(-halfWidth, halfWidth, local.y);
            } else {
                float normalisedX = abs(local.x) / max(b.x, 0.0001);
                float normalisedY = abs(local.y) / max(b.y, 0.0001);
                float exponent = 2.0 + clamp(b.w / 100.0, 0.0, 1.0) * 6.0;
                float distance = pow(pow(normalisedX, exponent) + pow(normalisedY, exponent), 1.0 / exponent);
                float inner = 1.0 - clamp(b.z / 100.0, 0.02, 1.0);
                weight = 1.0 - smoothstep(inner, 1.0, distance);
            }
            return uComponentInverted[index] == 1 ? 1.0 - weight : weight;
        }

        // The first component seeds the accumulator instead of combining with it: subtracting or
        // intersecting against nothing would always give zero.
        float maskWeight(int mask, vec2 point) {
            int start = uComponentStart[mask];
            int count = uComponentCount[mask];
            float accumulated = 0.0;
            for (int index = 0; index < count; index++) {
                float weight = componentWeight(start + index, point);
                if (index == 0) {
                    accumulated = weight;
                } else {
                    int op = uComponentOp[start + index];
                    if (op == 1) accumulated = accumulated * (1.0 - weight);
                    else if (op == 2) accumulated = accumulated * weight;
                    else accumulated = max(accumulated, weight);
                }
            }
            return accumulated;
        }
    """

    /**
     * The weight of each mask at each point, one channel per mask, at one eighth of the resolution.
     *
     * The field is built in **image** coordinates, which is why geometry never comes near it: the
     * `stage` lives there too, and the tonal step reads both with the same coordinate. Four masks is
     * not a chosen number — it is how many channels an RGBA texture has, and it is what lets the tonal
     * step apply them all with one dot product per adjustment.
     */
    const val MASK_FIELD = """#version 300 es
        precision highp float;
        uniform float uImageAspect;
        uniform int uMaskCount;
        uniform vec4 uMaskInverted;
        uniform int uComponentStart[4];
        uniform int uComponentCount[4];
        uniform int uComponentShape[8];
        uniform int uComponentOp[8];
        uniform int uComponentInverted[8];
        uniform vec4 uComponentA[8];
        uniform vec4 uComponentB[8];
        in vec2 vTexCoord;
        out vec4 fragColor;
""" + MASK_SHAPES + """
        float channelWeight(int mask, vec2 point, float inverted) {
            float value = maskWeight(mask, point);
            return inverted > 0.5 ? 1.0 - value : value;
        }

        // The four channels are written one by one, and not by a loop with `weights[mask]`.
        // Dynamically indexing a vector on the left-hand side is legal in ES 3.00 but it is the kind
        // of construct some drivers handle badly, and here it buys nothing: it is four lines.
        void main() {
            vec4 weights = vec4(0.0);
            if (uMaskCount > 0) weights.x = channelWeight(0, vTexCoord, uMaskInverted.x);
            if (uMaskCount > 1) weights.y = channelWeight(1, vTexCoord, uMaskInverted.y);
            if (uMaskCount > 2) weights.z = channelWeight(2, vTexCoord, uMaskInverted.z);
            if (uMaskCount > 3) weights.w = channelWeight(3, vTexCoord, uMaskInverted.w);
            fragColor = weights;
        }
    """

    /**
     * The mask weights and the five adjustments [BASE_TONE] consumes.
     *
     * The field is sampled with the same coordinate the image is sampled with, and not the screen's:
     * masks are stuck to the content, not to the framing.
     */
    private const val MASK_BASE_UNIFORMS = """
        uniform sampler2D uMaskField;
        uniform int uMaskEnabled;
        uniform vec4 uMaskTemperature;
        uniform vec4 uMaskTint;
        uniform vec4 uMaskExposure;
        uniform vec4 uMaskWhites;
        uniform vec4 uMaskBlacks;
    """

    /** Os restantes ajustes locais do passo tonal. */
    private const val MASK_TONE_UNIFORMS = """
        uniform vec4 uMaskContrast;
        uniform vec4 uMaskHighlights;
        uniform vec4 uMaskShadows;
        uniform vec4 uMaskVibrance;
        uniform vec4 uMaskSaturation;
    """

    private const val BASE_TONE = """
        vec3 toLinear(vec3 c) {
            vec3 low = c / 12.92;
            vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
            return mix(low, high, step(vec3(0.04045), c));
        }

        vec3 toSrgb(vec3 c) {
            c = max(c, vec3(0.0));
            vec3 low = c * 12.92;
            vec3 high = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
            return mix(low, high, step(vec3(0.0031308), c));
        }

        float luminance(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        vec3 applyBaseTone(vec3 srgb, float temperature, float tint, float exposure, float whites, float blacks) {
            vec3 linear = toLinear(srgb);
            linear *= vec3(1.0 + 0.20 * temperature, 1.0 + 0.05 * tint, 1.0 - 0.20 * temperature);
            linear *= vec3(1.0 + 0.08 * tint, 1.0 - 0.10 * tint, 1.0 + 0.08 * tint);
            linear *= exp2(exposure);
            float l = luminance(linear);
            linear += blacks * (1.0 - smoothstep(0.0, 0.35, l)) * 0.18;
            linear += whites * smoothstep(0.55, 1.0, l) * 0.22;
            return toSrgb(linear);
        }
    """

    /**
     * Highlights and shadows, a literal transcription of `locallyAdaptedHighlights` and
     * `locallyAdaptedShadows`. The reason for each factor is in the KDoc there; here it is enough that
     * the constants and the shape match.
     */
    private const val SELECTIVE_TONE = """
        float selectiveHighlightGain(float amount) {
            float a = clamp(amount, -1.0, 1.0);
            return a * (a < 0.0 ? 1.8 : 1.3);
        }

        float locallyAdaptedHighlights(float value, float base, float amount) {
            float pixel = clamp(value, 0.0, 1.0);
            if (pixel <= 0.35) return pixel;
            float reference = base < 0.0 ? pixel : clamp(base, 0.0, 1.0);
            float t = (pixel - 0.35) / 0.65;
            float band = clamp((reference - 0.35) / 0.65, 0.0, 1.0);
            // The mix's 0.35 and the pivot's are a coincidence: LOCAL_ADAPTATION_GLOBAL_SHARE and
            // HIGHLIGHT_PIVOT are separate constants there, and changing one is not changing the other.
            float local = band + 0.35 * (t - band);
            float flow = exp(selectiveHighlightGain(amount) * local);
            return 0.35 + 0.65 * (t * flow / (1.0 - t + t * flow));
        }

        float locallyAdaptedShadows(float value, float base, float amount) {
            float pixel = clamp(value, 0.0, 1.0);
            float mirrored = base < 0.0 ? -1.0 : 1.0 - clamp(base, 0.0, 1.0);
            return 1.0 - locallyAdaptedHighlights(1.0 - pixel, mirrored, -amount);
        }
    """

    /**
     * Local adaptation mask: the image's luminance as it enters step 8, at half resolution.
     *
     * The 2×2 average is done with the four explicit samples, and not left to the bilinear filter,
     * because the CPU counterpart does the same average over the same four pixels — it is the only
     * point on the mask's path where the two implementations could pick different neighbourhoods.
     * Steps 1 to 7 come after the average, as there.
     */
    const val LOCAL_BASE = """#version 300 es
        precision highp float;
        uniform sampler2D uTexture;
        uniform vec2 uSourceTexel;
        uniform float uTemperature;
        uniform float uTint;
        uniform float uExposure;
        uniform float uWhites;
        uniform float uBlacks;
        in vec2 vTexCoord;
        out vec4 fragColor;
""" + MASK_BASE_UNIFORMS + BASE_TONE + """
        void main() {
            vec2 offset = uSourceTexel * 0.5;
            vec3 averaged = 0.25 * (
                texture(uTexture, vTexCoord + vec2(-offset.x, -offset.y)).rgb +
                texture(uTexture, vTexCoord + vec2( offset.x, -offset.y)).rgb +
                texture(uTexture, vTexCoord + vec2(-offset.x,  offset.y)).rgb +
                texture(uTexture, vTexCoord + vec2( offset.x,  offset.y)).rgb
            );
            // This layer MUST see the local exposure. Built with the global one, it would describe an
            // image that is not the one entering step 8, and the two curves would decide what is a
            // highlight and what is a shadow from a neighbourhood that never existed.
            vec4 w = uMaskEnabled == 1 ? texture(uMaskField, vTexCoord) : vec4(0.0);
            float temperature = uTemperature + dot(w, uMaskTemperature);
            float tint = uTint + dot(w, uMaskTint);
            float exposure = clamp(uExposure + dot(w, uMaskExposure), -5.0, 5.0);
            float whites = uWhites + dot(w, uMaskWhites);
            float blacks = uBlacks + dot(w, uMaskBlacks);
            vec3 based = applyBaseTone(averaged, temperature, tint, exposure, whites, blacks);
            fragColor = vec4(vec3(luminance(based)), 1.0);
        }
    """

    /** Steps 1 to 8: from the source texture to perceptual space, without neighbourhood or geometry. */
    const val TONE = """#version 300 es
        precision highp float;
        uniform sampler2D uTexture;
        uniform sampler2D uLocalBlur;
        uniform int uLocalEnabled;
        uniform float uTemperature;
        uniform float uTint;
        uniform float uExposure;
        uniform float uContrast;
        uniform float uHighlights;
        uniform float uShadows;
        uniform float uWhites;
        uniform float uBlacks;
        uniform float uToneCurve[256];
        uniform vec3 uHslBands[8];
        uniform float uVibrance;
        uniform float uSaturation;
        in vec2 vTexCoord;
        out vec4 fragColor;
""" + MASK_BASE_UNIFORMS + MASK_TONE_UNIFORMS + BASE_TONE + SELECTIVE_TONE + """
        float curveSample(float value) {
            float position = clamp(value, 0.0, 1.0) * 255.0;
            int left = int(floor(position));
            int right = min(left + 1, 255);
            return mix(uToneCurve[left], uToneCurve[right], fract(position));
        }

        vec3 rgbToHsv(vec3 c) {
            vec4 k = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, k.wz), vec4(c.gb, k.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        vec3 hsvToRgb(vec3 c) {
            vec3 p = abs(fract(c.xxx + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
            return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
        }

        float hueDistance(float a, float b) {
            float distance = abs(a - b);
            return min(distance, 1.0 - distance);
        }

        vec3 remapLuminance(vec3 color, float target) {
            float current = luminance(color);
            if (current <= 0.00001) return color;
            return color * (target / current);
        }

        float applyMidtoneContrast(float value, float amount) {
            float strength = clamp(amount, -1.0, 1.0) * 0.85;
            return clamp(value + strength * value * (1.0 - value) * (2.0 * value - 1.0), 0.0, 1.0);
        }

        vec3 applyHslBands(vec3 color) {
            const float centers[8] = float[8](0.0, 0.083333, 0.166667, 0.333333, 0.5, 0.666667, 0.75, 0.833333);
            vec3 hsv = rgbToHsv(clamp(color, 0.0, 1.0));
            vec3 adjustment = vec3(0.0);
            float totalWeight = 0.0;
            for (int index = 0; index < 8; index++) {
                float weight = max(1.0 - hueDistance(hsv.x, centers[index]) / 0.13, 0.0);
                adjustment += uHslBands[index] * weight;
                totalWeight += weight;
            }
            if (totalWeight > 0.0) adjustment /= totalWeight;
            hsv.x = fract(hsv.x + adjustment.x / 12.0 + 1.0);
            hsv.y = clamp(hsv.y * (1.0 + adjustment.y), 0.0, 1.0);
            hsv.z = clamp(hsv.z + adjustment.z * 0.5, 0.0, 1.0);
            return hsvToRgb(hsv);
        }

        void main() {
            vec4 source = texture(uTexture, vTexCoord);

            // The hot path of the masks: one sample and one dot product per adjustment. No loops, no
            // dynamic indexing, no trigonometry — all that stayed in the field pass, which runs at one
            // eighth. Adding in parameter space is what makes two overlapping masks of plus one EV
            // each give two, which is what whoever drew them expects; mixing outputs would give an
            // order-dependent result.
            vec4 w = uMaskEnabled == 1 ? texture(uMaskField, vTexCoord) : vec4(0.0);
            float exposure = clamp(uExposure + dot(w, uMaskExposure), -5.0, 5.0);
            float contrast = uContrast + dot(w, uMaskContrast);
            float highlights = uHighlights + dot(w, uMaskHighlights);
            float shadows = uShadows + dot(w, uMaskShadows);
            float saturation = uSaturation + dot(w, uMaskSaturation);
            float vibrance = uVibrance + dot(w, uMaskVibrance);

            float temperature = uTemperature + dot(w, uMaskTemperature);
            float tint = uTint + dot(w, uMaskTint);
            float whites = uWhites + dot(w, uMaskWhites);
            float blacks = uBlacks + dot(w, uMaskBlacks);
            vec3 color = applyBaseTone(source.rgb, temperature, tint, exposure, whites, blacks);

            // Negative says "no mask", and in that case the curves fall back to the global behaviour.
            float base = uLocalEnabled == 1 ? texture(uLocalBlur, vTexCoord).r : -1.0;

            float tonalLuminance = clamp(luminance(color), 0.0, 1.0);
            color = remapLuminance(color, locallyAdaptedShadows(tonalLuminance, base, shadows));
            tonalLuminance = clamp(luminance(color), 0.0, 1.0);
            color = remapLuminance(color, locallyAdaptedHighlights(tonalLuminance, base, highlights));
            tonalLuminance = clamp(luminance(color), 0.0, 1.0);
            color = remapLuminance(color, applyMidtoneContrast(tonalLuminance, contrast));
            color = vec3(curveSample(color.r), curveSample(color.g), curveSample(color.b));
            color = applyHslBands(color);
            float perceived = luminance(color);
            color = mix(vec3(perceived), color, 1.0 + saturation);
            float chroma = max(color.r, max(color.g, color.b)) - min(color.r, min(color.g, color.b));
            color = mix(vec3(perceived), color, 1.0 + vibrance * (1.0 - clamp(chroma, 0.0, 1.0)));

            fragColor = vec4(clamp(color, 0.0, 1.0), source.a);
        }
    """

    /** Exact 2× reduction, with the four samples at the centres of the source texels. */
    const val DOWNSAMPLE = """#version 300 es
        precision highp float;
        uniform sampler2D uSource;
        uniform vec2 uSourceTexel;
        in vec2 vTexCoord;
        out vec4 fragColor;
        void main() {
            vec2 offset = uSourceTexel * 0.5;
            fragColor = 0.25 * (
                texture(uSource, vTexCoord + vec2(-offset.x, -offset.y)) +
                texture(uSource, vTexCoord + vec2( offset.x, -offset.y)) +
                texture(uSource, vTexCoord + vec2(-offset.x,  offset.y)) +
                texture(uSource, vTexCoord + vec2( offset.x,  offset.y))
            );
        }
    """

    /** Separable half Gaussian. `uStep` already carries the direction and the texel size. */
    const val BLUR = """#version 300 es
        precision highp float;
        uniform sampler2D uSource;
        uniform vec2 uStep;
        uniform int uRadius;
        uniform float uWeights[25];
        in vec2 vTexCoord;
        out vec4 fragColor;
        void main() {
            vec4 sum = texture(uSource, vTexCoord) * uWeights[0];
            for (int index = 1; index <= 24; index++) {
                if (index > uRadius) break;
                vec2 offset = uStep * float(index);
                sum += (texture(uSource, vTexCoord + offset) + texture(uSource, vTexCoord - offset)) * uWeights[index];
            }
            fragColor = sum;
        }
    """

    /**
     * Dehaze statistics: dark channel in `.r` and max channel in `.g`.
     *
     * The minimum is taken over a 3×3 window of the pyramid, which at full resolution covers a
     * neighbourhood of the order of the 15 px in the original *dark channel prior* paper.
     */
    const val STATS = """#version 300 es
        precision highp float;
        uniform sampler2D uSource;
        uniform vec2 uSourceTexel;
        in vec2 vTexCoord;
        out vec4 fragColor;
        void main() {
            float dark = 1.0;
            float bright = 0.0;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec3 c = texture(uSource, vTexCoord + uSourceTexel * vec2(float(x), float(y))).rgb;
                    dark = min(dark, min(c.r, min(c.g, c.b)));
                    bright = max(bright, max(c.r, max(c.g, c.b)));
                }
            }
            fragColor = vec4(dark, bright, 0.0, 1.0);
        }
    """

    /**
     * Final pass: step 9 (clarity, texture, dehaze), step 12 (colour grading), step 13 (vignette,
     * grain) and step 14 (geometry), all in a single draw to the screen.
     */
    const val COMPOSITE = """#version 300 es
        precision highp float;
        // Required, and not decorative: in the ES 3.00 fragment language `int` and `uint` are mediump
        // by default, which the spec only guarantees at 16 bits. The grain hash multiplies by 32-bit
        // constants — at mediump not even the constant fits — and on GPUs that honour mediump the
        // hash collapsed, leaving the preview with no grain at all while the export, which runs on
        // the JVM with 32-bit integers, showed it.
        precision highp int;
        uniform sampler2D uStage;
        uniform sampler2D uBandBlur;
        uniform sampler2D uCoarseBlur;
        uniform sampler2D uDarkBlur;
        uniform sampler2D uStats;
        uniform vec2 uStageTexel;
        uniform float uTexture;
        uniform float uClarity;
        uniform float uDehaze;
        uniform sampler2D uMaskField;
        uniform int uMaskEnabled;
        uniform vec4 uMaskTexture;
        uniform vec4 uMaskClarity;
        uniform vec4 uMaskDehaze;
        // Index of the mask to paint on top, or -1 for none. It is a visual aid of the editor and
        // not a render parameter: it lives outside `RenderParameters` on purpose, because those are
        // what the export honours and what decides whether a recipe is neutral.
        uniform int uMaskOverlay;
        // Step 12: each wheel's chroma with the saturation already applied and the hue converted, the
        // four luminances in a vec4 in the order shadows, midtones, highlights, global, and the two
        // controls raw. See `ColorGradeRange`.
        uniform vec3 uGradeShadows;
        uniform vec3 uGradeMidtones;
        uniform vec3 uGradeHighlights;
        uniform vec3 uGradeGlobal;
        uniform vec4 uGradeLuminance;
        uniform float uGradeBlending;
        uniform float uGradeBalance;
        uniform float uVignetteAmount;
        uniform float uVignetteMidpoint;
        uniform float uVignetteRoundness;
        uniform float uVignetteFeather;
        uniform float uGrainAmount;
        // The cell's side, in IMAGE pixels, and the granularity's amplitude gain. The shader does not
        // derive them from the slider: they are PhotoEffects.grainCellSize and grainSizeGain, so that
        // the preview and the export read the same scale from the same function.
        uniform float uGrainCell;
        uniform float uGrainGain;
        uniform float uGrainRoughness;
        // The dimensions of the already cropped frame, in image pixels. It is what converts the grain
        // cell from image pixels to target pixels, and so what makes grain resolve when zooming in
        // and fade when fitting.
        uniform vec2 uImagePixels;
        uniform vec2 uOutputSize;
        uniform float uAirlightLod;
        uniform vec2 uCropOrigin;
        uniform vec2 uCropSize;
        // Straighten and perspective in one matrix: `FrameGeometry.imageFromView`, computed once on the
        // CPU and uploaded as it is. The shader does not read the sliders, so it cannot read them
        // differently from the export.
        uniform mat3 uImageFromView;
        uniform int uShowOutside;
        uniform float uImageAspect;
        uniform int uRotation;
        uniform int uMirrorH;
        uniform int uMirrorV;
        uniform int uDetailEnabled;
        uniform int uGradingEnabled;
        uniform int uEffectsEnabled;
        in vec2 vTexCoord;
        out vec4 fragColor;

        float luminance(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        vec3 remapLuminance(vec3 color, float target) {
            float current = luminance(color);
            if (current <= 0.00001) return color;
            return clamp(color * (target / current), 0.0, 1.0);
        }

        // This function is what defines the geometry: the two CPU paths (`applyRecipeGeometry` and
        // `FullResolutionExporter.applyGeometry`) compose the same operations in reverse, and
        // `FrameGeometry` transcribes it to Kotlin. As flip and quarter turn do not commute, the
        // order here is not a preference — it is the contract, and `FrameGeometryTest` guards it.
        vec2 geometryCoordinate(vec2 coordinate) {
            vec3 view = vec3((coordinate.x - 0.5) * uImageAspect, coordinate.y - 0.5, 1.0);
            vec3 image = uImageFromView * view;
            // Beyond the virtual camera's horizon: no point of the photo lands here.
            if (image.z <= 0.0) return vec2(-1.0);
            vec2 transformed = vec2(image.x / image.z / uImageAspect + 0.5, image.y / image.z + 0.5);
            if (uMirrorH == 1) transformed.x = 1.0 - transformed.x;
            if (uMirrorV == 1) transformed.y = 1.0 - transformed.y;
            if (uRotation == 1) return vec2(transformed.y, 1.0 - transformed.x);
            if (uRotation == 2) return vec2(1.0 - transformed.x, 1.0 - transformed.y);
            if (uRotation == 3) return vec2(1.0 - transformed.y, transformed.x);
            return transformed;
        }

        // --- passo 9 -------------------------------------------------------------------------

        float softLimit(float value, float limit) {
            return limit * tanh(value / limit);
        }

        float midtoneWeight(float value) {
            float v = clamp(value, 0.0, 1.0);
            return 4.0 * v * (1.0 - v);
        }

        float edgeMask(float band, float coarse) {
            return 1.0 - 0.85 * smoothstep(0.05, 0.24, abs(band - coarse));
        }

        float clarityDelta(float value, float coarse, float amount) {
            if (amount == 0.0) return 0.0;
            float detail = softLimit(value - coarse, 0.32);
            return clamp(amount, -1.0, 1.0) * 1.15 * detail * midtoneWeight(value);
        }

        float textureDelta(float fine, float band, float coarse, float amount) {
            if (amount == 0.0) return 0.0;
            float detail = softLimit(fine - band, 0.18);
            return clamp(amount, -1.0, 1.0) * 1.6 * detail * edgeMask(band, coarse);
        }

        /** 3×3 tent over the luminance, the fine term of the texture band. */
        float fineLuminance(vec2 coordinate) {
            float total = 0.0;
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    float weight = (x == 0 ? 2.0 : 1.0) * (y == 0 ? 2.0 : 1.0);
                    vec2 offset = uStageTexel * vec2(float(x), float(y));
                    total += weight * luminance(texture(uStage, coordinate + offset).rgb);
                }
            }
            return total / 16.0;
        }

        /**
         * Atmospheric light: the maximum of the max channel over sixteen blocks of the image. It
         * replaces the scan of the brightest 0.1% of the dark channel with a mipmap read — the large
         * bright region the *dark channel prior* looks for is precisely the one that survives averaging.
         */
        float estimateAirlight() {
            float airlight = 0.0;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    vec2 coordinate = (vec2(float(x), float(y)) + 0.5) / 4.0;
                    airlight = max(airlight, textureLod(uStats, coordinate, uAirlightLod).g);
                }
            }
            return max(airlight, 0.05);
        }

        float dehazeChannel(float value, float airlight, float transmission, float amount) {
            if (amount > 0.0) {
                float strength = min(amount, 1.0);
                float effective = 1.0 - (1.0 - max(transmission, 0.1)) * strength;
                return clamp((value - airlight) / effective + airlight, 0.0, 1.0);
            }
            float haze = min(-amount, 1.0) * (1.0 - transmission);
            return clamp(value * (1.0 - haze) + airlight * haze, 0.0, 1.0);
        }

        vec3 applyDetail(vec3 color, vec2 coordinate, float amountTexture, float amountClarity, float amountDehaze) {
            float value = luminance(color);
            float band = luminance(texture(uBandBlur, coordinate).rgb);
            float coarse = luminance(texture(uCoarseBlur, coordinate).rgb);
            float fine = amountTexture == 0.0 ? value : fineLuminance(coordinate);
            float target = clamp(
                value + textureDelta(fine, band, coarse, amountTexture) + clarityDelta(value, coarse, amountClarity),
                0.0,
                1.0
            );
            vec3 result = remapLuminance(color, target);
            if (amountDehaze != 0.0) {
                // The atmospheric light stays global even with masks: it is a statistic of the whole
                // scene, not a property of this pixel. Only the intensity is local.
                float airlight = estimateAirlight();
                float dark = texture(uDarkBlur, coordinate).r;
                float transmission = clamp(1.0 - 0.95 * (dark / airlight), 0.0, 1.0);
                result = vec3(
                    dehazeChannel(result.r, airlight, transmission, amountDehaze),
                    dehazeChannel(result.g, airlight, transmission, amountDehaze),
                    dehazeChannel(result.b, airlight, transmission, amountDehaze)
                );
            }
            return result;
        }

        // --- passo 12 ------------------------------------------------------------------------

        float colorGradeWidth(float blending) {
            return 0.40 + 0.60 * clamp(blending / 100.0, 0.0, 1.0);
        }

        float colorGradeMidtone(float balance) {
            return 0.5 - 0.15 * clamp(balance / 100.0, -1.0, 1.0);
        }

        vec3 applyColorGrading(vec3 color) {
            float value = clamp(luminance(color), 0.0, 1.0);
            float width = colorGradeWidth(uGradeBlending);
            float midtone = colorGradeMidtone(uGradeBalance);
            vec3 weight = max(1.0 - abs(value - vec3(0.0, midtone, 1.0)) / width, 0.0);
            float total = weight.x + weight.y + weight.z;
            if (total > 0.0) weight /= total;
            vec3 chroma = weight.x * uGradeShadows + weight.y * uGradeMidtones +
                weight.z * uGradeHighlights + uGradeGlobal;
            vec3 graded = clamp(color + 0.30 * chroma, 0.0, 1.0);
            float offset = dot(vec4(weight, 1.0), uGradeLuminance) / 100.0 * 0.5;
            if (offset == 0.0) return graded;
            return remapLuminance(graded, clamp(value + offset, 0.0, 1.0));
        }

        // --- passo 13 ------------------------------------------------------------------------

        float vignetteFalloff(vec2 coordinate) {
            float round = clamp(uVignetteRoundness / 100.0, -1.0, 1.0);
            float aspect = uOutputSize.x / max(uOutputSize.y, 1.0);
            float safeAspect = aspect > 0.0 ? aspect : 1.0;
            float scaleX = 1.0 + max(round, 0.0) * (max(safeAspect, 1.0) / safeAspect - 1.0);
            float scaleY = 1.0 + max(round, 0.0) * (max(1.0 / safeAspect, 1.0) * safeAspect - 1.0);
            float dx = abs((coordinate.x - 0.5) * 2.0) * scaleX;
            float dy = abs((coordinate.y - 0.5) * 2.0) * scaleY;
            float exponent = 2.0 + max(-round, 0.0) * 6.0;
            float distance = pow(pow(dx, exponent) + pow(dy, exponent), 1.0 / exponent);
            float outer = 0.35 + clamp(uVignetteMidpoint / 100.0, 0.0, 1.0) * 1.10;
            float width = 0.03 + clamp(uVignetteFeather / 100.0, 0.0, 1.0) * 0.85;
            float inner = max(outer - width, 0.0);
            return smoothstep(inner, outer, distance);
        }

        vec3 applyVignette(vec3 color, vec2 coordinate) {
            if (uVignetteAmount == 0.0) return color;
            float strength = clamp(uVignetteAmount / 100.0, -1.0, 1.0) * vignetteFalloff(coordinate);
            vec3 result = strength < 0.0 ? color * (1.0 + strength) : color + (1.0 - color) * strength;
            return clamp(result, 0.0, 1.0);
        }

        float latticeValue(int ix, int iy, int seed) {
            uint hash = uint(ix) * 374761393u + uint(iy) * 668265263u + uint(seed) * 2246822519u;
            hash += hash << 10u;
            hash ^= hash >> 6u;
            hash += hash << 3u;
            hash ^= hash >> 11u;
            hash += hash << 15u;
            return float((hash >> 8u) & 0xFFFFu) / 65536.0;
        }

        float valueNoise(vec2 position, int seed) {
            vec2 cell = floor(position);
            vec2 fraction = position - cell;
            vec2 weight = fraction * fraction * (3.0 - 2.0 * fraction);
            int baseX = int(cell.x);
            int baseY = int(cell.y);
            float topLeft = latticeValue(baseX, baseY, seed);
            float topRight = latticeValue(baseX + 1, baseY, seed);
            float bottomLeft = latticeValue(baseX, baseY + 1, seed);
            float bottomRight = latticeValue(baseX + 1, baseY + 1, seed);
            float top = topLeft + (topRight - topLeft) * weight.x;
            float bottom = bottomLeft + (bottomRight - bottomLeft) * weight.x;
            return top + (bottom - top) * weight.y;
        }

        float grainWeight(float value) {
            float v = clamp(value, 0.0, 1.0);
            return smoothstep(0.0, 0.14, v) * (1.0 - 0.55 * smoothstep(0.72, 1.0, v));
        }

        // MurmurHash3's finaliser, transcribed bit for bit from PhotoEffects.grainHash. All 32 bits
        // come out because the particle takes four draws from them.
        uint grainHash(int ix, int iy) {
            uint hash = uint(ix) * 374761393u + uint(iy) * 668265263u + 0x9e3779b9u;
            hash ^= hash >> 15u;
            hash *= 0x85ebca6bu;
            hash ^= hash >> 13u;
            hash *= 0xc2b2ae35u;
            return hash ^ (hash >> 16u);
        }

        // PhotoEffects.grainNormalization: the closed form of the variance of the sum of particles.
        float grainNormalization(float rough) {
            float spread = 0.30 + 0.25 * rough;
            float variance = 3.14159265 * 0.90 * 0.60 * 0.60
                * (1.0 + spread * spread / 3.0)
                * (1.0 + 0.0914 * rough * rough);
            return 0.289 / sqrt(variance);
        }

        // PhotoEffects.grainField. The position comes in cells, and pixelInCells says how much a
        // target pixel measures in the same unit: it is what spreads and weakens the sub-pixel particle.
        float grainField(vec2 position, float roughness, float pixelInCells) {
            float rough = clamp(roughness / 100.0, 0.0, 1.0);
            float spread = 0.30 + 0.25 * rough;
            float jitter = 0.80 + 0.20 * rough;
            float footprint = 0.75 * pixelInCells;
            // The divisor and the edge's ceiling, GRAIN_EDGE_MAX = 1.0: past it, the amplitude keeps
            // falling but the blur no longer grows, which is where the 3x3 neighbourhood ends.
            float overshoot = max(footprint, 1.0);
            float clump = 1.0 + rough * (valueNoise(position * 0.22, 1) - 0.5) * 1.6;
            int cellX = int(floor(position.x));
            int cellY = int(floor(position.y));
            float sum = 0.0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    uint hash = grainHash(cellX + dx, cellY + dy);
                    vec2 centre = vec2(
                        float(cellX + dx) + 0.5 + (float(hash & 0xFFu) / 255.0 - 0.5) * jitter,
                        float(cellY + dy) + 0.5 + (float((hash >> 8u) & 0xFFu) / 255.0 - 0.5) * jitter
                    );
                    float radius = 0.60 * (1.0 + (float((hash >> 16u) & 0xFFu) / 255.0 - 0.5) * 2.0 * spread);
                    radius = min(radius, 0.80);
                    float edge = min(max(0.20 * radius, footprint), 1.0);
                    float peak = min(1.0, (radius / edge) * (radius / edge));
                    float coverage = 1.0 - smoothstep(max(radius - edge, 0.0), radius + edge, distance(position, centre));
                    float polarity = ((hash >> 24u) & 1u) == 0u ? -1.0 : 1.0;
                    sum += polarity * peak * coverage;
                }
            }
            return sum * clump * grainNormalization(rough) / (overshoot * overshoot);
        }

        vec3 applyGrain(vec3 color, vec2 coordinate) {
            if (uGrainAmount == 0.0) return color;
            // The cell, measured in target pixels. The x axis is enough for both: the viewport keeps
            // the frame's aspect ratio, and using a single scale per axis is what keeps the grain round.
            float cellStep = max(uGrainCell, 0.0001) * (uOutputSize.x / max(uImagePixels.x, 1.0));
            vec2 position = coordinate * uOutputSize / cellStep;
            float noise = grainField(position, uGrainRoughness, 1.0 / cellStep);
            float strength = clamp(uGrainAmount / 100.0, 0.0, 1.0) * 0.34 * uGrainGain;
            return clamp(color + noise * strength * grainWeight(luminance(color)), 0.0, 1.0);
        }

        void main() {
            // The only flip in the pipeline. The bitmap arrives with its first row at v = 0, and the
            // screen has its top at NDC +1; it is here, when writing to the screen, that the two
            // conventions meet. `screen` has its origin at the top-left corner, which is the space in
            // which geometry, the vignette and grain were always defined.
            vec2 screen = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
            vec2 cropped = uCropOrigin + screen * uCropSize;
            vec2 source = geometryCoordinate(cropped);
            // What the perspective leaves uncovered is white, and stays white: it leaves before any
            // adjustment could turn it grey. Only with the crop unconstrained can it be reached.
            if (uShowOutside == 1 && (any(lessThan(source, vec2(0.0))) || any(greaterThan(source, vec2(1.0))))) {
                fragColor = vec4(1.0);
                return;
            }
            vec4 staged = texture(uStage, source);
            vec3 color = staged.rgb;
            // The field is read at `source` and never at `screen`. Masks are in image coordinates, and
            // sampling them in screen space was silently right as long as there was no crop or
            // rotation — and silently wrong from then on.
            vec4 w = uMaskEnabled == 1 ? texture(uMaskField, source) : vec4(0.0);
            if (uDetailEnabled == 1) {
                color = applyDetail(
                    color,
                    source,
                    uTexture + dot(w, uMaskTexture),
                    uClarity + dot(w, uMaskClarity),
                    uDehaze + dot(w, uMaskDehaze)
                );
            }
            // Step 12 has no neighbourhood or coordinate: it is per pixel, and sits between detail and
            // the vignette, where the pipeline places it.
            if (uGradingEnabled == 1) {
                color = applyColorGrading(color);
            }
            if (uEffectsEnabled == 1) {
                color = applyVignette(color, screen);
                color = applyGrain(color, screen);
            }
            // The visual aid comes from here and not from an overlay drawn in Compose, because that way
            // it shows exactly what the pipeline sees — flipping and composition of components
            // included. Drawing it from the parameters would be a second interpretation of the mask,
            // and the two would disagree on the first composed case.
            if (uMaskOverlay >= 0) {
                vec4 pick = vec4(
                    uMaskOverlay == 0 ? 1.0 : 0.0,
                    uMaskOverlay == 1 ? 1.0 : 0.0,
                    uMaskOverlay == 2 ? 1.0 : 0.0,
                    uMaskOverlay == 3 ? 1.0 : 0.0
                );
                color = mix(color, vec3(0.90, 0.20, 0.25), dot(w, pick) * 0.45);
            }
            fragColor = vec4(color, staged.a);
        }
    """
}
