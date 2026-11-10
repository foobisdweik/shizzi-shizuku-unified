package dev.shizzi.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns Shizzi's independent pairing. It never reads another app's credentials. */
object HelperAdb {
    private val operations = Mutex()
    private const val STORE = "helper_pairing"
    private const val PAIRED = "paired"

    fun isPaired(context: Context): Boolean = preferences(context).getBoolean(PAIRED, false)

    fun hasRealWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                capabilities.transportInfo is WifiInfo
        }
    }

    suspend fun pair(context: Context, port: Int, code: String) = operations.withLock {
        require(port in 1..65535) { "Enter a pairing port between 1 and 65535." }
        require(code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit pairing code." }
        check(hasRealWifi(context)) { "Pairing needs a real Wi-Fi connection. The hotspot alone is insufficient." }
        withTimeout(15_000) {
            withContext(Dispatchers.IO) {
                val client = AdbPairingClient("127.0.0.1", port, code, key(context))
                recoveryIo(client::close) {
                    if (!client.start()) throw AdbInvalidPairingCodeException()
                    check(preferences(context).edit().putBoolean(PAIRED, true).commit()) {
                        "Could not save pairing state. Pair again."
                    }
                }
            }
        }
    }

    suspend fun launch(context: Context, command: String) = operations.withLock {
        if (!isPaired(context)) throw AdbPairingRequiredException(IllegalStateException("Pair Shizzi with wireless debugging first."))
        check(hasRealWifi(context)) { "Recovery needs a real Wi-Fi connection. The hotspot alone is insufficient." }
        withTimeout(15_000) {
            val ports = Channel<Int>(Channel.CONFLATED)
            val discovery = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port in 1..65535) ports.trySend(port)
            }
            try {
                discovery.start()
                val port = ports.receive()
                withContext(Dispatchers.IO) {
                    val client = AdbClient("127.0.0.1", port, key(context))
                    recoveryIo(client::close) {
                        client.connect()
                        client.shellCommand(command, null)
                    }
                }
            } catch (error: AdbPairingRequiredException) {
                preferences(context).edit().putBoolean(PAIRED, false).commit()
                throw error
            } finally {
                discovery.stop()
                ports.close()
            }
        }
    }

    suspend fun setWirelessEnabled(context: Context) = withContext(Dispatchers.IO) {
        check(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            "Allow Shizzi to change secure settings, or enable wireless debugging in Developer options."
        }
        check(Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)) {
            "Android rejected enabling debugging."
        }
        check(Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)) {
            "Android rejected enabling wireless debugging."
        }
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun key(context: Context): AdbKey = try {
        AdbKey(PreferenceAdbKeyStore(preferences(context)), "shizzi")
    } catch (error: Exception) {
        throw AdbKeyException(error)
    }
}
