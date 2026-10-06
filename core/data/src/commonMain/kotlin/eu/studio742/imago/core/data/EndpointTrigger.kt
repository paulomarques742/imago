package eu.studio742.imago.core.data

/** A moment when a library's active address may have stopped being the right one. */
enum class EndpointTrigger { NETWORK_CHANGED, FOREGROUND, CONNECTION_FAILED }
