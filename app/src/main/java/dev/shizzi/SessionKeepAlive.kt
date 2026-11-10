package dev.shizzi

/** Owns one request to keep the CPU awake. Updates can arrive from settings and session callbacks. */
internal class SessionKeepAlive(private val acquire: () -> (() -> Unit)) {
    private var closed = false
    private var release: (() -> Unit)? = null

    @Synchronized
    fun update(enabled: Boolean, active: Boolean) {
        if (closed) return
        if (enabled && active) {
            if (release == null) release = acquire()
        } else {
            release?.invoke()
            release = null
        }
    }

    @Synchronized
    fun close() {
        update(enabled = false, active = false)
        closed = true
    }
}
