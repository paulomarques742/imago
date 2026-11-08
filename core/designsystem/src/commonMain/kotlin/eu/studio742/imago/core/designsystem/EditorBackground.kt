package eu.studio742.imago.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * What the photo sits on in the editor, chosen in Settings. A photo reads differently on black and on
 * grey, and which one helps is a matter of how each person works. [grey] is null for [AUTOMATIC], which
 * is the theme's workspace.
 */
enum class EditorBackground(val grey: Color?) {
    AUTOMATIC(null),
    BLACK(Color(0xFF000000)),
    DARK_GREY(Color(0xFF2B2B2B)),
    MID_GREY(Color(0xFF808080)),
    LIGHT_GREY(Color(0xFFC4C4C4)),
    WHITE(Color(0xFFFFFFFF));

    companion object {
        /** A stored choice: nothing stored, or a value from another version, is [AUTOMATIC]. */
        fun of(stored: String?): EditorBackground = entries.firstOrNull { it.name == stored } ?: AUTOMATIC
    }
}

/**
 * The palette of what sits on the editor's stage. The panels follow the theme, but the stage can now be
 * white in the dark theme, so the bar over it takes its text from the background and not from the theme.
 */
fun ImagoUiPalette.onStage(background: EditorBackground): ImagoUiPalette {
    val grey = background.grey ?: return this
    val base = if (grey.luminance() < STAGE_NEEDS_DARK_TEXT) ImagoDarkPalette else ImagoLightPalette
    return base.copy(workspace = grey)
}

// Where dark text starts to read better than light text on a grey.
private const val STAGE_NEEDS_DARK_TEXT = 0.18f
