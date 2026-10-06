package eu.studio742.imago.core.designsystem.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A text that lives in state — in a ViewModel message, in a history entry — and is only written when
 * it reaches the screen.
 *
 * Keeping the reference instead of the sentence is what makes changing the language also change what
 * was already on screen. The arguments can be other [UiText]s, resolved in the same language.
 */
@Immutable
sealed interface UiText {
    data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : UiText

    data class Plural(val resource: PluralStringResource, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Text that already comes written from outside — a file name, the name the person gave. */
    data class Raw(val text: String) : UiText

    /** Several texts in a row, with a separator between them. */
    data class Joined(val parts: List<UiText>, val separator: String = " · ") : UiText
}

fun uiText(resource: StringResource, vararg args: Any): UiText = UiText.Resource(resource, args.toList())

fun uiPlural(resource: PluralStringResource, count: Int, vararg args: Any): UiText =
    UiText.Plural(resource, count, if (args.isEmpty()) listOf(count) else args.toList())

fun String.asUiText(): UiText = UiText.Raw(this)

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(resource, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Plural -> pluralStringResource(resource, count, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Raw -> text
    is UiText.Joined -> parts.map { it.resolve() }.joinToString(separator)
}

/** The same, outside the composition, in the app language. */
suspend fun UiText.resolveNow(): String = when (this) {
    is UiText.Resource -> getString(resource, *args.map { it.resolveArgNow() }.toTypedArray())
    is UiText.Plural -> getPluralString(resource, count, *args.map { it.resolveArgNow() }.toTypedArray())
    is UiText.Raw -> text
    is UiText.Joined -> parts.map { it.resolveNow() }.joinToString(separator)
}

@Composable
private fun Any.resolveArg(): Any = if (this is UiText) resolve() else this

private suspend fun Any.resolveArgNow(): Any = if (this is UiText) resolveNow() else this
