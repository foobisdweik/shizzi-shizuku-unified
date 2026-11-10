package dev.shizzi

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dalvik.system.DexClassLoader
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileOutputStream
import java.nio.channels.FileLock

/** Loads app classes with the installed native directory, so Go can load its JNI library. */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(Os.getuid() == 2000) { "Start the helper through Android debugging" }
        // Detach from the debugging shell. Android can still terminate shell processes on an adbd reset.
        if (System.getenv("SHIZZI_DETACHED") != "1") {
            runCatching { Os.setsid() }.onFailure { Log.w("ShizziHelper", "Could not detach the Unix session", it) }
        }
        require(args.size == 2) { "The installed APK and native directory are required" }
        val libraries = args[1] + ":" + args[0] + "!/lib/arm64-v8a"
        val loader = DexClassLoader(args[0], "/data/local/tmp", libraries, ClassLoader.getSystemClassLoader().parent)
        Thread.currentThread().contextClassLoader = loader
        loader.loadClass("dev.shizzi.HelperProcess").getMethod("run", Array<String>::class.java)
            .invoke(null, args)
    }
}

object HelperProcess {
    private var lockStream: FileOutputStream? = null
    private var processLock: FileLock? = null

    @JvmStatic
    fun run(args: Array<String>) {
        HiddenApiBypass.addHiddenApiExemptions("L")
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        val systemContext = activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
        val shellContext = systemContext.createPackageContext("com.android.shell", 0)
        val appInfo = shellContext.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        require(appInfo.sourceDir == args[0] && appInfo.nativeLibraryDir == args[1]) { "The helper must use the installed APK" }
        val descriptor = Os.open("/data/local/tmp/shizzi-helper-${appInfo.uid}.lock",
            OsConstants.O_CREAT or OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        val stat = Os.fstat(descriptor)
        require(stat.st_uid == 2000 && OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L) { "Unsafe helper lock" }
        Os.fchmod(descriptor, 384)
        lockStream = FileOutputStream(descriptor)
        processLock = lockStream!!.channel.tryLock()
        if (processLock == null) return
        val helper = TetherService(systemContext).apply {
            startupRecoveryProblem = recoverOwnedSession()
        }
        val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.helper")
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val prepared = requireNotNull(HelperPublication.call("prepare"))
                    if (prepared.getBoolean("shutdown")) {
                        helper.stop()
                        kotlin.system.exitProcess(0)
                    }
                    if (prepared.getInt("contract") != TetherService.CONTRACT_VERSION || prepared.getInt("appUid") != appInfo.uid) {
                        helper.stop()
                        kotlin.system.exitProcess(0)
                    }
                    val extra = Bundle().apply {
                        putString("challenge", prepared.getString("challenge"))
                        putInt("contract", TetherService.CONTRACT_VERSION)
                        putBinder("binder", helper)
                    }
                    HelperPublication.call("publish", extra)
                } catch (failure: Exception) {
                    Log.w("ShizziHelper", "Could not publish the helper", failure)
                }
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "helper-publication").start()
        Looper.loop()
    }
}
