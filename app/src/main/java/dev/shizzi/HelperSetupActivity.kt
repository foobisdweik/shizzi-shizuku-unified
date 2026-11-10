package dev.shizzi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.shizzi.adb.HelperAdb
import kotlinx.coroutines.launch

/** Initial Android pairing can need Developer options. Later starts remain inside Shizzi. */
class HelperSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val scope = rememberCoroutineScope()
                        val status by HelperRuntime.status.collectAsState()
                        val helper by HelperBridge.service.collectAsState()
                        var port by remember { mutableStateOf("") }
                        var code by remember { mutableStateOf("") }
                        var busy by remember { mutableStateOf(false) }
                        fun runOperation(operation: suspend () -> Unit) {
                            scope.launch {
                                busy = true
                                try { operation() } catch (e: Exception) {
                                    RecoveryPolicy.checkCancellation()
                                    HelperRuntime.status.value = "${e.javaClass.simpleName}: ${e.message}"
                                } finally { busy = false }
                            }
                        }
                        Text("Shizzi helper", style = MaterialTheme.typography.headlineMedium)
                        Text(if (helper?.asBinder()?.pingBinder() == true) "The helper is connected" else "The helper is not connected")
                        Text(status)
                        Text("Shizzi has its own helper. You do not need the Shizuku app.")
                        Text("For initial setup, connect the phone to Wi-Fi. Open Developer options, enable Wireless debugging, then choose Pair device with pairing code.")
                        Text("Use split screen to keep the pairing dialog open beside Shizzi. Enter its pairing port and six-digit code below. These differ from the connection port.")
                        Button(onClick = {
                            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                                .onFailure { HelperRuntime.status.value = "Open Developer options in Android Settings" }
                        }) { Text("Open Developer options") }
                        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Pairing port") }, enabled = !busy)
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Pairing code") },
                            visualTransformation = PasswordVisualTransformation(), enabled = !busy)
                        Button(enabled = !busy, onClick = { runOperation {
                            HelperAdb.pair(this@HelperSetupActivity, port.toIntOrNull() ?: 0, code)
                            code = ""
                            HelperRuntime.status.value = "Pairing succeeded. Starting the helper."
                            SessionService.enableProtection(this@HelperSetupActivity)
                            HelperRuntime.ensureHelper(this@HelperSetupActivity)
                        } }) { Text("Pair and start helper") }
                        Button(enabled = !busy, onClick = { runOperation {
                            SessionService.enableProtection(this@HelperSetupActivity)
                            HelperRuntime.ensureHelper(this@HelperSetupActivity)
                        } }) { Text("Start paired helper") }
                        Text("Without Wi-Fi, connect this phone to a computer with USB debugging authorized. Run the copied command there.")
                        OutlinedButton(onClick = {
                            SessionService.enableProtection(this@HelperSetupActivity)
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Start Shizzi helper", HelperCommands.usb(this@HelperSetupActivity)))
                            HelperRuntime.status.value = "The USB startup command was copied"
                        }) { Text("Copy USB startup command") }
                        Text("If an older build left the hotspot on, turn it off before the first standalone session. Shizzi cannot establish ownership of that older hotspot.")
                        Button(onClick = { SessionService.enableProtection(this@HelperSetupActivity) }) { Text("Start protection") }
                        OutlinedButton(onClick = { SessionService.stopAll(this@HelperSetupActivity) }) { Text("Stop tethering and all protection") }
                        OutlinedButton(onClick = { finish() }) { Text("Return to Shizzi") }
                    }
                }
            }
        }
    }
}
