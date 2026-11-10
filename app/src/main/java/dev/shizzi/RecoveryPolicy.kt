package dev.shizzi

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Stops finite retries and chooses one delay without changing the session watchdog. */
object RecoveryPolicy {
    /** A nested attempt can time out while the protection worker itself remains active. */
    suspend fun checkCancellation() {
        currentCoroutineContext().ensureActive()
    }

    fun delayMillis(failures: Int, fast: Boolean): Long {
        val schedule = if (fast) longArrayOf(2, 5, 15, 30, 60) else longArrayOf(15, 30, 60, 120)
        return schedule[failures.coerceIn(0, schedule.lastIndex)] * 1_000
    }
    fun shouldResume(requested: Boolean, active: Boolean, stopping: Boolean, enabled: Boolean, resumeEnabled: Boolean) =
        requested && !active && !stopping && enabled && resumeEnabled
    fun mayRetry(failures: Int, limit: Int) = limit == 0 || failures < limit
}
