package dev.shizzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.shizzi.adb.HelperAdb
import dev.shizzi.adb.AdbPairingRequiredException
import dev.shizzi.adb.AdbKeyException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = TetherClient()
    private val notification by lazy { SessionNotification(this) }
    private val statusPoller = SessionStatusPoller(scope, controller)

    private val internalState get() = sessionState

    private val keepAlive = SessionKeepAlive {
        val lock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Shizzi:session")
            .apply { setReferenceCounted(false); acquire() }
        val release: () -> Unit = { if (lock.isHeld) lock.release() }
        release
    }

    private fun releaseWakeLock() = keepAlive.update(enabled = false, active = false)

    private var isStopping = false
    private var protectionJob: Job? = null
    private val protectionEvents = Channel<Unit>(Channel.CONFLATED)
    private val phoneJournal by lazy { ProtectionJournal(this) }
    private var recoverAfter = 0L
    private var failures = 0
    private var pausedForPairing = false
    private var protectionEnabled = true


    private var reportTo: AutomationCommand? = null

    private var startJob: Job? = null
    private var stopJob: Job? = null
    private var protectionToStop: Job? = null

    private var generation = 0

    private val sessionLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        controller.onSessionLost = { scope.launch { handleSessionLost() } }
        liveService = this
        scope.launch { HelperBridge.service.collect { protectionEvents.trySend(Unit) } }
        scope.launch { settingsStore().settings.collect { protectionEvents.trySend(Unit) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reportTo = intent?.getStringExtra(EXTRA_REPORT_AS)
            ?.let { runCatching { AutomationCommand.valueOf(it) }.getOrNull() }
        val action = intent?.action
        if (action == ACTION_STOP_ALL) {
            protectionEnabled = false
            protectionEvents.trySend(Unit)
            protectionToStop = protectionJob
            protectionToStop?.cancel()
            protectionJob = null
            getSharedPreferences("runtime", MODE_PRIVATE).edit().putBoolean("protectionStopped", true).commit()
            setRequested(this, false)
            stopSession()
        } else {
            protectionEnabled = !getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("protectionStopped", false)
            if (action == ACTION_PROTECT || action == null) {
                isStopping = false
            } else if (action == ACTION_STOP) {
                setRequested(this, false)
                isStopping = true
                stopSession()
            } else {
                setRequested(this, true)
                isStopping = false
                startSession()
            }
        }
        protectionEvents.trySend(Unit)

        startForeground(NOTIFICATION_ID, notification.build(internalState.value, isStopping))
        if (action != ACTION_STOP_ALL && protectionJob?.isActive != true) protectionJob = scope.launch { protectLoop() }
        return if (action == ACTION_STOP_ALL) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun protectLoop() {
        while (scope.isActive) {
            try {
                val settings = settingsStore().settings.first()
                val p = settings.protection
                val wanted = isRequested(this)
                val active = internalState.value.status in listOf(UiStatus.LOADING, UiStatus.CONNECTED)
                val protecting = protectionEnabled && (p.protectWhileIdle || wanted || active)
                keepAlive.update(protecting && (if (wanted || active) p.wakeDuringSession else p.wakeWhileIdle), protecting)
                if (!protecting) {
                    sessionLock.withLock { restorePhoneSettings(settings) }
                    if (!active && !wanted) { stopSelf(); return }
                } else {
                    if (HelperBridge.isReady()) {
                        val helper = HelperBridge.awaitService()
                        failures = 0
                        pausedForPairing = false
                        if (p.restoreWirelessDebugging) phoneJournal.wirelessPermission(helper)
                        val warnings = sessionLock.withLock { phoneJournal.reconcile(helper, settings, enabled = true) }
                        if (!p.restoreWirelessDebugging) phoneJournal.restoreWireless()?.let { HelperRuntime.status.value = it }
                        HelperRuntime.status.value = if (warnings.isEmpty()) "The helper is running" else warnings.joinToString("\n")
                        if (RecoveryPolicy.shouldResume(isRequested(this),
                            internalState.value.status in listOf(UiStatus.LOADING, UiStatus.CONNECTED),
                            isStopping, protectionEnabled, p.resumeInterrupted || getSharedPreferences("runtime", MODE_PRIVATE)
                                .getBoolean("resumeAfterBootPending", false))) startSession(isRecovery = true)
                    } else if (p.autoRecover && !pausedForPairing && RecoveryPolicy.mayRetry(failures, p.retryLimit)
                        && android.os.SystemClock.elapsedRealtime() >= recoverAfter) {
                        if (!HelperAdb.hasRealWifi(this)) {
                            HelperRuntime.status.value = "Recovery needs a real Wi-Fi connection. The hotspot alone is insufficient."
                        } else {
                            try {
                                if (p.restoreWirelessDebugging) phoneJournal.enableWireless()
                                HelperRuntime.ensureHelper(this)
                            } catch (e: Exception) {
                                RecoveryPolicy.checkCancellation()
                                HelperRuntime.status.value = "Recovery failed: ${e.message}"
                                pausedForPairing = e is AdbPairingRequiredException || e is AdbKeyException
                                recoverAfter = android.os.SystemClock.elapsedRealtime() + RecoveryPolicy.delayMillis(failures, p.fastRetries)
                                failures++
                            }
                        }
                    } else if (failures > 0 && !RecoveryPolicy.mayRetry(failures, p.retryLimit)) {
                        HelperRuntime.status.value = "Recovery reached its retry limit. Open helper setup to retry."
                    }
                }
                notificationManager().notify(NOTIFICATION_ID, notification.build(internalState.value, isStopping))
                withTimeoutOrNull(p.healthIntervalSeconds * 1_000L) { protectionEvents.receive() }
            } catch (e: Exception) {
                RecoveryPolicy.checkCancellation()
                HelperRuntime.status.value = "Protection needs attention: ${e.message}"
                delay(5_000)
            }
        }
    }

    private suspend fun restorePhoneSettings(settings: Settings) {
        if (HelperBridge.isReady()) {
            val warnings = phoneJournal.reconcile(HelperBridge.awaitService(), settings, enabled = false)
            if (warnings.isNotEmpty()) HelperRuntime.status.value = warnings.joinToString("\n")
        } else if (phoneJournal.pending) {
            HelperRuntime.status.value = "Some phone settings still need restoration. Start the helper to restore them."
        }
        phoneJournal.restoreWireless()?.let { HelperRuntime.status.value = it }
    }

    private fun startSession(isRecovery: Boolean = false) {
        if (internalState.value.isBusy) {
            announceOutcome()
            return
        }
        getSharedPreferences("runtime", MODE_PRIVATE).edit().putBoolean("resumeAfterBootPending", false).commit()
        val attempt = ++generation
        internalState.update {
            it.copy(isBusy = true, status = UiStatus.LOADING, lastError = "", detail = "")
        }

        val pendingStop = stopJob
        startJob = scope.launch {
            pendingStop?.join()
            if (attempt != generation || !isRequested(this@SessionService)) return@launch
            val settings = settingsStore().settings.first()
            keepAlive.update(settings.protection.wakeDuringSession, active = !isStopping)

            val outcome = sessionLock.withLock {
                if (attempt != generation) return@launch
                runCatching {
                    check(settings.usbTethering == UsbTethering.NEVER || !settings.protection.stabilizeUsb) {
                        "Disable USB stabilization before using USB tethering"
                    }
                    val existing = controller.status()
                    if (org.json.JSONObject(existing).optString("state") == "ACTIVE") existing
                    else controller.start(settings.isLogging, settings.vpnMode, settings.usbTethering)
                }
            }
            if (attempt != generation) return@launch

            outcome.exceptionOrNull()?.let { failure ->
                SessionLog.error(
                    "start failed in the app process: " +
                        "${failure.javaClass.name}: ${failure.message}",
                )
            }

            internalState.update { current -> current.applyOutcome(outcome) }
            if (outcome.isFailure || internalState.value.status == UiStatus.ERROR) {
                setRequested(this@SessionService, false)
                if (isRecovery) HelperRuntime.status.value = "Tethering could not resume. Recovery is paused. Check the session error before starting again."
            }
            publishState()
            announceOutcome()
            followStatus()
        }
    }

    private fun followStatus() = statusPoller.follow(
        isConnected = { internalState.value.status == UiStatus.CONNECTED },
        onStatus = { outcome ->
            internalState.update { current -> current.applyOutcome(outcome) }
            if (internalState.value.status in listOf(UiStatus.READY, UiStatus.ERROR)) {
                // A watchdog failure must not become an automatic restart of an unsafe route.
                setRequested(this, false)
            }
            publishState()
            if (internalState.value.status == UiStatus.READY) finishEndedSession()
        },
    )

    /** The session stopped on its own, as a stop would; release it the same way. */
    private fun finishEndedSession() {
        val ended = generation

        scope.launch {
            sessionLock.withLock { controller.unbindAndStopDaemon() }
            if (ended == generation) { setRequested(this@SessionService, false); remainOrStop() }
        }
    }

    private fun settingsStore(): SettingsStore =
        (application as App).settingsStore

    private fun stopSession() {
        releaseWakeLock()
        val stopped = ++generation

        val abandoned = startJob
        startJob = null

        statusPoller.stop()

        internalState.update {
            it.asStopped()
        }

        publishState()

        val previousStop = stopJob
        stopJob = scope.launch {
            previousStop?.join()

            abandoned?.cancelAndJoin()
            protectionToStop?.join()
            protectionToStop = null

            sessionLock.withLock {
                val stoppedResult = runCatching {
                    val result = controller.stop()
                    val json = org.json.JSONObject(result)
                    check(json.optString("state") != "ERROR") { json.optString("detail", "Android did not confirm cleanup") }
                }
                stoppedResult.exceptionOrNull()?.let { failure ->
                    SessionLog.error("teardown failed: ${failure.message}")
                    internalState.update { it.copy(status = UiStatus.ERROR,
                        lastError = "The hotspot may still be on. Cleanup could not be confirmed: ${failure.message}") }
                    publishState()
                }

                controller.unbindAndStopDaemon()
            }

            announceOutcome()

            if (stopped == generation) {
                isStopping = false
                if (!protectionEnabled) {
                    protectionJob?.cancelAndJoin()
                    restorePhoneSettings(settingsStore().settings.first())
                    withContext(Dispatchers.IO) { runCatching { HelperBridge.service.value?.shutdownHelper() } }
                    stopSelf()
                } else remainOrStop()
            }
        }
    }

    private fun handleSessionLost() {
        statusPoller.stop()
        controller.unbind()
        // Keep the persisted request. The protection worker recovers before resuming.
        internalState.update { it.copy(isBusy = false, status = UiStatus.ERROR,
            interfaceName = "", lastError = "The helper stopped. The hotspot may still be on while recovery waits.") }
        HelperRuntime.status.value = "The helper stopped. Waiting for recovery."
        publishState()
    }

    private suspend fun remainOrStop() {
        val keepIdle = protectionEnabled && settingsStore().settings.first().protection.protectWhileIdle
        if (!keepIdle) stopSelf()
    }

    private fun announceOutcome() {
        val command = reportTo ?: return
        reportTo = null
        AutomationResult.announce(this, command, internalState.value)
    }

    private fun publishState() {

        notificationManager().notify(
            NOTIFICATION_ID,
            notification.build(internalState.value, isStopping),
        )
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        keepAlive.close()
        controller.onSessionLost = null
        controller.unbind()
        scope.cancel()
        liveService = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.shizzi.START_SESSION"
        const val ACTION_PROTECT = "dev.shizzi.PROTECT"
        const val ACTION_STOP_ALL = "dev.shizzi.STOP_ALL"
        fun isRequested(context: Context): Boolean = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .getBoolean("sessionRequested", false)
        fun setRequested(context: Context, wanted: Boolean) {
            check(context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit()
                .putBoolean("sessionRequested", wanted).apply { if (!wanted) putBoolean("resumeAfterBootPending", false) }.commit())
        }
        fun protect(context: Context) {
            // A deliberate Stop all remains in effect until the user explicitly starts protection.
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(ACTION_PROTECT))
        }
        fun enableProtection(context: Context) {
            context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().putBoolean("protectionStopped", false).commit()
            protect(context)
        }
        fun stopAll(context: Context) {
            setRequested(context, false)
            context.startForegroundService(Intent(context, SessionService::class.java).setAction(ACTION_STOP_ALL))
        }
        const val ACTION_STOP = "dev.shizzi.STOP_SESSION"
        const val EXTRA_REPORT_AS = "reportAs"

        private val sessionState = MutableStateFlow(SessionUiState())

        val liveState: StateFlow<SessionUiState> = sessionState.asStateFlow()

        private var liveService: SessionService? = null

        val isRunning: Boolean get() = liveService != null

        val isSessionUp: Boolean get() = liveState.value.status == UiStatus.CONNECTED

        val isSessionBusy: Boolean get() = liveState.value.status == UiStatus.LOADING

        fun start(context: Context, reportAs: AutomationCommand? = null) {
            setRequested(context, true)
            context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().putBoolean("protectionStopped", false).commit()
            context.startForegroundService(
                Intent(context, SessionService::class.java).setAction(ACTION_START).reporting(reportAs),
            )
        }

        fun stop(context: Context, reportAs: AutomationCommand? = null) {
            setRequested(context, false)
            context.startForegroundService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_STOP)
                    .reporting(reportAs),
            )
        }

        private fun Intent.reporting(command: AutomationCommand?): Intent = when (command) {
            null -> this
            else -> putExtra(EXTRA_REPORT_AS, command.name)
        }
    }
}
