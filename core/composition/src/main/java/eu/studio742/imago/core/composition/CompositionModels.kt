package eu.studio742.imago.core.composition

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import eu.studio742.imago.core.model.EditRecipe

const val COMPOSITION_SCHEMA_VERSION = 2
const val MAX_COMPOSITION_PAGES = 20
const val MAX_VIDEOS_PER_PAGE = 4
const val MAX_PAGE_DURATION_MS = 60_000L

/** The smallest side that still makes a page: below this there is no composition, there is an icon. */
const val MIN_PAGE_SIDE = 240

/**
 * The largest side a page can have.
 *
 * The static exporter draws the whole page into a single ARGB `Bitmap` — 4096 × 4096 is already
 * 64 MB of heap — and Android's video encoders rarely accept more than this per side. The ceiling is
 * the same for both, so there are no formats that export as an image but not as MP4.
 */
const val MAX_PAGE_SIDE = 4096

/**
 * The size of a page, in pixels.
 *
 * It stopped being an `enum` so the custom format fits in the same place as the others: whoever
 * draws, measures and exports keeps reading `pixelWidth`/`pixelHeight` without needing to know
 * whether it came from a preset or from two fields filled in by hand. The presets live in
 * [PageFormatPreset].
 */
@Serializable(with = PageFormatSerializer::class)
data class PageFormat(val pixelWidth: Int, val pixelHeight: Int, val label: String) {
    init {
        require(pixelWidth in MIN_PAGE_SIDE..MAX_PAGE_SIDE && pixelHeight in MIN_PAGE_SIDE..MAX_PAGE_SIDE) {
            "A page measures between $MIN_PAGE_SIDE and $MAX_PAGE_SIDE px per side."
        }
    }

    /** The aspect ratio, which is what the stage and the diagrams almost always want. */
    val aspectRatio: Float get() = pixelWidth.toFloat() / pixelHeight

    companion object {
        /** The format a composition is born with while nobody chooses another. */
        val Default: PageFormat get() = PageFormatPreset.STORY_9_16.format

        /** A custom size. The label is the measurement itself — there is no better name for it. */
        fun custom(pixelWidth: Int, pixelHeight: Int): PageFormat {
            val width = pixelWidth.coerceIn(MIN_PAGE_SIDE, MAX_PAGE_SIDE)
            val height = pixelHeight.coerceIn(MIN_PAGE_SIDE, MAX_PAGE_SIDE)
            return PageFormat(width, height, "$width × $height px")
        }
    }
}

/** The three families the list of formats is arranged by. */
enum class PageFormatCategory(val label: String) {
    SOCIAL("Redes sociais"),
    PHOTO("Photo prints"),
    EDITORIAL("Editorial"),
}

/**
 * The formats that come ready-made.
 *
 * The print ones are at 100 px/cm (≈ 254 dpi, enough for a photo print) and not at the usual
 * 300 dpi: at 300 dpi a 30 × 30 cm is 50 MB of bitmap just for the exporter to start working. In
 * exchange, the measurements in centimetres come out as round numbers — which is what makes the label
 * readable.
 *
 * The names of the first four entries are the ones already saved in existing projects (the format
 * is serialised by name), and so they are not touched.
 */
enum class PageFormatPreset(
    val category: PageFormatCategory,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val label: String,
) {
    STORY_9_16(PageFormatCategory.SOCIAL, 1080, 1920, "Story / Reel 9:16"),
    FEED_4_5(PageFormatCategory.SOCIAL, 1080, 1350, "Feed 4:5"),
    FEED_3_4(PageFormatCategory.SOCIAL, 1080, 1440, "Feed 3:4"),
    SQUARE_1_1(PageFormatCategory.SOCIAL, 1080, 1080, "Square 1:1"),
    LANDSCAPE_16_9(PageFormatCategory.SOCIAL, 1920, 1080, "Landscape 16:9"),
    PIN_2_3(PageFormatCategory.SOCIAL, 1000, 1500, "Pin 2:3"),

    PRINT_10X15(PageFormatCategory.PHOTO, 1500, 1000, "10 × 15 cm · 3:2"),
    PRINT_13X18(PageFormatCategory.PHOTO, 1800, 1300, "13 × 18 cm · 7:5"),
    PRINT_20X25(PageFormatCategory.PHOTO, 2500, 2000, "20 × 25 cm · 5:4"),
    PRINT_20X30(PageFormatCategory.PHOTO, 3000, 2000, "20 × 30 cm · 3:2"),
    PRINT_30X30(PageFormatCategory.PHOTO, 3000, 3000, "30 × 30 cm · 1:1"),
    PANORAMA_3_1(PageFormatCategory.PHOTO, 3000, 1000, "Panorama 3:1"),

    A4_PORTRAIT(PageFormatCategory.EDITORIAL, 2100, 2970, "A4 portrait"),
    A4_LANDSCAPE(PageFormatCategory.EDITORIAL, 2970, 2100, "A4 landscape"),
    A5_PORTRAIT(PageFormatCategory.EDITORIAL, 1480, 2100, "A5 portrait"),
    LETTER_PORTRAIT(PageFormatCategory.EDITORIAL, 2159, 2794, "Letter (US) portrait"),
    ALBUM_21X21(PageFormatCategory.EDITORIAL, 2100, 2100, "Album 21 × 21 cm"),
    ;

    val format: PageFormat get() = PageFormat(pixelWidth, pixelHeight, label)

    companion object {
        fun of(category: PageFormatCategory): List<PageFormatPreset> = entries.filter { it.category == category }

        /** The preset this format came from, if it came from one. */
        fun matching(format: PageFormat): PageFormatPreset? = entries.firstOrNull { it.format == format }
    }
}

/**
 * Presets by name, custom sizes by object.
 *
 * Saved projects say `"format":"STORY_9_16"` and can keep saying it: a preset is written as it
 * always was, and only a custom size becomes `{"width":…}`. A name that no longer exists does not
 * bring the whole project down — it falls back to the default format, because losing a
 * composition's aspect ratio is bad, but losing the composition is worse.
 */
internal object PageFormatSerializer : KSerializer<PageFormat> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): PageFormat {
        val input = decoder as? JsonDecoder ?: error("A page format is only read from JSON.")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive ->
                PageFormatPreset.entries.firstOrNull { it.name == element.content }?.format ?: PageFormat.Default
            else -> {
                val obj = element.jsonObject
                val width = obj["width"]?.jsonPrimitive?.intOrNull
                val height = obj["height"]?.jsonPrimitive?.intOrNull
                if (width == null || height == null) PageFormat.Default else PageFormat.custom(width, height)
            }
        }
    }

    override fun serialize(encoder: Encoder, value: PageFormat) {
        val output = encoder as? JsonEncoder ?: error("A page format is only written as JSON.")
        val preset = PageFormatPreset.matching(value)
        output.encodeJsonElement(
            if (preset != null) {
                JsonPrimitive(preset.name)
            } else {
                buildJsonObject {
                    put("width", JsonPrimitive(value.pixelWidth))
                    put("height", JsonPrimitive(value.pixelHeight))
                }
            },
        )
    }
}

@Serializable
data class NormalizedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(width > 0f && height > 0f) { "Elements need positive dimensions." }
    }

    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

@Serializable
data class ElementTransform(
    val bounds: NormalizedRect,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1f,
    val mirrorHorizontal: Boolean = false,
    val mirrorVertical: Boolean = false,
)

@Serializable
data class MediaReference(
    val assetId: String,
    val checksum: String,
    val fileName: String,
    val mimeType: String? = null,
    val width: Long? = null,
    val height: Long? = null,
    val durationMs: Long? = null,
    /**
     * Hints for another device to recognise the same device photo without reading files: the file
     * size and the photo's date. Filled in when syncing.
     */
    val sizeBytes: Long? = null,
    val takenAt: String? = null,
)

@Serializable
data class PhotoRecipeSnapshot(val recipe: EditRecipe)

@Serializable
data class FrameStyle(
    val cornerRadius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderArgb: Long = 0xFFFFFFFF,
    val shadowRadius: Float = 0f,
)

@Serializable
data class PlacementCrop(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
)

@Serializable
data class VideoTiming(
    val trimStartMs: Long = 0,
    val trimEndMs: Long,
    val startOffsetMs: Long = 0,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val loop: Boolean = false,
) {
    init {
        require(trimStartMs >= 0 && trimEndMs > trimStartMs)
        require(startOffsetMs in 0..MAX_PAGE_DURATION_MS)
        require(volume in 0f..1f)
    }
}

@Serializable
enum class TextAlignment { START, CENTER, END }

@Serializable
enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW }

@Serializable
enum class StrokeKind { PEN, MARKER, ERASER }

/**
 * A point of a stroke, with the force it was made with.
 *
 * [pressure] comes from the stylus and is 1 for everything else — a finger has no reliable
 * pressure, and projects saved before this column existed do not carry it. With the default at 1,
 * both draw exactly as they always did.
 */
@Serializable
data class StrokePoint(val x: Float, val y: Float, val pressure: Float = 1f)

/**
 * How much pressure can narrow a stroke.
 *
 * It does not reach zero on purpose: a stroke that disappears where the hand eased off reads as a
 * drawing glitch and not as a light stroke.
 */
private const val MIN_PRESSURE_SCALE = 0.35f

/** The stroke's width at a point, between [MIN_PRESSURE_SCALE] and the full width. */
fun strokeWidthAt(baseWidth: Float, pressure: Float): Float =
    baseWidth * (MIN_PRESSURE_SCALE + (1f - MIN_PRESSURE_SCALE) * pressure.coerceIn(0f, 1f))

/**
 * True when the stroke is worth drawing segment by segment.
 *
 * A constant-pressure stroke — every one that came from a finger, and every one that already
 * existed — is drawn with a single `Path`, which is cheaper and is what was always done. Only a
 * stylus stroke with varying pressure justifies a line per segment.
 */
fun List<StrokePoint>.hasVariablePressure(): Boolean {
    if (size < 2) return false
    val first = this[0].pressure
    return any { it.pressure != first }
}

@Serializable
sealed class CompositionElement {
    abstract val id: String
    abstract val transform: ElementTransform
    abstract val zIndex: Int
    abstract val groupId: String?
    abstract val locked: Boolean
    abstract val visible: Boolean
    abstract val repeatOnPages: Set<Int>

    @Serializable
    @SerialName("media_placeholder")
    data class MediaPlaceholder(
        override val id: String,
        override val transform: ElementTransform,
        override val zIndex: Int,
        val acceptsVideo: Boolean = true,
        val frame: FrameStyle = FrameStyle(),
        override val groupId: String? = null,
        override val locked: Boolean = false,
        override val visible: Boolean = true,
        override val repeatOnPages: Set<Int> = emptySet(),
    ) : CompositionElement()

    @Serializable
    @SerialName("photo")
    data class Photo(
        override val id: String,
        override val transform: ElementTransform,
        override val zIndex: Int,
        val media: MediaReference,
        val recipe: PhotoRecipeSnapshot,
        val crop: PlacementCrop = PlacementCrop(),
        val frame: FrameStyle = FrameStyle(),
        override val groupId: String? = null,
        override val locked: Boolean = false,
        override val visible: Boolean = true,
        override val repeatOnPages: Set<Int> = emptySet(),
    ) : CompositionElement()

    @Serializable
    @SerialName("video")
    data class Video(
        override val id: String,
        override val transform: ElementTransform,
        override val zIndex: Int,
        val pageIndex: Int,
        val media: MediaReference,
        val timing: VideoTiming,
        val crop: PlacementCrop = PlacementCrop(),
        val frame: FrameStyle = FrameStyle(),
        override val groupId: String? = null,
        override val locked: Boolean = false,
        override val visible: Boolean = true,
        override val repeatOnPages: Set<Int> = emptySet(),
    ) : CompositionElement()

    /**
     * A text block. How it is laid out and drawn is in [CompositionTextLayout].
     *
     * The measurements use two units, depending on what they follow. [fontSize], the shadow and
     * the background are pixels on a page 1080 wide, like the shapes' thickness: they grow with the
     * page. [letterSpacing] and [strokeWidth] are fractions of the font size (em), because they
     * follow the letter: making the text bigger must not leave the letters tighter or the outline
     * thinner.
     *
     * The outline, the shadow and the background are on when their colour exists. Each one's other
     * measurements stay saved even when it is off, to turn it back on without losing them.
     */
    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        override val transform: ElementTransform,
        override val zIndex: Int,
        val text: String,
        val fontFamily: String = CompositionFonts.Default.id,
        val fontSize: Float = 48f,
        val fontWeight: Int = 400,
        val colorArgb: Long = 0xFFFFFFFF,
        val alignment: TextAlignment = TextAlignment.START,
        /** Em. Added to every letter, like Android's `letterSpacing`. */
        val letterSpacing: Float = 0f,
        /** Multiplies the font's natural line height (ascent plus descent). */
        val lineSpacing: Float = 1f,
        val allCaps: Boolean = false,
        val backgroundArgb: Long? = null,
        val backgroundPadding: Float = 24f,
        val backgroundCornerRadius: Float = 0f,
        val strokeArgb: Long? = null,
        /** Em. The stroke is half outside and half inside the letter, and the fill goes on top. */
        val strokeWidth: Float = 0.06f,
        val shadowArgb: Long? = null,
        val shadowRadius: Float = 8f,
        val shadowOffsetX: Float = 0f,
        val shadowOffsetY: Float = 4f,
        override val groupId: String? = null,
        override val locked: Boolean = false,
        override val visible: Boolean = true,
        override val repeatOnPages: Set<Int> = emptySet(),
    ) : CompositionElement()

    @Serializable
    @SerialName("shape")
    data class Shape(
        override val id: String,
        override val transform: ElementTransform,
        override val zIndex: Int,
        val kind: ShapeKind,
        val fillArgb: Long = 0xFFFFFFFF,
        val strokeArgb: Long = 0xFFFFFFFF,
        val strokeWidth: Float = 2f,
        override val groupId: String? = null,
        override val locked: Boolean = false,
        override val visible: Boolean = true,
        override val repeatOnPages: Set<Int> = emptySet(),
    ) : CompositionElement()

    @Serializable
    @SerialName("drawing")
    data class Drawing(
        override val id: String,
        override val transform: ElementTransform,
        override val zIndex: Int,
        val kind: StrokeKind,
        val points: List<StrokePoint>,
        val colorArgb: Long,
        val strokeWidth: Float,
        override val groupId: String? = null,
        override val locked: Boolean = false,
        override val visible: Boolean = true,
        override val repeatOnPages: Set<Int> = emptySet(),
    ) : CompositionElement()
}

@Serializable
sealed class CompositionBackground {
    @Serializable
    @SerialName("solid")
    data class Solid(val argb: Long = 0xFF111111) : CompositionBackground()

    @Serializable
    @SerialName("gradient")
    data class Gradient(val startArgb: Long, val endArgb: Long, val angleDegrees: Float = 0f) : CompositionBackground()

    @Serializable
    @SerialName("photo")
    data class Photo(val media: MediaReference, val crop: PlacementCrop = PlacementCrop(), val blurRadius: Float = 0f) : CompositionBackground()
}

@Serializable
data class CompositionPage(
    val id: String,
    val index: Int,
    val backgroundOverride: CompositionBackground? = null,
    val durationMs: Long = 5_000,
)

@Serializable
data class CompositionProject(
    val schemaVersion: Int = COMPOSITION_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val format: PageFormat,
    val pages: List<CompositionPage>,
    val elements: List<CompositionElement> = emptyList(),
    val background: CompositionBackground = CompositionBackground.Solid(),
    val safeZonesVisible: Boolean = true,
    val revision: Long = 1,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(pages.size in 1..MAX_COMPOSITION_PAGES)
        require(pages.map(CompositionPage::index) == pages.indices.toList())
        pages.forEach { require(it.durationMs in 1_000..MAX_PAGE_DURATION_MS) }
        elements.filterIsInstance<CompositionElement.Video>()
            .groupingBy(CompositionElement.Video::pageIndex)
            .eachCount()
            .forEach { (_, count) -> require(count <= MAX_VIDEOS_PER_PAGE) }
        elements.filterIsInstance<CompositionElement.Video>().forEach { video ->
            require(video.pageIndex in pages.indices)
            require(video.transform.bounds.x >= video.pageIndex && video.transform.bounds.right <= video.pageIndex + 1f) {
                "Videos have to stay confined to one page."
            }
            require(video.repeatOnPages.isEmpty())
        }
    }
}

@Serializable
data class CompositionTemplate(
    val id: String,
    val name: String,
    val project: CompositionProject,
    val includesMedia: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BrandKit(
    /** The first is the brand's main colour. */
    val paletteArgb: List<Long> = DefaultPalette,
    val primaryFont: String = CompositionFonts.Default.id,
    val secondaryFont: String = CompositionFonts.Lora.id,
    val logos: List<MediaReference> = emptyList(),
    val updatedAt: String,
) {
    companion object {
        /** The palette of a kit nobody has edited yet. */
        val DefaultPalette: List<Long> = listOf(0xFF111111, 0xFFFFFFFF, 0xFFD4AF37)
    }
}
