package dev.shizzi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shizzi.AppPermission
import dev.shizzi.PermissionStatus
import dev.shizzi.HelperState
import dev.shizzi.ui.theme.ShizziTheme

data class PermissionsSectionState(
    val helper: HelperState,
    val permissions: List<PermissionStatus>,
)

@Composable
fun PermissionsSection(
    state: PermissionsSectionState,
    onGrantPermission: (AppPermission) -> Unit,
    onHelperAction: () -> Unit,
) {
    Box(modifier = Modifier.padding(vertical = ShizziTheme.spacing.md)) {
        HelperCard(state = state.helper, onGrant = onHelperAction)
    }

    val rows = permissionRows(
        sources = PermissionRowSources(
            helper = state.helper,
            permissions = state.permissions,
        ),
        onGrantPermission = onGrantPermission,
        onHelperAction = onHelperAction,
    )

    rows.forEach { row -> PermissionSettingsRow(row) }
}

@Composable
private fun PermissionSettingsRow(row: PermissionRowState) {
    val label = SettingsText(title = row.title, subtitle = row.rationale)

    if (row.isGranted) {
        SettingsStatusRow(label = label)
        return
    }

    SettingsAction(label = label, onClick = row.onAct)
}
