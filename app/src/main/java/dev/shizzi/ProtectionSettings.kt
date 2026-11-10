package dev.shizzi

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/** Controls protection for this app and optional changes that affect the phone. */
data class ProtectionSettings(
    val protectWhileIdle: Boolean = true,
    val wakeDuringSession: Boolean = true,
    val wakeWhileIdle: Boolean = true,
    val autoRecover: Boolean = true,
    val resumeInterrupted: Boolean = true,
    val startAfterBoot: Boolean = true,
    val resumeAfterBoot: Boolean = true,
    val healthIntervalSeconds: Int = 5,
    val retryLimit: Int = 0,
    val fastRetries: Boolean = true,
    val keepScreenOn: Boolean = false,
    val restoreWirelessDebugging: Boolean = false,
    val preventAuthorizationExpiry: Boolean = false,
    val stabilizeUsb: Boolean = false,
    val disableLightIdle: Boolean = false,
    val disableDeepIdle: Boolean = false,
)

private val IDLE_PROTECTION = booleanPreferencesKey("protection_idle")
private val IDLE_WAKE = booleanPreferencesKey("protection_idle_wake")
private val RECOVER = booleanPreferencesKey("protection_recover")
private val RESUME = booleanPreferencesKey("protection_resume")
private val BOOT = booleanPreferencesKey("protection_boot")
private val BOOT_RESUME = booleanPreferencesKey("protection_boot_resume")
private val INTERVAL = intPreferencesKey("protection_health_seconds")
private val RETRIES = intPreferencesKey("protection_retry_limit")
private val FAST = booleanPreferencesKey("protection_fast_retries")
private val SCREEN = booleanPreferencesKey("protection_screen")
private val WIRELESS = booleanPreferencesKey("protection_wireless")
private val AUTHORIZATION = booleanPreferencesKey("protection_authorization")
private val USB = booleanPreferencesKey("protection_usb")
private val LIGHT_IDLE = booleanPreferencesKey("protection_light_idle")
private val DEEP_IDLE = booleanPreferencesKey("protection_deep_idle")

internal fun readProtection(preferences: Preferences) = ProtectionSettings(
    protectWhileIdle = preferences[IDLE_PROTECTION] ?: true,
    // Reuse the existing key, so an earlier opt-out survives this upgrade.
    wakeDuringSession = preferences[KEEP_ALIVE] ?: true,
    wakeWhileIdle = preferences[IDLE_WAKE] ?: true,
    autoRecover = preferences[RECOVER] ?: true,
    resumeInterrupted = preferences[RESUME] ?: true,
    startAfterBoot = preferences[BOOT] ?: true,
    resumeAfterBoot = preferences[BOOT_RESUME] ?: true,
    healthIntervalSeconds = preferences[INTERVAL]?.takeIf { it in listOf(2, 5, 10, 30) } ?: 5,
    retryLimit = preferences[RETRIES]?.takeIf { it in listOf(0, 3, 10) } ?: 0,
    fastRetries = preferences[FAST] ?: true,
    keepScreenOn = preferences[SCREEN] ?: false,
    restoreWirelessDebugging = preferences[WIRELESS] ?: false,
    preventAuthorizationExpiry = preferences[AUTHORIZATION] ?: false,
    stabilizeUsb = preferences[USB] ?: false,
    disableLightIdle = preferences[LIGHT_IDLE] ?: false,
    disableDeepIdle = preferences[DEEP_IDLE] ?: false,
)

internal fun MutablePreferences.writeProtection(settings: ProtectionSettings) {
    this[IDLE_PROTECTION] = settings.protectWhileIdle
    this[KEEP_ALIVE] = settings.wakeDuringSession
    this[IDLE_WAKE] = settings.wakeWhileIdle
    this[RECOVER] = settings.autoRecover
    this[RESUME] = settings.resumeInterrupted
    this[BOOT] = settings.startAfterBoot
    this[BOOT_RESUME] = settings.resumeAfterBoot
    this[INTERVAL] = settings.healthIntervalSeconds.takeIf { it in listOf(2, 5, 10, 30) } ?: 5
    this[RETRIES] = settings.retryLimit.takeIf { it in listOf(0, 3, 10) } ?: 0
    this[FAST] = settings.fastRetries
    this[SCREEN] = settings.keepScreenOn
    this[WIRELESS] = settings.restoreWirelessDebugging
    this[AUTHORIZATION] = settings.preventAuthorizationExpiry
    this[USB] = settings.stabilizeUsb
    this[LIGHT_IDLE] = settings.disableLightIdle
    this[DEEP_IDLE] = settings.disableDeepIdle
}
