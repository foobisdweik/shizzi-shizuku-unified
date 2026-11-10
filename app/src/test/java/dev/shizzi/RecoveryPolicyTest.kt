package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun `an interrupted requested session can resume when protection allows it`() {
        assertTrue(RecoveryPolicy.shouldResume(
            requested = true, active = false, stopping = false, enabled = true, resumeEnabled = true,
        ))
    }

    @Test
    fun `a completed or pending Stop prevents automatic resumption`() {
        assertFalse(RecoveryPolicy.shouldResume(
            requested = false, active = false, stopping = false, enabled = true, resumeEnabled = true,
        ))
        assertFalse(RecoveryPolicy.shouldResume(
            requested = true, active = false, stopping = true, enabled = true, resumeEnabled = true,
        ))
    }

    @Test
    fun `a healthy session and disabled protection cannot trigger duplicate startup`() {
        assertFalse(RecoveryPolicy.shouldResume(
            requested = true, active = true, stopping = false, enabled = true, resumeEnabled = true,
        ))
        assertFalse(RecoveryPolicy.shouldResume(
            requested = true, active = false, stopping = false, enabled = false, resumeEnabled = true,
        ))
        assertFalse(RecoveryPolicy.shouldResume(
            requested = true, active = false, stopping = false, enabled = true, resumeEnabled = false,
        ))
    }

    @Test
    fun `fast retries begin promptly then settle at one minute`() {
        assertEquals(
            listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 60_000L),
            (0..5).map { RecoveryPolicy.delayMillis(it, fast = true) },
        )
    }

    @Test
    fun `slower retries settle at two minutes`() {
        assertEquals(
            listOf(15_000L, 30_000L, 60_000L, 120_000L, 120_000L),
            (0..4).map { RecoveryPolicy.delayMillis(it, fast = false) },
        )
    }

    @Test
    fun `unexpected failure counters cannot index outside the delay schedule`() {
        assertEquals(2_000L, RecoveryPolicy.delayMillis(Int.MIN_VALUE, fast = true))
        assertEquals(60_000L, RecoveryPolicy.delayMillis(Int.MAX_VALUE, fast = true))
        assertEquals(15_000L, RecoveryPolicy.delayMillis(Int.MIN_VALUE, fast = false))
        assertEquals(120_000L, RecoveryPolicy.delayMillis(Int.MAX_VALUE, fast = false))
    }

    @Test
    fun `a finite limit allows exactly its number of attempts`() {
        for (limit in listOf(3, 10)) {
            for (failures in 0 until limit) {
                assertTrue(RecoveryPolicy.mayRetry(failures, limit))
            }
            assertFalse(RecoveryPolicy.mayRetry(limit, limit))
            assertFalse(RecoveryPolicy.mayRetry(limit + 1, limit))
        }
    }

    @Test
    fun `zero keeps recovery available after many failures`() {
        assertTrue(RecoveryPolicy.mayRetry(0, 0))
        assertTrue(RecoveryPolicy.mayRetry(10, 0))
        assertTrue(RecoveryPolicy.mayRetry(Int.MAX_VALUE, 0))
    }
}
