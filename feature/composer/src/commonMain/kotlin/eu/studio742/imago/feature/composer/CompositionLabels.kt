package eu.studio742.imago.feature.composer

import androidx.compose.runtime.Composable
import eu.studio742.imago.core.composition.BuiltInLayout
import eu.studio742.imago.core.composition.FontCategory
import eu.studio742.imago.core.composition.PageFormat
import eu.studio742.imago.core.composition.PageFormatCategory
import eu.studio742.imago.core.composition.PageFormatPreset
import eu.studio742.imago.feature.composer.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * The names the screen gives to the things of the compositions model.
 *
 * `core:composition` is pure JVM and has no texts: the labels there are internal names, and the presets
 * are saved by the enum's name. What the person reads comes from here, in the app language. Every
 * `when` is exhaustive — a new preset without a name does not compile.
 */

internal fun PageFormatPreset.labelRes(): StringResource = when (this) {
    PageFormatPreset.STORY_9_16 -> Res.string.composer_format_story_9_16
    PageFormatPreset.FEED_4_5 -> Res.string.composer_format_feed_4_5
    PageFormatPreset.FEED_3_4 -> Res.string.composer_format_feed_3_4
    PageFormatPreset.SQUARE_1_1 -> Res.string.composer_format_square_1_1
    PageFormatPreset.LANDSCAPE_16_9 -> Res.string.composer_format_landscape_16_9
    PageFormatPreset.PIN_2_3 -> Res.string.composer_format_pin_2_3
    PageFormatPreset.PRINT_10X15 -> Res.string.composer_format_print_10x15
    PageFormatPreset.PRINT_13X18 -> Res.string.composer_format_print_13x18
    PageFormatPreset.PRINT_20X25 -> Res.string.composer_format_print_20x25
    PageFormatPreset.PRINT_20X30 -> Res.string.composer_format_print_20x30
    PageFormatPreset.PRINT_30X30 -> Res.string.composer_format_print_30x30
    PageFormatPreset.PANORAMA_3_1 -> Res.string.composer_format_panorama_3_1
    PageFormatPreset.A4_PORTRAIT -> Res.string.composer_format_a4_portrait
    PageFormatPreset.A4_LANDSCAPE -> Res.string.composer_format_a4_landscape
    PageFormatPreset.A5_PORTRAIT -> Res.string.composer_format_a5_portrait
    PageFormatPreset.LETTER_PORTRAIT -> Res.string.composer_format_letter_portrait
    PageFormatPreset.ALBUM_21X21 -> Res.string.composer_format_album_21x21
}

/** The format's name: that of the preset it came from, or the measurement, which needs no translation. */
@Composable
internal fun PageFormat.displayLabel(): String =
    PageFormatPreset.matching(this)?.let { stringResource(it.labelRes()) } ?: label

internal fun PageFormatCategory.labelRes(): StringResource = when (this) {
    PageFormatCategory.SOCIAL -> Res.string.composer_format_category_social
    PageFormatCategory.PHOTO -> Res.string.composer_format_category_photo
    PageFormatCategory.EDITORIAL -> Res.string.composer_format_category_editorial
}

internal fun BuiltInLayout.labelRes(): StringResource = when (this) {
    BuiltInLayout.BLANK -> Res.string.composer_layout_blank
    BuiltInLayout.FULL_BLEED -> Res.string.composer_layout_full_bleed
    BuiltInLayout.VERTICAL_SPLIT -> Res.string.composer_layout_vertical_split
    BuiltInLayout.HORIZONTAL_SPLIT -> Res.string.composer_layout_horizontal_split
    BuiltInLayout.GRID_2X2 -> Res.string.composer_layout_grid_2x2
    BuiltInLayout.HERO_STACK -> Res.string.composer_layout_hero_stack
    BuiltInLayout.EDITORIAL -> Res.string.composer_layout_editorial
    BuiltInLayout.OVERLAP -> Res.string.composer_layout_overlap
    BuiltInLayout.PANORAMA_2 -> Res.string.composer_layout_panorama_2
    BuiltInLayout.PANORAMA_3 -> Res.string.composer_layout_panorama_3
}

@Composable
internal fun FontCategory.displayName(): String = stringResource(
    when (this) {
        FontCategory.SANS -> Res.string.composer_font_category_sans
        FontCategory.SERIF -> Res.string.composer_font_category_serif
        FontCategory.DISPLAY -> Res.string.composer_font_category_display
        FontCategory.SCRIPT -> Res.string.composer_font_category_script
        FontCategory.MONO -> Res.string.composer_font_category_mono
    },
)
