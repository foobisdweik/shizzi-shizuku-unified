package dev.shizzi

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Only Android's shell can publish a helper. The helper separately checks every client call. */
class HelperProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        return when (method) {
            "prepare", "publish" -> {
                if (!HelperAccess.isShellCaller(caller)) throw SecurityException("Only the shell can publish a helper")
                val identity = Binder.clearCallingIdentity()
                try {
                    if (method == "prepare") HelperBridge.prepare().apply {
                        putBoolean("shutdown", requireNotNull(context).getSharedPreferences("runtime", android.content.Context.MODE_PRIVATE)
                            .getBoolean("protectionStopped", false) && !SessionService.isRequested(requireNotNull(context)))
                    }
                    else HelperBridge.publish(requireNotNull(extras))
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
            }
            "status" -> {
                if (!HelperAccess.isAppCaller(caller, android.os.Process.myUid())) {
                    throw SecurityException("Only Shizzi can inspect this connection")
                }
                Bundle().apply { putBoolean("ready", HelperBridge.isReady()) }
            }
            else -> throw IllegalArgumentException("Unknown helper operation")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
