package eu.studio742.imago.feature.editor

import kotlin.math.pow

/** A brush slider, with Lightroom's ranges. */
enum class BrushSetting(val neutral: Float, val range: ClosedFloatingPointRange<Float>) {
    SIZE(25f, 1f..100f),
    FEATHER(50f, 0f..100f),
    FLOW(100f, 1f..100f),
    DENSITY(100f, 1f..100f),
}

/** One brush's settings — the Brush's or the Eraser's, which Lightroom keeps apart. */
data class BrushSettings(
    val size: Float = BrushSetting.SIZE.neutral,
    val feather: Float = BrushSetting.FEATHER.neutral,
    val flow: Float = BrushSetting.FLOW.neutral,
    val density: Float = BrushSetting.DENSITY.neutral,
    val autoMask: Boolean = false,
) {
    fun value(setting: BrushSetting): Float = when (setting) {
        BrushSetting.SIZE -> size
        BrushSetting.FEATHER -> feather
        BrushSetting.FLOW -> flow
        BrushSetting.DENSITY -> density
    }

    fun with(setting: BrushSetting, value: Float): BrushSettings {
        val clamped = value.coerceIn(setting.range.start, setting.range.endInclusive)
        return when (setting) {
            BrushSetting.SIZE -> copy(size = clamped)
            BrushSetting.FEATHER -> copy(feather = clamped)
            BrushSetting.FLOW -> copy(flow = clamped)
            BrushSetting.DENSITY -> copy(density = clamped)
        }
    }
}

/**
 * The brush tool: the two brushes and which one is in the hand.
 *
 * It is not part of the recipe — a stroke keeps the settings it was painted with — but of the device,
 * where it is remembered between sessions, as Lightroom does.
 */
data class BrushTool(
    val paint: BrushSettings = BrushSettings(),
    val erase: BrushSettings = BrushSettings(),
    val erasing: Boolean = false,
) {
    val active: BrushSettings get() = if (erasing) erase else paint

    fun withActive(settings: BrushSettings): BrushTool = if (erasing) copy(erase = settings) else copy(paint = settings)

    /** `paint=size,feather,flow,density,auto;erase=…;erasing=0|1` — read back by [decode]. */
    fun encode(): String = "paint=${paint.encode()};erase=${erase.encode()};erasing=${if (erasing) 1 else 0}"

    companion object {
        /** What was stored, or the defaults; a value this version cannot read is forgotten, not an error. */
        fun decode(stored: String?): BrushTool {
            val fields = stored.orEmpty().split(';').mapNotNull { field ->
                field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            return BrushTool(
                paint = fields["paint"]?.let(::decodeSettings) ?: BrushSettings(),
                erase = fields["erase"]?.let(::decodeSettings) ?: BrushSettings(),
                erasing = fields["erasing"] == "1",
            )
        }

        private fun BrushSettings.encode() = "$size,$feather,$flow,$density,${if (autoMask) 1 else 0}"

        private fun decodeSettings(encoded: String): BrushSettings? {
            val parts = encoded.split(',')
            if (parts.size != 5) return null
            fun number(index: Int, setting: BrushSetting) =
                (parts[index].toFloatOrNull() ?: setting.neutral).coerceIn(setting.range.start, setting.range.endInclusive)
            return BrushSettings(
                size = number(0, BrushSetting.SIZE),
                feather = number(1, BrushSetting.FEATHER),
                flow = number(2, BrushSetting.FLOW),
                density = number(3, BrushSetting.DENSITY),
                autoMask = parts[4] == "1",
            )
        }
    }
}

/**
 * The brush's radius on screen, in dp, for a Size from 1 to 100.
 *
 * On screen and not on the photo, as in Lightroom: to paint finer one zooms in, and the same brush
 * covers less of the photo. Squared so that the small sizes — where precision matters — take more of
 * the slider: 1 is a 3 dp tip, 25 about 12 dp, 100 a 160 dp circle.
 */
fun brushRadiusDp(size: Float): Float {
    val t = ((size - BrushSetting.SIZE.range.start) / (BrushSetting.SIZE.range.endInclusive - BrushSetting.SIZE.range.start)).coerceIn(0f, 1f)
    return MIN_BRUSH_RADIUS_DP + t.pow(2) * (MAX_BRUSH_RADIUS_DP - MIN_BRUSH_RADIUS_DP)
}

private const val MIN_BRUSH_RADIUS_DP = 3f
private const val MAX_BRUSH_RADIUS_DP = 160f

/**
 * How far the finger has to travel, as a fraction of the radius, before a stroke takes another point.
 * The dabs are laid along the path anyway; the points only have to follow its curve.
 */
const val BRUSH_POINT_SPACING = 0.15f

/** How much `[` and `]` change the Size on desktop. */
const val BRUSH_SIZE_KEY_STEP = 5f
