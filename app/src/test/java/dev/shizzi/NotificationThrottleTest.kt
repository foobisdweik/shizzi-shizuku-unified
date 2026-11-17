package dev.shizzi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationThrottleTest {

    private val throttle = NotificationThrottle(trafficIntervalMs = 10_000L)

    @Test
    fun `posts the first update`() {
        assertTrue(throttle.shouldPost("Connected\n1 device", "a", now = 0))
    }

    @Test
    fun `skips an update that changes nothing`() {
        throttle.shouldPost("k", "a", now = 0)

        assertFalse(throttle.shouldPost("k", "a", now = 60_000))
    }

    @Test
    fun `holds traffic-only changes until the interval passes`() {
        throttle.shouldPost("k", "a", now = 0)

        assertFalse(throttle.shouldPost("k", "b", now = 1_000))
        assertFalse(throttle.shouldPost("k", "c", now = 9_999))
        assertTrue(throttle.shouldPost("k", "d", now = 10_000))
    }

    @Test
    fun `posts a key change at once`() {
        throttle.shouldPost("Connected\n1 device", "a", now = 0)

        assertTrue(throttle.shouldPost("Connected\n2 devices", "b", now = 1_000))
    }

    @Test
    fun `a recorded post restarts the interval`() {
        throttle.posted("k", "a", now = 5_000)

        assertFalse(throttle.shouldPost("k", "b", now = 14_999))
        assertTrue(throttle.shouldPost("k", "b", now = 15_000))
    }
}
