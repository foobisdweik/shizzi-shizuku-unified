package dev.shizzi

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class UiStatus { READY, LOADING, CONNECTED, ERROR }

data class SessionUiState(
    val helperState: HelperState = HelperState.NotRunning,
    val status: UiStatus = UiStatus.READY,
    val isBusy: Boolean = false,
    val detail: String = "",
    val interfaceName: String = "",
    val lastError: String = "",
    val isVpnBound: Boolean = false,
    val isVpnBypassed: Boolean = false,
    val isWaitingForUsb: Boolean = false,

    val clientCount: Int = 0,

    val traffic: Traffic = Traffic(),
) {

    val canStart: Boolean get() = helperState is HelperState.Ready && !isBusy
}

fun SessionUiState.asStopped(): SessionUiState = copy(
    isBusy = false,
    status = UiStatus.READY,
    lastError = "",
    detail = "Stopped",
    interfaceName = "",
    isVpnBound = false,
    isVpnBypassed = false,
    isWaitingForUsb = false,
    clientCount = 0,
    traffic = Traffic(),
)

fun SessionUiState.applyOutcome(outcome: Result<String>): SessionUiState {
    val failure = outcome.exceptionOrNull()
    if (failure != null) {
        return copy(
            isBusy = false,
            status = UiStatus.ERROR,
            lastError = "${failure.javaClass.simpleName}: ${failure.message}",

            interfaceName = "",
            isVpnBound = false,
            isVpnBypassed = false,
            isWaitingForUsb = false,
            clientCount = 0,
            traffic = Traffic(),
        )
    }

    val parsed = runCatching { JSONObject(outcome.getOrDefault("{}")) }.getOrNull()
    val sessionState = parsed?.optString("state").orEmpty()
    val sessionDetail = parsed?.optString("detail").orEmpty()

    return copy(
        isBusy = false,
        status = statusFor(sessionState),
        detail = sessionDetail,
        interfaceName = parsed?.optString("interface").orEmpty().takeIf { it != "null" }.orEmpty(),
        lastError = if (sessionState == "ERROR") sessionDetail else "",

        isVpnBound = parsed?.optBoolean("isVpnBound") == true,
        isVpnBypassed = parsed?.optBoolean("isVpnBypassed") == true,
        isWaitingForUsb = parsed?.optBoolean("isWaitingForUsb") == true,
        clientCount = parsed?.optInt("clientCount") ?: 0,
        traffic = Traffic(
            up = parsed?.optLong("bytesUp") ?: 0,
            down = parsed?.optLong("bytesDown") ?: 0,
        ),
    )
}

private fun statusFor(sessionState: String): UiStatus = when (sessionState) {
    "ACTIVE" -> UiStatus.CONNECTED
    "ERROR" -> UiStatus.ERROR
    "STARTING" -> UiStatus.LOADING
    else -> UiStatus.READY
}

class TetherClient {

    private var boundService: ITetherService? = null
    private var pendingBind: CompletableDeferred<ITetherService>? = null

    var onSessionLost: (() -> Unit)? = null

    private var deathRecipient: IBinder.DeathRecipient? = null

    private suspend fun service(): ITetherService {
        boundService?.let { if (it.asBinder().pingBinder()) return it }
        val bound = HelperRuntime.ensureHelper(App.instance)
        verifyContract(bound)
        boundService = bound
        observeDeath(bound)
        return bound
    }

    private fun observeDeath(bound: ITetherService) {
        deathRecipient = null

        val binder = bound.asBinder()
        val recipient = IBinder.DeathRecipient {
            boundService = null
            onSessionLost?.invoke()
        }

        runCatching { binder.linkToDeath(recipient, 0) }
            .onSuccess { deathRecipient = recipient }
            .onFailure {

                boundService = null
            }
    }

    suspend fun runProbes(attemptTethering: Boolean): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.runProbes(attemptTethering, AVAILABILITY_TIMEOUT_MS)
    }

    suspend fun checkCompatibility(): List<CapabilityResult> = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        parseCapabilities(bound.checkCompatibility())
    }

    suspend fun installTetheringApex(path: String): StagingOutcome =
        withContext(Dispatchers.IO) {
            val bound = service()
            verifyContract(bound)

            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { apex -> parseStagingOutcome(bound.installTetheringApex(apex)) }
        }

    suspend fun rebootDevice(): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.rebootDevice()
    }

    suspend fun start(
        logging: Boolean,
        vpnMode: VpnMode,
        usbTethering: UsbTethering,
    ): String = withContext(Dispatchers.IO) {
        val bound = service()
        verifyContract(bound)
        bound.start(logging, vpnMode.name, usbTethering.name)
    }

    fun setLogging(enabled: Boolean) {
        runCatching { boundService?.setLogging(enabled) }
    }

    suspend fun stop(): String = withContext(Dispatchers.IO) {
        service().stop()
    }

    suspend fun clearLog(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bound = service()

            verifyContract(bound)
            bound.clearLog()
        }.fold(
            onSuccess = { null },
            onFailure = { failure -> failure.message ?: "could not reach the helper" },
        )
    }

    suspend fun releaseOrphanedDownstream(): String? = withContext(Dispatchers.IO) {
        runCatching { service().stop() }
            .fold(
                onSuccess = { null },
                onFailure = { failure ->
                    "could not reach the helper to drop the hotspot: ${failure.message}"
                },
            )
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        service().status
    }

    private fun verifyContract(bound: ITetherService) {
        val remote = bound.contractVersion
        check(remote == TetherService.CONTRACT_VERSION) {
            "The helper is stale: app is build ${TetherService.CONTRACT_VERSION}, " +
                "shell process reports $remote — press Stop then Start to reload it"
        }
    }

    fun unbind() = releaseBinding(shouldTerminate = false)

    fun unbindAndStopDaemon() = releaseBinding(shouldTerminate = true)

    private fun releaseBinding(shouldTerminate: Boolean) {
        deathRecipient?.let { recipient ->
            runCatching { boundService?.asBinder()?.unlinkToDeath(recipient, 0) }
        }
        deathRecipient = null

        // The protection service owns the helper lifetime. Session Stop only releases this client.
        boundService = null
    }

    private companion object {

        private const val SERVICE_TAG = "shizzi-session"

        const val BIND_TIMEOUT_MS = 35_000L

        const val AVAILABILITY_TIMEOUT_MS = 10_000
    }
}
