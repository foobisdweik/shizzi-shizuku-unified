package dev.shizzi

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Writes original values before any phone-wide change. Failed restoration stays pending. */
class ProtectionJournal(private val context: Context) {
    private val prefs = context.getSharedPreferences("protection_journal", Context.MODE_PRIVATE)
    val pending: Boolean get() = prefs.all.isNotEmpty()

    suspend fun reconcile(service: ITetherService, settings: Settings, enabled: Boolean): List<String> = withContext(Dispatchers.IO) {
        val p = settings.protection
        val desired = mapOf(
            "lightIdle" to if (enabled && p.disableLightIdle) "0" else null,
            "deepIdle" to if (enabled && p.disableDeepIdle) "0" else null,
            "authorizationExpiry" to if (enabled && p.preventAuthorizationExpiry) "0" else null,
            "usbUnlocked" to if (enabled && p.stabilizeUsb && settings.usbTethering == UsbTethering.NEVER) "" else null,
        )
        val failures = mutableListOf<String>()
        desired.forEach { (key, value) ->
            try {
                val saved = prefs.getString(key, null)?.let(::JSONObject)
                if (value == null && saved == null) return@forEach
                val current = remoteRead(service, key)
                if (value != null) {
                    // A user or another tool changed this value. Do not overwrite it again.
                    if (saved != null && current != saved.nullable("applied")) {
                        failures += "$key changed outside Shizzi. Disable its control before applying it again."
                    } else if (current != value) {
                        if (key == "usbUnlocked" && (SessionService.isSessionUp || SessionService.isSessionBusy)) {
                            failures += "Stop tethering before changing USB mode"
                        } else {
                            if (saved == null) check(prefs.edit().putString(key, JSONObject()
                                .put("original", current ?: JSONObject.NULL).put("applied", value).toString()).commit())
                            remoteWrite(service, key, value)
                        }
                    }
                } else if (saved != null) {
                    if (key == "usbUnlocked" && (SessionService.isSessionUp || SessionService.isSessionBusy)) {
                        failures += "Stop tethering before restoring USB mode"
                        return@forEach
                    }
                    if (current == saved.nullable("applied")) remoteWrite(service, key, saved.nullable("original"))
                    check(prefs.edit().remove(key).commit())
                }
            } catch (e: Exception) { failures += "$key: ${e.message}" }
        }
        if (settings.usbTethering != UsbTethering.NEVER && p.stabilizeUsb) failures += "USB stabilization requires USB tethering to be Never"
        failures
    }

    suspend fun wirelessPermission(service: ITetherService) = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED)
            service.protection(JSONObject().put("op", "grantRecoveryPermission").toString())
    }

    /** Recovery can need this before a shell helper exists. Preserve both values first. */
    fun enableWireless() {
        require(context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            "Start the helper once to grant wireless recovery permission"
        }
        for (key in listOf("adb_enabled", "adb_wifi_enabled")) {
            val name = "global:$key"
            val current = AndroidSettings.Global.getString(context.contentResolver, key)
            if (!prefs.contains(name) && current != "1") check(prefs.edit().putString(name,
                JSONObject().put("original", current ?: JSONObject.NULL).put("applied", "1").toString()).commit())
            check(AndroidSettings.Global.putString(context.contentResolver, key, "1"))
        }
    }

    fun restoreWireless(): String? = runCatching {
        for (key in listOf("adb_wifi_enabled", "adb_enabled")) {
            val name = "global:$key"
            val saved = prefs.getString(name, null)?.let(::JSONObject) ?: continue
            if (AndroidSettings.Global.getString(context.contentResolver, key) == "1")
                check(AndroidSettings.Global.putString(context.contentResolver, key, saved.nullable("original")))
            check(prefs.edit().remove(name).commit())
        }
    }.exceptionOrNull()?.let { "Wireless settings still need restoration: ${it.message}" }

    private fun remoteRead(service: ITetherService, key: String): String? = JSONObject(service.protection(
        JSONObject().put("op", "read").put("key", key).toString())).nullable("value")
    private fun remoteWrite(service: ITetherService, key: String, value: String?) {
        service.protection(JSONObject().put("op", "write").put("key", key).put("value", value ?: JSONObject.NULL).toString())
    }
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
}
