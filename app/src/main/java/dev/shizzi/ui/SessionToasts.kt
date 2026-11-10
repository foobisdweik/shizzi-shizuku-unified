package dev.shizzi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.shizzi.HelperState
import dev.shizzi.SessionUiState

@Composable
fun SessionToasts(
    state: SessionUiState,
    toasts: ToastState,
    onRequestPermission: () -> Unit,
) {
    ErrorToast(state.lastError, toasts)
    HelperToast(state.helperState, toasts, onRequestPermission)
}

@Composable
private fun ErrorToast(lastError: String, toasts: ToastState) {
    LaunchedEffect(lastError) {
        if (lastError.isEmpty()) {
            toasts.dismiss(ToastKeys.SESSION)
            return@LaunchedEffect
        }

        toasts.show(
            Toast(
                key = ToastKeys.SESSION,
                message = lastError,
                duration = ToastDuration.Indefinite,
            ),
        )
    }
}

@Composable
private fun HelperToast(
    state: HelperState,
    toasts: ToastState,
    onRequestPermission: () -> Unit,
) {
    LaunchedEffect(state) {
        val message = describe(state)
        if (message.isEmpty()) {
            toasts.dismiss(ToastKeys.HELPER)
            return@LaunchedEffect
        }

        toasts.show(
            Toast(
                key = ToastKeys.HELPER,
                message = message,
                duration = ToastDuration.Indefinite,
                action = ToastAction("Setup", onRequestPermission)
                    .takeIf { state !is HelperState.Ready },
            ),
        )
    }
}

private fun describe(state: HelperState): String = when (state) {
    is HelperState.NotInstalled -> "Set up Shizzi’s helper"
    is HelperState.NotRunning -> "Shizzi’s helper is not running. Open helper setup."
    is HelperState.PermissionRequired -> "Open helper setup to continue"
    is HelperState.Ready -> ""
}
