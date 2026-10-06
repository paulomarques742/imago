package eu.studio742.imago.core.model

/**
 * The names sync writes into the user's data, in the app language at the moment it writes them.
 *
 * Sync is pure Kotlin and has no translations, so the interface provides them. What comes out is
 * content, like a name the person typed: it stays in the language it was written in.
 */
interface SyncTexts {
    /** The copy that keeps a project's losing version: "<name> (conflict · <device>)". */
    suspend fun conflictCopyName(name: String, deviceName: String): String

    /** A device whose name is not known, as it appears in a recipe's conflict history. */
    suspend fun unknownDevice(): String
}
