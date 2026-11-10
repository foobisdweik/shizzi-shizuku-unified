package dev.shizzi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCancellationTest {
    @Test
    fun `an attempt timeout permits recovery to continue in its active worker`() = runBlocking {
        var timedOut = false
        var continued = false
        try {
            withTimeout(50) { delay(Long.MAX_VALUE) }
        } catch (error: TimeoutCancellationException) {
            timedOut = true
            RecoveryPolicy.checkCancellation()
        }
        // A further suspension proves that the outer worker remains usable.
        delay(1)
        continued = true

        assertTrue(timedOut)
        assertTrue(continued)
    }

    @Test
    fun `stopping the worker rethrows cancellation instead of continuing recovery`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var continued = false
        var rejected: CancellationException? = null
        val worker = launch {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } catch (error: CancellationException) {
                try {
                    RecoveryPolicy.checkCancellation()
                    continued = true
                } catch (cancelled: CancellationException) {
                    rejected = cancelled
                    throw cancelled
                }
            }
        }

        entered.await()
        worker.cancelAndJoin()

        assertTrue(worker.isCancelled)
        assertFalse(continued)
        assertNotNull(rejected)
    }
}
