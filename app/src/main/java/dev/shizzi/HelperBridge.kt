package dev.shizzi

import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

/** Holds the connection inside the app. A new app process requires a new publication. */
object HelperBridge {
    private val mutableService = MutableStateFlow<ITetherService?>(null)
    val service: StateFlow<ITetherService?> = mutableService.asStateFlow()
    private var challenge: String? = null

    fun isReady(): Boolean = mutableService.value?.asBinder()?.isBinderAlive == true

    suspend fun awaitService(timeoutMs: Long = 15_000): ITetherService = withTimeout(timeoutMs) {
        service.filterNotNull().first { it.asBinder().isBinderAlive }
    }

    @Synchronized
    internal fun prepare(): Bundle {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        challenge = bytes.joinToString("") { "%02x".format(it) }
        return Bundle().apply {
            putString("challenge", challenge)
            putInt("contract", TetherService.CONTRACT_VERSION)
            putInt("appUid", android.os.Process.myUid())
        }
    }

    @Synchronized
    internal fun publish(extras: Bundle): Bundle {
        check(extras.getString("challenge") == challenge && challenge != null) { "Stale helper publication" }
        check(extras.getInt("contract") == TetherService.CONTRACT_VERSION) { "Incompatible helper" }
        val binder = requireNotNull(extras.getBinder("binder")) { "Missing helper" }
        if (mutableService.value?.asBinder() == binder) {
            challenge = null
            return Bundle().apply { putBoolean("accepted", true) }
        }
        val helper = ITetherService.Stub.asInterface(binder)
        check(helper.contractVersion == TetherService.CONTRACT_VERSION) { "Incompatible helper contract" }
        binder.linkToDeath({
            synchronized(this) {
                if (mutableService.value?.asBinder() === binder) mutableService.value = null
            }
        }, 0)
        mutableService.value = helper
        challenge = null
        return Bundle().apply { putBoolean("accepted", true) }
    }
}

internal object HelperAccess {
    fun isAppCaller(callerUid: Int, appUid: Int): Boolean = appUid >= 10_000 && callerUid == appUid
    fun isShellCaller(callerUid: Int): Boolean = callerUid == 2000
}
