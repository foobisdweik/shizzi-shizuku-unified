package dev.shizzi

import android.content.Intent

// Readiness belongs to the dedicated helper packaged inside this app.
sealed interface HelperState {
    data object NotInstalled : HelperState
    data object NotRunning : HelperState
    data object PermissionRequired : HelperState
    data class Ready(val uid: Int, val isRoot: Boolean) : HelperState
}

object HelperGate {
    fun currentState(): HelperState = if (HelperBridge.isReady())
        HelperState.Ready(2000, false) else HelperState.NotRunning
    fun hasPermission() = HelperBridge.isReady()
    fun requestPermission() = remedy(currentState())
    fun remedy(state: HelperState) {
        App.instance.startActivity(Intent(App.instance, HelperSetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun describeUid(uid: Int) = "shell ($uid)"
    fun shortUid(uid: Int) = "$uid (shell)"
    fun installedVersion(): String = BuildConfig.VERSION_NAME
}
