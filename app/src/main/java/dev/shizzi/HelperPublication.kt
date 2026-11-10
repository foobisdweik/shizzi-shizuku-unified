package dev.shizzi

import android.content.AttributionSource
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.system.Os

/** Shell processes have no bound Application. Acquire the provider without ActivityThread's package fallback. */
internal object HelperPublication {
    fun call(method: String, extras: Bundle? = null): Bundle? {
        val authority = BuildConfig.APPLICATION_ID + ".helper"
        val activityManager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        val managerApi = Class.forName("android.app.IActivityManager")
        val providerApi = Class.forName("android.content.IContentProvider")
        val holder = managerApi.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
            IBinder::class.java, String::class.java).invoke(activityManager, authority, 0, null, "ShizziHelper")
            ?: error("Android did not provide Shizzi's helper provider")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            return if (Build.VERSION.SDK_INT >= 31) {
                val source = AttributionSource.Builder(Os.getuid()).setPackageName("com.android.shell").build()
                providerApi.getMethod("call", AttributionSource::class.java, String::class.java, String::class.java,
                    String::class.java, Bundle::class.java).invoke(provider, source, authority, method, null, extras) as Bundle?
            } else {
                providerApi.getMethod("call", String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java, Bundle::class.java)
                    .invoke(provider, "com.android.shell", null, authority, method, null, extras) as Bundle?
            }
        } finally {
            managerApi.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                .invoke(activityManager, authority, null)
        }
    }
}
