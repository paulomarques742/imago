package eu.studio742.imago.core.composition

/**
 * The type families the app ships with.
 *
 * They are bundled files, and not system fonts, because a composition has to come out the same on
 * every device where it is opened: the name of a system font that does not exist on another device
 * silently falls back to the default font, and the drawing changes without anyone touching it.
 *
 * [CompositionFont.id] is what is saved — in `CompositionElement.Text.fontFamily` and in the
 * `BrandKit` fonts — and cannot change once published. The files are in
 * `feature/composer/src/commonMain/composeResources/font/`, with the licences (all OFL) in
 * `files/licences/`.
 */
object CompositionFonts {
    val Inter = CompositionFont("inter", "Inter", FontCategory.SANS, 100..900)
    val Montserrat = CompositionFont("montserrat", "Montserrat", FontCategory.SANS, 100..900)
    val PlayfairDisplay = CompositionFont("playfair-display", "Playfair Display", FontCategory.SERIF, 400..900)
    val Lora = CompositionFont("lora", "Lora", FontCategory.SERIF, 400..700)
    val Oswald = CompositionFont("oswald", "Oswald", FontCategory.DISPLAY, 200..700)
    val BebasNeue = CompositionFont("bebas-neue", "Bebas Neue", FontCategory.DISPLAY, 400..400)
    val DancingScript = CompositionFont("dancing-script", "Dancing Script", FontCategory.SCRIPT, 400..700)
    val GreatVibes = CompositionFont("great-vibes", "Great Vibes", FontCategory.SCRIPT, 400..400)
    val JetBrainsMono = CompositionFont("jetbrains-mono", "JetBrains Mono", FontCategory.MONO, 100..800)

    /** In the order they appear to whoever chooses: by category, and within it the most versatile first. */
    val all: List<CompositionFont> = listOf(
        Inter, Montserrat, PlayfairDisplay, Lora, Oswald, BebasNeue, DancingScript, GreatVibes, JetBrainsMono,
    )

    val Default: CompositionFont = Inter

    /**
     * The family of a saved name. It accepts [CompositionFont.id], the displayed name, and the three
     * generic names from before the catalogue — `Sans`, `Serif`, `Mono` — that were saved in old
     * compositions and kits. An unknown name (a font typed by hand in the dialog that existed before
     * the brand editor) falls back to [Default], as it already did when exporting.
     */
    fun resolve(name: String?): CompositionFont {
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) return Default
        all.firstOrNull { it.id == wanted }?.let { return it }
        all.firstOrNull { it.displayName.equals(wanted, ignoreCase = true) }?.let { return it }
        return when (wanted.lowercase()) {
            "sans", "sans-serif" -> Inter
            "serif" -> Lora
            "mono", "monospace" -> JetBrainsMono
            else -> Default
        }
    }
}

enum class FontCategory(val label: String) {
    SANS("Sans serif"),
    SERIF("Serif"),
    DISPLAY("Display"),
    SCRIPT("Script"),
    MONO("Monospace"),
}

/**
 * A family in the catalogue.
 *
 * [weights] is the file's `wght` axis: variable families have a range, static ones a single value.
 * Whoever draws always asks for the weight explicitly — Montserrat, for example, has Thin (100) as
 * the file's default weight, and without asking for another everything would come out hairline.
 */
data class CompositionFont(
    val id: String,
    val displayName: String,
    val category: FontCategory,
    val weights: IntRange,
) {
    val isVariable: Boolean get() = weights.first != weights.last

    /** The requested weight, brought within what the file has. */
    fun weight(requested: Int): Int = requested.coerceIn(weights)
}
