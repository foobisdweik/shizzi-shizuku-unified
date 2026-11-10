package dev.shizzi

import android.content.Context

object HelperCommands {
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** Contains paths only. The helper obtains a fresh challenge through the protected provider. */
    fun launch(context: Context): String {
        val info = context.applicationInfo
        return "${quote(info.nativeLibraryDir + "/libshizzi_starter.so")} " +
            "${quote(info.sourceDir)} ${quote(info.nativeLibraryDir)}"
    }

    fun usb(context: Context): String = "adb shell " + quote(launch(context))
}
