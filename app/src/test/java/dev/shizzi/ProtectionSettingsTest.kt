package dev.shizzi

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionSettingsTest {
    @Test
    fun `a fresh install uses maximum app protection without changing phone-wide settings`() {
        val settings = toSettings(preferencesOf()).protection

        assertTrue(settings.protectWhileIdle)
        assertTrue(settings.wakeDuringSession)
        assertTrue(settings.wakeWhileIdle)
        assertTrue(settings.autoRecover)
        assertTrue(settings.resumeInterrupted)
        assertTrue(settings.startAfterBoot)
        assertTrue(settings.resumeAfterBoot)
        assertTrue(settings.fastRetries)
        assertEquals(5, settings.healthIntervalSeconds)
        assertEquals(0, settings.retryLimit)
        assertFalse(settings.keepScreenOn)
        assertFalse(settings.restoreWirelessDebugging)
        assertFalse(settings.preventAuthorizationExpiry)
        assertFalse(settings.stabilizeUsb)
        assertFalse(settings.disableLightIdle)
        assertFalse(settings.disableDeepIdle)
    }

    @Test
    fun `all explicit choices survive saving and loading`() {
        val choices = ProtectionSettings(
            protectWhileIdle = false,
            wakeDuringSession = false,
            wakeWhileIdle = false,
            autoRecover = false,
            resumeInterrupted = false,
            startAfterBoot = false,
            resumeAfterBoot = false,
            healthIntervalSeconds = 30,
            retryLimit = 10,
            fastRetries = false,
            keepScreenOn = true,
            restoreWirelessDebugging = true,
            preventAuthorizationExpiry = true,
            stabilizeUsb = true,
            disableLightIdle = true,
            disableDeepIdle = true,
        )
        val stored = mutablePreferencesOf().apply { writeProtection(choices) }

        assertEquals(choices, toSettings(stored).protection)
        assertFalse(toSettings(stored).aggressiveKeepAlive)
    }

    @Test
    fun `an earlier wake opt out survives the standalone upgrade`() {
        val upgraded = toSettings(preferencesOf(KEEP_ALIVE to false))

        assertFalse(upgraded.aggressiveKeepAlive)
        assertFalse(upgraded.protection.wakeDuringSession)
        assertTrue(upgraded.protection.wakeWhileIdle)

        val stored = mutablePreferencesOf(KEEP_ALIVE to false)
        stored.writeProtection(upgraded.protection.copy(retryLimit = 3))
        assertFalse(toSettings(stored).protection.wakeDuringSession)
    }

    @Test
    fun `valid intervals and retry limits remain available after saving`() {
        for (interval in listOf(2, 5, 10, 30)) {
            for (limit in listOf(0, 3, 10)) {
                val choices = ProtectionSettings(healthIntervalSeconds = interval, retryLimit = limit)
                val stored = mutablePreferencesOf().apply { writeProtection(choices) }
                assertEquals(choices, readProtection(stored))
            }
        }
    }

    @Test
    fun `invalid values cannot create a busy loop or a negative retry budget`() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 1, 4, Int.MAX_VALUE)) {
            val stored = mutablePreferencesOf().apply {
                writeProtection(ProtectionSettings(healthIntervalSeconds = invalid, retryLimit = invalid))
            }
            assertEquals(5, readProtection(stored).healthIntervalSeconds)
            assertEquals(0, readProtection(stored).retryLimit)
        }
    }

    @Test
    fun `malformed stored numeric choices return to supported defaults`() {
        val stored = preferencesOf(
            intPreferencesKey("protection_health_seconds") to 0,
            intPreferencesKey("protection_retry_limit") to -2,
        )
        assertEquals(5, readProtection(stored).healthIntervalSeconds)
        assertEquals(0, readProtection(stored).retryLimit)
    }
}
