package eu.studio742.imago.core.designsystem.i18n

import eu.studio742.imago.core.designsystem.resources.*
import eu.studio742.imago.core.model.UserMessage
import eu.studio742.imago.core.model.UserMessageException
import eu.studio742.imago.core.model.UserText
import org.jetbrains.compose.resources.StringResource

/**
 * The sentence for each [UserMessage], with the [args] in order. An exhaustive `when`: a new key
 * without a sentence does not compile.
 */
fun UserMessage.text(args: List<Any> = emptyList()): UiText = when (this) {
    UserMessage.FOLDERS_MANY -> {
        val count = args.firstOrNull() as? Int ?: 0
        UiText.Plural(Res.plurals.message_folders_many, count, listOf(count))
    }
    else -> UiText.Resource(resource(), args)
}

private fun UserMessage.resource(): StringResource = when (this) {
    UserMessage.OPERATION_CANCELLED -> Res.string.message_operation_cancelled
    UserMessage.FILE_UNAVAILABLE -> Res.string.message_file_unavailable
    UserMessage.FOLDER_FILE_UNAVAILABLE -> Res.string.message_folder_file_unavailable
    UserMessage.TRASH_FAILED -> Res.string.message_trash_failed
    UserMessage.ADDRESS_OTHER_ACCOUNT -> Res.string.message_address_other_account
    UserMessage.ACCOUNT_UNCONFIRMED -> Res.string.message_account_unconfirmed
    UserMessage.LIBRARY_NOT_LINKED -> Res.string.message_library_not_linked
    UserMessage.CONFIGURATION_NOT_SAVED -> Res.string.message_configuration_not_saved
    UserMessage.SYNC_FAILED -> Res.string.message_sync_failed
    UserMessage.SYNC_CHANGE_REJECTED -> Res.string.message_sync_change_rejected
    UserMessage.LIBRARY_NAME_REQUIRED -> Res.string.message_library_name_required
    UserMessage.KEY_OTHER_ACCOUNT -> Res.string.message_key_other_account
    UserMessage.LIBRARY_ALREADY_CONFIGURED -> Res.string.message_library_already_configured
    UserMessage.LIBRARY_RECONNECT -> Res.string.message_library_reconnect
    UserMessage.ADDRESS_ALREADY_IN_LIBRARY -> Res.string.message_address_already_in_library
    UserMessage.ADDRESS_WRONG_SERVER -> Res.string.message_address_wrong_server
    UserMessage.LIBRARY_NEEDS_ADDRESS -> Res.string.message_library_needs_address
    UserMessage.LIBRARY_DISCONNECTED -> Res.string.message_library_disconnected
    UserMessage.CHOOSE_FOLDER -> Res.string.message_choose_folder
    UserMessage.COMPOSITION_UNAVAILABLE -> Res.string.message_composition_unavailable
    UserMessage.EDIT_NEEDS_NEWER_APP -> Res.string.message_edit_needs_newer_app
    UserMessage.IMMICH_INVALID_URL -> Res.string.message_immich_invalid_url
    UserMessage.IMMICH_KEY_REJECTED -> Res.string.message_immich_key_rejected
    UserMessage.IMMICH_PERMISSION_MISSING -> Res.string.message_immich_permission_missing
    UserMessage.IMMICH_VERSION_UNSUPPORTED -> Res.string.message_immich_version_unsupported
    UserMessage.IMMICH_SERVER_ERROR -> Res.string.message_immich_server_error
    UserMessage.IMMICH_UNREACHABLE -> Res.string.message_immich_unreachable
    UserMessage.DEVICE_ACCESS_FULL -> Res.string.message_device_access_full
    UserMessage.DEVICE_ACCESS_SELECTED -> Res.string.message_device_access_selected
    UserMessage.DEVICE_ACCESS_ONE_TYPE -> Res.string.message_device_access_one_type
    UserMessage.DEVICE_ACCESS_NONE -> Res.string.message_device_access_none
    UserMessage.FOLDERS_NONE -> Res.string.message_folders_none
    UserMessage.FOLDERS_ONE -> Res.string.message_folders_one
    UserMessage.FOLDERS_MANY -> error("FOLDERS_MANY is a plural: it comes out of text()")
}

/** A state coming from the data layer, ready for the screen. */
fun UserText.toUiText(): UiText = message.text(args)

/**
 * A failure that already carries the sentence for the person. It serves the interface modules, which
 * have their own texts — an exporter that knows why it failed throws this instead of a sentence
 * written in a single language.
 */
class LocalizedException(val text: UiText, cause: Throwable? = null) : Exception(null, cause)

/**
 * What to show whoever sees this failure: its own sentence, if the failure brings one, otherwise
 * [fallback] — the sentence of whoever caught it, who knows what they were trying to do.
 *
 * The exception's technical text never goes to the screen: it is in a single language, and says
 * nothing to someone editing a photo. It stays in the logs.
 */
fun Throwable.toUiText(fallback: StringResource = Res.string.error_generic): UiText {
    for (failure in generateSequence(this) { it.cause }) {
        when (failure) {
            is LocalizedException -> return failure.text
            is UserMessageException -> return failure.userMessage.text(failure.args)
        }
    }
    return UiText.Resource(fallback)
}
