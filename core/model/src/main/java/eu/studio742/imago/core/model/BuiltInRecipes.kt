package eu.studio742.imago.core.model

/**
 * The recipes that come with the app.
 *
 * They are `EditRecipe`s like any other — what sets them apart is not being in Room and not being
 * deletable. They only use parameters the pipeline can process today; none depends on masks or lens
 * correction.
 *
 * The values are deliberately moderate. A preset is there to give a starting point, not to decide
 * the photo for the user — whoever wants more pushes the sliders next.
 *
 * The names and descriptions here are data, in the base language; the interface shows them
 * translated by `id`.
 */
data class BuiltInRecipe(
    val id: String,
    val name: String,
    val description: String,
    val category: RecipeCategory,
    val recipe: EditRecipe,
)

enum class RecipeCategory { LANDSCAPE, PORTRAIT, URBAN, MONOCHROME, CINEMATIC }

private const val BUILT_IN_ASSET_ID = "built-in"

/**
 * An `EditRecipe` without an associated photo.
 *
 * `assetId` and the dates only gain meaning when the recipe is applied — `rebasedOnto` swaps the
 * three for the target photo.
 */
private fun preset(
    whiteBalance: WhiteBalance = WhiteBalance(),
    tone: Tone = Tone(),
    presence: Presence = Presence(),
    hsl: Hsl = Hsl(),
    effects: Effects = Effects(),
    toneCurve: ToneCurve = ToneCurve(),
) = EditRecipe(
    assetId = BUILT_IN_ASSET_ID,
    originalChecksum = "",
    createdAt = "",
    updatedAt = "",
    whiteBalance = whiteBalance,
    tone = tone,
    presence = presence,
    hsl = hsl,
    effects = effects,
    toneCurve = toneCurve,
)

/**
 * A gentle S curve, the contrast gesture almost every preset shares.
 *
 * [amount] is how far the points move from the diagonal, in units of 0–255.
 */
private fun sCurve(amount: Int): ToneCurve = ToneCurve(
    rgb = listOf(
        CurvePoint(0, 0),
        CurvePoint(64, (64 - amount).coerceAtLeast(0)),
        CurvePoint(192, (192 + amount).coerceAtMost(255)),
        CurvePoint(255, 255),
    ),
)

val BUILT_IN_RECIPES: List<BuiltInRecipe> = listOf(
    BuiltInRecipe(
        id = "built-in-golden-hour",
        name = "Golden Hour",
        description = "Warm and radiant",
        category = RecipeCategory.LANDSCAPE,
        recipe = preset(
            whiteBalance = WhiteBalance(temp = 26f, tint = 6f),
            tone = Tone(exposure = 0.18f, contrast = 12f, highlights = -22f, shadows = 20f, blacks = -8f),
            presence = Presence(clarity = 8f, vibrance = 22f),
            toneCurve = sCurve(8),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-dramatic",
        name = "Dramatic",
        description = "High contrast",
        category = RecipeCategory.LANDSCAPE,
        recipe = preset(
            tone = Tone(contrast = 34f, highlights = -42f, shadows = -14f, whites = 16f, blacks = -30f),
            presence = Presence(clarity = 26f, texture = 14f, dehaze = 12f, vibrance = 10f),
            effects = Effects(vignetteAmount = -22f, vignetteMidpoint = 46f, vignetteFeather = 60f),
            toneCurve = sCurve(18),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-pastel",
        name = "Pastel",
        description = "Soft and delicate",
        category = RecipeCategory.PORTRAIT,
        recipe = preset(
            whiteBalance = WhiteBalance(temp = -6f, tint = 10f),
            // Lifting the blacks without touching the highlights is what gives the washed look of light matte.
            tone = Tone(exposure = 0.12f, contrast = -16f, highlights = -10f, shadows = 24f, blacks = 26f),
            presence = Presence(clarity = -12f, saturation = -18f, vibrance = 8f),
            toneCurve = ToneCurve(
                rgb = listOf(CurvePoint(0, 22), CurvePoint(128, 134), CurvePoint(255, 246)),
            ),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-monochrome",
        name = "Black & White",
        description = "Timeless",
        category = RecipeCategory.MONOCHROME,
        recipe = preset(
            tone = Tone(contrast = 22f, highlights = -18f, shadows = 14f, whites = 12f, blacks = -18f),
            // Saturation at -100 is what makes the photo monochrome; the rest is the contrast that
            // makes up for the colour information that was lost.
            presence = Presence(saturation = -100f, clarity = 18f, texture = 10f),
            toneCurve = sCurve(14),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-cinematic",
        name = "Cinematic",
        description = "Teal & Orange",
        category = RecipeCategory.CINEMATIC,
        recipe = preset(
            whiteBalance = WhiteBalance(temp = 8f, tint = -6f),
            tone = Tone(contrast = 16f, highlights = -26f, shadows = 18f, blacks = -22f),
            // Teal & orange is done in the bands: the blues and the water pulled towards cyan, the
            // skin's oranges kept warm.
            hsl = Hsl(
                orange = HslBand(hue = -6f, saturation = 14f, luminance = 6f),
                yellow = HslBand(hue = -14f, saturation = -10f),
                aqua = HslBand(hue = 10f, saturation = 24f, luminance = -8f),
                blue = HslBand(hue = 12f, saturation = 20f, luminance = -12f),
            ),
            presence = Presence(vibrance = 12f, clarity = 10f),
            effects = Effects(vignetteAmount = -16f, vignetteFeather = 66f),
            toneCurve = sCurve(10),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-natural",
        name = "Natural",
        description = "Balanced",
        category = RecipeCategory.LANDSCAPE,
        recipe = preset(
            tone = Tone(contrast = 8f, highlights = -14f, shadows = 12f, whites = 6f, blacks = -6f),
            presence = Presence(clarity = 6f, vibrance = 12f),
            toneCurve = sCurve(5),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-matte",
        name = "Matte",
        description = "Soft colors",
        category = RecipeCategory.URBAN,
        recipe = preset(
            tone = Tone(contrast = -12f, highlights = -18f, shadows = 10f, blacks = 32f),
            presence = Presence(saturation = -14f, clarity = -6f),
            effects = Effects(grainAmount = 18f, grainSize = 30f, grainRoughness = 55f),
            // Lifted blacks and restrained highlights: the end of the scale compressed on both sides.
            toneCurve = ToneCurve(
                rgb = listOf(CurvePoint(0, 28), CurvePoint(128, 130), CurvePoint(255, 238)),
            ),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-vibrant",
        name = "Vibrant",
        description = "Intense colors",
        category = RecipeCategory.URBAN,
        recipe = preset(
            tone = Tone(exposure = 0.1f, contrast = 20f, highlights = -20f, shadows = 16f, blacks = -14f),
            presence = Presence(vibrance = 38f, saturation = 12f, clarity = 14f, texture = 8f),
            toneCurve = sCurve(12),
        ),
    ),
    BuiltInRecipe(
        id = "built-in-cold",
        name = "Cool",
        description = "Cool tones",
        category = RecipeCategory.CINEMATIC,
        recipe = preset(
            whiteBalance = WhiteBalance(temp = -30f, tint = -8f),
            tone = Tone(contrast = 18f, highlights = -24f, shadows = 8f, blacks = -20f),
            hsl = Hsl(
                aqua = HslBand(saturation = 16f, luminance = -6f),
                blue = HslBand(saturation = 22f, luminance = -10f),
            ),
            presence = Presence(clarity = 12f, vibrance = 6f),
            toneCurve = sCurve(10),
        ),
    ),
)

fun RecipeCategory.label(): String = when (this) {
    RecipeCategory.LANDSCAPE -> "Landscape"
    RecipeCategory.PORTRAIT -> "Portrait"
    RecipeCategory.URBAN -> "Urban"
    RecipeCategory.MONOCHROME -> "Black and white"
    RecipeCategory.CINEMATIC -> "Cinematic"
}
