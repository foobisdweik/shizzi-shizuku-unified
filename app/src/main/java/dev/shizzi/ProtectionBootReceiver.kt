package dev.shizzi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ProtectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = (context.applicationContext as App).settingsStore.settings.first().protection
                if (intent.action == Intent.ACTION_BOOT_COMPLETED && !settings.resumeAfterBoot)
                    SessionService.setRequested(context, false)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED && settings.resumeAfterBoot && SessionService.isRequested(context))
                    context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit().putBoolean("resumeAfterBootPending", true).commit()
                if (settings.startAfterBoot) runCatching { SessionService.protect(context) }
                    .onFailure { HelperRuntime.status.value = "Open Shizzi to start protection: ${it.message}" }
            } finally { result.finish() }
        }
    }
}
