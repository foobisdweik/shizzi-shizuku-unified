package dev.shizzi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.shizzi.App
import dev.shizzi.ProtectionSettings
import dev.shizzi.Settings
import kotlinx.coroutines.launch

@Composable
fun ProtectionSettingsPanel() {
    val app = LocalContext.current.applicationContext as App
    val store = app.settingsStore
    val settings by store.settings.collectAsState(initial = Settings())
    val protection = settings.protection
    val scope = rememberCoroutineScope()
    val update: ((ProtectionSettings) -> ProtectionSettings) -> Unit = { transform ->
        scope.launch { store.updateProtection(transform) }
    }

    val status by dev.shizzi.HelperRuntime.status.collectAsState()
    SettingsLabel("Protection status", status)
    TextButton(onClick = { app.startActivity(android.content.Intent(app, dev.shizzi.HelperSetupActivity::class.java)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Helper setup and protection actions") }
    SettingsToggle(
        SettingsText("Protect while idle", "Shizzi keeps protection available without an active session. An ongoing notification remains visible."),
        protection.protectWhileIdle,
    ) { enabled -> update { it.copy(protectWhileIdle = enabled) } }
    SettingsToggle(
        SettingsText("Keep CPU awake during sessions", "A wake lock keeps the CPU awake while the screen is off. This uses more battery."),
        protection.wakeDuringSession,
    ) { enabled -> update { it.copy(wakeDuringSession = enabled) } }
    SettingsToggle(
        SettingsText("Keep CPU awake while idle", "This applies while idle protection is enabled. This can use substantial battery without tethering."),
        protection.wakeWhileIdle,
    ) { enabled -> update { it.copy(wakeWhileIdle = enabled) } }
    SettingsToggle(
        SettingsText("Recover the helper automatically", "Shizzi retries when the helper stops. Wireless recovery needs a real Wi-Fi connection and a valid pairing."),
        protection.autoRecover,
    ) { enabled -> update { it.copy(autoRecover = enabled) } }
    SettingsToggle(
        SettingsText("Resume interrupted tethering", "Shizzi restarts a session after recovery. A deliberate Stop cancels the restart."),
        protection.resumeInterrupted,
    ) { enabled -> update { it.copy(resumeInterrupted = enabled) } }
    SettingsToggle(
        SettingsText("Start protection after reboot", "Shizzi attempts to restore protection after you unlock the phone. Android may require you to open Shizzi."),
        protection.startAfterBoot,
    ) { enabled -> update { it.copy(startAfterBoot = enabled) } }
    SettingsToggle(
        SettingsText("Resume tethering after reboot", "Shizzi restarts the last requested session after unlocking, if protection and recovery are available."),
        protection.resumeAfterBoot,
    ) { enabled -> update { it.copy(resumeAfterBoot = enabled) } }
    ProtectionChoice(
        "Check the helper every", "Shorter intervals detect missed failures sooner and use more battery.",
        protection.healthIntervalSeconds, listOf(2, 5, 10, 30), { "$it seconds" },
    ) { seconds -> update { it.copy(healthIntervalSeconds = seconds) } }
    ProtectionChoice(
        "Recovery attempt limit", "After the limit, recovery waits for you to try again. Unlimited attempts continue with pauses.",
        protection.retryLimit, listOf(0, 3, 10), { if (it == 0) "Unlimited" else "$it attempts" },
    ) { limit -> update { it.copy(retryLimit = limit) } }
    SettingsToggle(
        SettingsText("Retry quickly after a failure", "Shizzi starts with short pauses, then increases them to 60 seconds. Turning this off increases pauses from 15 to 120 seconds."),
        protection.fastRetries,
    ) { enabled -> update { it.copy(fastRetries = enabled) } }
    SettingsToggle(
        SettingsText("Keep the screen on", "Shizzi prevents screen timeout while it is visible. This uses more battery and does not bypass the lock screen."),
        protection.keepScreenOn,
    ) { enabled -> update { it.copy(keepScreenOn = enabled) } }

    SectionLabel("Phone-wide changes")
    SettingsLabel(
        "These changes affect the phone", "Each option needs the helper or a granted permission. A saved preference does not prove that Android applied it.",
    )
    ConfirmedProtectionToggle(
        "Restore wireless debugging", "Shizzi enables wireless debugging when recovery needs it. Android can still refuse it without a Wi-Fi connection.",
        "Wireless debugging allows paired devices to run commands on this phone. Enable restoration only on networks you trust.",
        protection.restoreWirelessDebugging,
    ) { enabled -> update { it.copy(restoreWirelessDebugging = enabled) } }
    ConfirmedProtectionToggle(
        "Prevent debugging authorization expiry", "Shizzi prevents debugging authorizations from expiring automatically. This affects every authorized debugging device.",
        "Authorized debugging devices will remain trusted until you revoke them. This changes the phone's security settings.",
        protection.preventAuthorizationExpiry,
    ) { enabled -> update { it.copy(preventAuthorizationExpiry = enabled) } }
    ConfirmedProtectionToggle(
        "Stabilize USB with No data transfer", "Shizzi uses No data transfer to reduce USB changes during screen locking. This can interrupt USB connections and file transfer.",
        "Changing USB mode can disconnect debugging and interrupt the current session. USB file transfer will be unavailable while this applies.",
        protection.stabilizeUsb,
    ) { enabled -> update { it.copy(stabilizeUsb = enabled) } }
    ConfirmedProtectionToggle(
        "Disable light idle", "Light idle reduces background activity while the screen is off. Disabling it affects all apps and uses more battery.",
        "This changes power management for the whole phone. Other apps can run more often, so battery life may decrease.",
        protection.disableLightIdle,
    ) { enabled -> update { it.copy(disableLightIdle = enabled) } }
    ConfirmedProtectionToggle(
        "Disable deep idle", "Deep idle limits background activity while the phone remains unused. Disabling it affects all apps and uses more battery.",
        "This changes power management for the whole phone. Battery use can increase substantially while the phone remains unused.",
        protection.disableDeepIdle,
    ) { enabled -> update { it.copy(disableDeepIdle = enabled) } }
}

@Composable
private fun ConfirmedProtectionToggle(
    title: String,
    subtitle: String,
    explanation: String,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    SettingsToggle(SettingsText(title, subtitle), enabled) { requested ->
        if (requested) confirm = true else onChange(false)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(title) },
        text = { Text(explanation) },
        confirmButton = {
            TextButton(onClick = { confirm = false; onChange(true) }) { Text("Enable") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ProtectionChoice(
    title: String,
    subtitle: String,
    selected: Int,
    choices: List<Int>,
    describe: (Int) -> String,
    onChoose: (Int) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    SettingsChoice(SettingsText(title, subtitle), describe(selected)) { choosing = true }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(title) },
        text = {
            Column {
                choices.forEach { choice ->
                    TextButton(onClick = { choosing = false; onChoose(choice) }) {
                        Text(describe(choice) + if (choice == selected) " (selected)" else "")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
    )
}
