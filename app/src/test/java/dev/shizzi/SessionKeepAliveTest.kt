package dev.shizzi

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionKeepAliveTest {
    @Test
    fun `settings changes and session transitions balance protection`() {
        var acquired = 0
        var released = 0
        val protection = SessionKeepAlive { acquired++; { released++ } }
        protection.update(true, false)
        assertEquals(0, acquired)
        protection.update(true, true)
        protection.update(true, true)
        assertEquals(1, acquired)
        protection.update(false, true)
        assertEquals(1, released)
        protection.update(true, true)
        protection.update(true, false)
        protection.update(false, false)
        assertEquals(2, acquired)
        assertEquals(2, released)
        protection.update(true, true)
        protection.close()
        protection.close()
        protection.update(true, true)
        assertEquals(3, acquired)
        assertEquals(3, released)
    }

    @Test
    fun `concurrent startup and settings callbacks acquire only once`() {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val protection = SessionKeepAlive { acquired.incrementAndGet(); { released.incrementAndGet(); Unit } }
        val executor = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        val tasks = (1..32).map {
            executor.submit { gate.await(); protection.update(true, true) }
        }
        try {
            gate.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            protection.update(false, false)
            assertEquals(1, acquired.get())
            assertEquals(1, released.get())
        } finally { executor.shutdownNow() }
    }
}
