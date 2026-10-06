package eu.studio742.imago.core.data

/**
 * The timeline sync log, which used to go to logcat.
 *
 * It is a short line on stderr: on Android the runtime forwards it to logcat with the System.err
 * tag, on desktop it shows in the console.
 */
internal fun syncLog(level: String, message: String) {
    System.err.println("[ImmichRoom/sync] $level $message")
}
