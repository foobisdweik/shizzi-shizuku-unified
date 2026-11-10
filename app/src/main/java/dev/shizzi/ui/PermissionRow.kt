package dev.shizzi.ui

import dev.shizzi.AppPermission
import dev.shizzi.PermissionStatus
import dev.shizzi.HelperState
import dev.shizzi.rationale
import dev.shizzi.title

data class PermissionRowState(
    val title: String,
    val rationale: String,
    val isGranted: Boolean,
    val onAct: () -> Unit,
)

data class PermissionRowSources(
    val helper: HelperState,
    val permissions: List<PermissionStatus>,
)

fun permissionRows(
    sources: PermissionRowSources,
    onGrantPermission: (AppPermission) -> Unit,
    onHelperAction: () -> Unit,
): List<PermissionRowState> {
    val helper = shizukuRow(state = sources.helper, onAct = onHelperAction)

    return listOf(helper) + sources.permissions.map { status ->
        PermissionRowState(
            title = status.permission.title,
            rationale = status.permission.rationale,
            isGranted = status.isGranted,
            onAct = { onGrantPermission(status.permission) },
        )
    }
}

private const val SHIZUKU_RATIONALE = "Required for core functionality"

private fun shizukuRow(state: HelperState, onAct: () -> Unit) = PermissionRowState(
    title = "Shizzi helper",
    rationale = SHIZUKU_RATIONALE,
    isGranted = state is HelperState.Ready,
    onAct = onAct,
)
