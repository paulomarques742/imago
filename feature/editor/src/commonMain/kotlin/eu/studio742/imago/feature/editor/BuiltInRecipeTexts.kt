package eu.studio742.imago.feature.editor

import eu.studio742.imago.core.designsystem.i18n.UiText
import eu.studio742.imago.core.model.BuiltInRecipe
import eu.studio742.imago.core.model.RecipeCategory
import eu.studio742.imago.feature.editor.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The names of the recipes that come with the app, in the language of whoever sees them.
 *
 * `core/model` is pure Kotlin and keeps them as data, in the base language; the interface writes them
 * by `id`. A new recipe without an entry here shows up with the model's name, instead of disappearing.
 */
private fun BuiltInRecipe.nameRes(): StringResource? = when (id) {
    "built-in-golden-hour" -> Res.string.editor_builtin_golden_hour
    "built-in-dramatic" -> Res.string.editor_builtin_dramatic
    "built-in-pastel" -> Res.string.editor_builtin_pastel
    "built-in-monochrome" -> Res.string.editor_builtin_monochrome
    "built-in-cinematic" -> Res.string.editor_builtin_cinematic
    "built-in-natural" -> Res.string.editor_builtin_natural
    "built-in-matte" -> Res.string.editor_builtin_matte
    "built-in-vibrant" -> Res.string.editor_builtin_vibrant
    "built-in-cold" -> Res.string.editor_builtin_cold
    else -> null
}

private fun BuiltInRecipe.descriptionRes(): StringResource? = when (id) {
    "built-in-golden-hour" -> Res.string.editor_builtin_golden_hour_desc
    "built-in-dramatic" -> Res.string.editor_builtin_dramatic_desc
    "built-in-pastel" -> Res.string.editor_builtin_pastel_desc
    "built-in-monochrome" -> Res.string.editor_builtin_monochrome_desc
    "built-in-cinematic" -> Res.string.editor_builtin_cinematic_desc
    "built-in-natural" -> Res.string.editor_builtin_natural_desc
    "built-in-matte" -> Res.string.editor_builtin_matte_desc
    "built-in-vibrant" -> Res.string.editor_builtin_vibrant_desc
    "built-in-cold" -> Res.string.editor_builtin_cold_desc
    else -> null
}

fun BuiltInRecipe.nameText(): UiText = nameRes()?.let { UiText.Resource(it) } ?: UiText.Raw(name)

fun BuiltInRecipe.descriptionText(): UiText = descriptionRes()?.let { UiText.Resource(it) } ?: UiText.Raw(description)

fun RecipeCategory.labelRes(): StringResource = when (this) {
    RecipeCategory.LANDSCAPE -> Res.string.editor_category_landscape
    RecipeCategory.PORTRAIT -> Res.string.editor_category_portrait
    RecipeCategory.URBAN -> Res.string.editor_category_urban
    RecipeCategory.MONOCHROME -> Res.string.editor_category_monochrome
    RecipeCategory.CINEMATIC -> Res.string.editor_category_cinematic
}
