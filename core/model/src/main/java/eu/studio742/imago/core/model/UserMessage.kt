package eu.studio742.imago.core.model

/**
 * A message for the person, born outside the interface.
 *
 * The data layer and sync have no access to the translated texts, and should not: they are pure
 * Kotlin, shared by both apps. When a failure has to reach the screen with a sentence of its own,
 * [UserMessageException] is thrown with one of these keys, and it is the interface that writes it
 * in the person's language (`core/designsystem/i18n/UserMessages.kt`). An exception without a key
 * shows up as the generic message of whoever catches it.
 *
 * Logs (`log`) keep the technical text: they are for developers.
 */
enum class UserMessage {
    /** The person cancelled the operation. */
    OPERATION_CANCELLED,

    /** The file of a photo on the device is no longer accessible. */
    FILE_UNAVAILABLE,

    /** The file left the computer library's folder. */
    FOLDER_FILE_UNAVAILABLE,

    /** Moving to the Recycle Bin failed. */
    TRASH_FAILED,

    /** A new address of an Immich library answers for another server or another account. */
    ADDRESS_OTHER_ACCOUNT,

    /** The Immich account of a library could not be confirmed through the current address. */
    ACCOUNT_UNCONFIRMED,

    /** There are photos from a library not yet linked to the IMAGO account. */
    LIBRARY_NOT_LINKED,

    /** Saving the configuration on the device failed. */
    CONFIGURATION_NOT_SAVED,

    /** Sync failed, without a more precise reason. */
    SYNC_FAILED,

    /** The account server refused a change; it is not retried. */
    SYNC_CHANGE_REJECTED,

    /** A library saved without a name. */
    LIBRARY_NAME_REQUIRED,

    /** The key of an existing library now belongs to another Immich account. */
    KEY_OTHER_ACCOUNT,

    /** This library's Immich account already has a library configured. */
    LIBRARY_ALREADY_CONFIGURED,

    /** The library was disconnected and has to be connected again. */
    LIBRARY_RECONNECT,

    /** An address the library already has. */
    ADDRESS_ALREADY_IN_LIBRARY,

    /** A new address answers for another Immich account, with the same key accepted. */
    ADDRESS_WRONG_SERVER,

    /** Removing a library's last address. */
    LIBRARY_NEEDS_ADDRESS,

    /** A disconnected library. Argument: its name. */
    LIBRARY_DISCONNECTED,

    /** The chosen path is not a folder. */
    CHOOSE_FOLDER,

    /** The composition no longer exists. */
    COMPOSITION_UNAVAILABLE,

    /** An edit made in a version of the app newer than this one. */
    EDIT_NEEDS_NEWER_APP,

    /** An Immich server address is not a valid http(s) URL. */
    IMMICH_INVALID_URL,

    /** The Immich server refused the API key. */
    IMMICH_KEY_REJECTED,

    /** The API key lacks permissions. Argument: their Immich names, comma separated. */
    IMMICH_PERMISSION_MISSING,

    /** The Immich server is too old. Arguments: its version and the minimum. */
    IMMICH_VERSION_UNSUPPORTED,

    /** The Immich server answered with an HTTP error. Argument: the code. */
    IMMICH_SERVER_ERROR,

    /** There was no answer from the Immich server. */
    IMMICH_UNREACHABLE,

    /** Access to all photos and videos on the device. */
    DEVICE_ACCESS_FULL,

    /** Access only to the photos and videos the person chose. */
    DEVICE_ACCESS_SELECTED,

    /** Access only to photos or only to videos. */
    DEVICE_ACCESS_ONE_TYPE,

    /** No access: the permission has to be granted. */
    DEVICE_ACCESS_NONE,

    /** No folder chosen in the computer's library. */
    FOLDERS_NONE,

    /** One folder chosen. Argument: its name. */
    FOLDERS_ONE,

    /** Several folders chosen. Argument: how many. */
    FOLDERS_MANY,
}

/** A sentence for the person that is not a failure — a state, a summary. [args] go in, in order. */
data class UserText(val message: UserMessage, val args: List<Any> = emptyList()) {
    constructor(message: UserMessage, vararg args: Any) : this(message, args.toList())
}

/**
 * A failure with a sentence of its own for the person. [args] go into the sentence, in order.
 *
 * Open so that the failure families that already existed with a name of their own (the Immich
 * client's, for example) can bring their sentence too.
 */
open class UserMessageException(
    val userMessage: UserMessage,
    val args: List<Any> = emptyList(),
    cause: Throwable? = null,
) : Exception(userMessage.name, cause)

/** `require` with a sentence for the person: fails with [message] when [condition] is false. */
fun requireUser(condition: Boolean, message: UserMessage, vararg args: Any) {
    if (!condition) throw UserMessageException(message, args.toList())
}
