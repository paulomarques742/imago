package eu.studio742.imago.feature.editor

import org.jetbrains.compose.resources.StringResource
import androidx.compose.runtime.Composable

@Composable
expect fun editorViewModel(): EditorViewModel

@Composable
expect fun recipeLibraryViewModel(): RecipeLibraryViewModel

/**
 * The gesture of saving the export "on this device". On old Android it first asks for the write
 * permission; on other versions, and on desktop, it saves right away.
 */
@Composable
expect fun rememberSaveToDevice(onSave: () -> Unit, onDenied: () -> Unit): () -> Unit

/** The button's text: "Save to gallery" on the phone, "Save as…" on the computer. */
expect val SaveToDeviceLabel: StringResource
