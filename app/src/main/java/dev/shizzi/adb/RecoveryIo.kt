// Adapted from RikkaApps/Shizuku under Apache-2.0. Modified for standalone Shizzi.
package dev.shizzi.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Closing the connection lets cancellation interrupt a blocked socket operation. */
internal suspend fun recoveryIo(close: () -> Unit, operation: () -> Unit) = withContext(Dispatchers.IO) {
    suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { close() }
        val result = runCatching {
            try {
                if (!continuation.isActive) throw CancellationException("Recovery was cancelled")
                operation()
            } finally { close() }
        }
        continuation.resumeWith(result)
    }
}
