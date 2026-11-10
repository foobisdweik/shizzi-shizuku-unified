package dev.shizzi

/**
 * The downstreams a session brought up, which are the only ones it stops.
 * Stopping USB tethering with nothing running can reset the USB function.
 *
 * Written from the session and watchdog threads, read by the shutdown hook.
 */
class StartedDownstreams(private val persist: (Set<TetheringType>) -> Unit = {}) {

    @Volatile
    var types: Set<TetheringType> = emptySet()
        private set

    @Synchronized
    fun add(type: TetheringType) {
        val next = types + type
        persist(next)
        types = next
    }

    @Synchronized
    fun clear() {
        // A new instance cannot clear ownership that it has not recovered yet.
        if (types.isEmpty()) return
        persist(emptySet())
        types = emptySet()
    }
}
