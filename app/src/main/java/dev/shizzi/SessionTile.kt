package dev.shizzi

import android.service.quicksettings.Tile

enum class TileAction { START, STOP, OPEN_APP, NONE }

data class TileRender(
    val state: Int,
    val subtitle: String,
    val action: TileAction,
)

object SessionTile {

    const val LABEL = "Shizzi"

    fun render(session: SessionUiState, helper: HelperState, isStopping: Boolean): TileRender {
        if (helper !is HelperState.Ready) return unavailable(helper)

        return when (session.status) {
            UiStatus.LOADING -> TileRender(
                state = Tile.STATE_UNAVAILABLE,
                subtitle = if (isStopping) "Stopping…" else "Starting…",
                action = TileAction.NONE,
            )

            UiStatus.CONNECTED -> TileRender(
                state = Tile.STATE_ACTIVE,
                subtitle = describeConnected(session),
                action = TileAction.STOP,
            )

            UiStatus.ERROR -> TileRender(
                state = Tile.STATE_INACTIVE,
                subtitle = "Tap to retry",
                action = TileAction.START,
            )

            UiStatus.READY -> TileRender(
                state = Tile.STATE_INACTIVE,
                subtitle = "Tap to share",
                action = TileAction.START,
            )
        }
    }

    private fun unavailable(helper: HelperState) = TileRender(
        state = Tile.STATE_UNAVAILABLE,
        subtitle = describe(helper),
        action = TileAction.OPEN_APP,
    )

    private fun describe(helper: HelperState): String = when (helper) {
        HelperState.NotInstalled -> "Helper setup needed"
        HelperState.NotRunning -> "Helper not running"
        HelperState.PermissionRequired -> "Permission required"
        is HelperState.Ready -> ""
    }

    private fun describeConnected(session: SessionUiState): String = when (session.clientCount) {
        0 -> "No devices"
        1 -> "1 device · ${Traffic.format(session.traffic.down)}"
        else -> "${session.clientCount} devices · ${Traffic.format(session.traffic.down)}"
    }
}
