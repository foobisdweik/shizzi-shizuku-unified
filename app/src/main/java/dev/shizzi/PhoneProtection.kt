package dev.shizzi

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Dumpsys can repeat this field for each enabled function. An omitted field means zero. */
internal fun parseUnlockedUsbFunctions(dump: String): String {
    check(dump.contains("device_manager")) { "This phone does not expose USB state" }
    val values = Regex("screen_unlocked_functions\\s*[:=]\\s*([^\\s}]+)")
        .findAll(dump).flatMap { it.groupValues[1].trim(',', '[', ']').split(',').asSequence() }
        .map { it.lowercase(Locale.ROOT) }.filter { it !in listOf("", "none", "0") }.toSet()
    check(values.all { it in USB_FUNCTIONS }) { "Android exposed an unknown USB function" }
    return values.sorted().joinToString(",")
}

private val USB_FUNCTIONS = setOf("mtp", "ptp", "rndis", "ncm", "midi", "accessory", "audio_source", "adb")

/** Drain output while the child runs, because waiting first can fill its pipe and block it. */
internal fun captureProtectionCommand(vararg args: String): String {
    val process = ProcessBuilder(*args).redirectErrorStream(true).start()
    val retained = ByteArrayOutputStream()
    val readFailure = AtomicReference<Throwable?>()
    var truncated = false
    val reader = Thread({
        try {
            process.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val keep = minOf(count, 262_144 - retained.size())
                    if (keep > 0) retained.write(buffer, 0, keep)
                    if (keep < count) truncated = true
                }
            }
        } catch (failure: Exception) { readFailure.set(failure) }
    }, "protection-command-output").apply { isDaemon = true; start() }
    try {
        check(process.waitFor(4, TimeUnit.SECONDS)) { "Android did not finish ${args.first()} within four seconds" }
        reader.join(1_000)
        check(!reader.isAlive) { "Android did not close the output from ${args.first()}" }
        readFailure.get()?.let { throw IllegalStateException("Could not read Android's response", it) }
        check(!truncated) { "Android's response exceeded the supported size" }
        val output = retained.toByteArray().toString(Charsets.UTF_8)
        check(process.exitValue() == 0) { output.ifBlank { "Android rejected ${args.first()}" } }
        return output
    } finally {
        process.destroyForcibly()
        runCatching { process.inputStream.close() }
        reader.join(500)
    }
}

/** Only these fixed operations are available. Callers cannot supply shell commands. */
class PhoneProtection(private val context: Context) {
    fun execute(request: String): String {
        val json = JSONObject(request)
        val key = json.optString("key")
        return when (json.getString("op")) {
            "read" -> JSONObject().put("value", read(key) ?: JSONObject.NULL).toString()
            "write" -> {
                write(key, if (json.isNull("value")) null else json.getString("value"))
                JSONObject().put("ok", true).toString()
            }
            "grantRecoveryPermission" -> {
                command("pm", "grant", BuildConfig.APPLICATION_ID, "android.permission.WRITE_SECURE_SETTINGS")
                JSONObject().put("ok", true).toString()
            }
            else -> error("Unknown protection operation")
        }
    }

    private fun read(key: String): String? = when (key) {
        "lightIdle" -> idleEnabled("light")
        "deepIdle" -> idleEnabled("deep")
        "authorizationExpiry" -> command("settings", "get", "global", "adb_allowed_connection_time")
            .trim().takeUnless { it == "null" }
        "usbUnlocked" -> {
            parseUnlockedUsbFunctions(command("dumpsys", "usb"))
        }
        else -> error("Unsupported protection setting: $key")
    }

    private fun idleEnabled(kind: String): String {
        val result = command("cmd", "deviceidle", "enabled", kind).trim()
        check(result == "0" || result == "1") { "Android did not expose $kind idle state: $result" }
        return result
    }

    private fun write(key: String, value: String?) {
        when (key) {
            "lightIdle", "deepIdle" -> {
                require(value in listOf("0", "1"))
                val kind = if (key == "lightIdle") "light" else "deep"
                command("cmd", "deviceidle", if (value == "0") "disable" else "enable", kind)
                check(idleEnabled(kind) == value) { "Android did not apply $kind idle protection" }
            }
            "authorizationExpiry" -> {
                require(value == null || value.toLongOrNull()?.let { it >= 0 } == true)
                // Android's settings CLI supplies shell attribution. This process has no bound Application.
                if (value == null) command("settings", "delete", "global", "adb_allowed_connection_time")
                else command("settings", "put", "global", "adb_allowed_connection_time", value)
                check(read(key) == value) { "Android did not apply the authorization timeout" }
            }
            "usbUnlocked" -> {
                val functions = value.orEmpty().lowercase(Locale.ROOT).split(',')
                    .filter { it !in listOf("", "none", "0") }.toSet().sorted().joinToString(",")
                require(functions.split(',').all { it.isEmpty() || it in USB_FUNCTIONS })
                if (functions.isBlank()) command("svc", "usb", "setScreenUnlockedFunctions")
                else command("svc", "usb", "setScreenUnlockedFunctions", functions)
                check(read(key).orEmpty() == functions) { "Android did not apply the USB mode" }
            }
            else -> error("Unsupported protection setting: $key")
        }
    }

    private fun command(vararg args: String): String = captureProtectionCommand(*args)
}
