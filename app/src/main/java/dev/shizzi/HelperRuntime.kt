package dev.shizzi

import android.content.Context
import dev.shizzi.adb.HelperAdb
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes startup so diagnostics and recovery cannot launch competing helpers. */
object HelperRuntime {
    private val startup = Mutex()
    val status = MutableStateFlow("Open helper setup to pair or start the helper")
    suspend fun ensureHelper(context: Context): ITetherService = startup.withLock {
        if (HelperBridge.isReady()) return@withLock HelperBridge.awaitService()
        status.value = "Starting the helper"
        withTimeout(15_000) {
            HelperAdb.launch(context, HelperCommands.launch(context))
            HelperBridge.awaitService().also { status.value = "The helper is running" }
        }
    }
}
