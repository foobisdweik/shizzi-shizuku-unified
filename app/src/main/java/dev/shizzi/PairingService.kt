package dev.shizzi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import dev.shizzi.adb.AdbInvalidPairingCodeException
import dev.shizzi.adb.AdbMdns
import dev.shizzi.adb.HelperAdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pairing assistant: it watches for the wireless debugging pairing dialog and offers
 * a notification reply field for the six-digit code. Android advertises the open
 * dialog's port over mDNS (multicast DNS, local service discovery), so the user never
 * types the port and can stay in Settings while entering the code from the shade.
 */
class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discovery: AdbMdns? = null
    private var port = 0
    private var pairing: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REPLY -> {
                startInForeground(working("Pairing…"))
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                onCode(code.filter(Char::isDigit))
            }
            else -> {
                startInForeground(searching())
                startDiscovery()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        if (discovery != null) return
        // NsdManager reports on a binder thread. Hop to the main thread so `port` has one writer.
        discovery = AdbMdns(this, AdbMdns.TLS_PAIRING) { found ->
            scope.launch { onPort(found) }
        }.also { it.start() }
    }

    private fun onPort(found: Int) {
        if (pairing?.isActive == true) return
        if (found in 1..65535) {
            port = found
            post(codeRequest("Pairing service found", "Enter the six-digit code from the pairing dialog."))
        } else {
            // -1 means the dialog closed, so the old port no longer accepts pairing.
            port = 0
            post(searching())
        }
    }

    private fun onCode(code: String) {
        if (pairing?.isActive == true) return
        if (port == 0) {
            post(searching("The pairing dialog closed. Open Pair device with pairing code again."))
            return
        }
        if (code.length != 6) {
            post(codeRequest("Enter six digits", "The pairing code has six digits."))
            return
        }
        val target = port
        pairing = scope.launch {
            try {
                HelperAdb.pair(this@PairingService, target, code)
                HelperRuntime.status.value = "Pairing succeeded. Starting the helper."
                post(working("Paired. Starting the helper…"))
                SessionService.enableProtection(this@PairingService)
                HelperRuntime.ensureHelper(this@PairingService)
                finish("Shizzi is paired", "The helper is running. You can turn wireless debugging off if you do not need it.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbInvalidPairingCodeException) {
                post(codeRequest("Wrong pairing code", "Check the code in the pairing dialog and try again."))
            } catch (e: Exception) {
                Log.w(TAG, "Pairing failed", e)
                HelperRuntime.status.value = "${e.javaClass.simpleName}: ${e.message}"
                if (HelperAdb.isPaired(this@PairingService)) {
                    finish("Shizzi is paired", "The helper did not start: ${e.message ?: e.javaClass.simpleName}. Open helper setup to retry.")
                } else {
                    post(codeRequest("Pairing failed", e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Leaves a dismissible result behind, because the foreground notification goes away with the service. */
    private fun finish(title: String, text: String) {
        val result = base(CHANNEL_ALERT, title, text).setAutoCancel(true).build()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications().notify(RESULT_ID, result)
        stopSelf()
    }

    override fun onDestroy() {
        discovery?.stop()
        discovery = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        createChannels()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun post(notification: Notification) = notifications().notify(NOTIFICATION_ID, notification)

    private fun searching(text: String = "Open Pair device with pairing code under Wireless debugging."): Notification =
        base(CHANNEL_PROGRESS, "Searching for the pairing dialog", text)
            .setOngoing(true)
            .addAction(developerOptionsAction())
            .addAction(stopAction())
            .build()

    private fun working(title: String): Notification =
        base(CHANNEL_PROGRESS, title, "Keep the pairing dialog open.").setOngoing(true).build()

    private fun codeRequest(title: String, text: String): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
        // A RemoteInput intent must be mutable so Android can attach the typed text.
        // The explicit component keeps other apps from redirecting it.
        val reply = PendingIntent.getService(
            this, REQUEST_REPLY,
            Intent(this, PairingService::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(null, "Enter pairing code", reply)
            .addRemoteInput(input)
            .build()
        return base(CHANNEL_ALERT, title, text)
            .setOngoing(true)
            .addAction(action)
            .addAction(stopAction())
            .build()
    }

    private fun base(channel: String, title: String, text: String): Notification.Builder =
        Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(this, REQUEST_OPEN,
                Intent(this, HelperSetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

    private fun stopAction(): Notification.Action = Notification.Action.Builder(null, "Stop",
        PendingIntent.getService(this, REQUEST_STOP,
            Intent(this, PairingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun developerOptionsAction(): Notification.Action = Notification.Action.Builder(null, "Developer options",
        PendingIntent.getActivity(this, REQUEST_SETTINGS, developerOptionsIntent(), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun createChannels() {
        notifications().createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_PROGRESS, "Wireless pairing progress", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while Shizzi waits for the wireless debugging pairing dialog" },
            // High importance makes the code request appear as a heads-up over Settings.
            NotificationChannel(CHANNEL_ALERT, "Wireless pairing code", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Asks for the wireless debugging pairing code" },
        ))
    }

    private fun notifications(): NotificationManager = getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "PairingService"
        private const val ACTION_STOP = "dev.shizzi.pairing.STOP"
        private const val ACTION_REPLY = "dev.shizzi.pairing.REPLY"
        private const val KEY_CODE = "pairing_code"
        private const val CHANNEL_PROGRESS = "wireless-pairing-progress"
        private const val CHANNEL_ALERT = "wireless-pairing-code"
        private const val NOTIFICATION_ID = 0x5A1
        private const val RESULT_ID = 0x5A2
        private const val REQUEST_REPLY = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_SETTINGS = 3
        private const val REQUEST_OPEN = 4

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PairingService::class.java))
        }

        /** The fragment key asks Settings to scroll to and highlight the Wireless debugging row. */
        fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
