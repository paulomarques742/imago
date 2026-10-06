package eu.studio742.imago.core.sync

/**
 * Chooses, among the addresses of an Immich library, the first one that answers.
 *
 * The order is the user's: whoever put the home address ahead of the external one wants the home
 * one whenever it is in reach. That is why it stops at the first that answers, and the next ones
 * are left untested — there is no reason to wake them.
 */
class EndpointSelector(private val ping: suspend (String) -> Boolean) {
    suspend fun select(urls: List<String>): EndpointSelection {
        val probes = linkedMapOf<String, Boolean>()
        for (url in urls) {
            val reachable = ping(url)
            probes[url] = reachable
            if (reachable) return EndpointSelection(url, probes)
        }
        return EndpointSelection(null, probes)
    }
}

/** The chosen address, or null if none answered, and the result of each address tested. */
data class EndpointSelection(val active: String?, val probes: Map<String, Boolean>)
