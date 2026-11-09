package dev.jeo.pencere

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlin.system.exitProcess

/**
 * Entry point of the shell-privileged process, started through Shizuku with app_process.
 * It builds its own system context like scrcpy, because Shizuku's service starter crashes on Xiaomi.
 */
object DisplayMain {
    private const val TAG = "FluidDisplay"
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000
    private const val PRIMARY_USER = 0

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Looper.prepareMainLooper()
            val client = register(args[0], DisplayService(shellContext(systemContext())))
            client.linkToDeath({ exitProcess(0) }, 0)
            Looper.loop()
        } catch (e: Throwable) {
            Log.e(TAG, "display service failed", e)
            exitProcess(1)
        }
    }

    private fun systemContext(): Context {
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        type.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        type.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        return type.getDeclaredMethod("getSystemContext").invoke(thread) as Context
    }

    /** Identifies as the shell without loading its resources, which needs a fully started app. */
    private fun shellContext(system: Context): Context = object : ContextWrapper(system) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    }

    /** Hands the service to the launcher and returns a binder that dies along with the launcher. */
    private fun register(authority: String, service: IDisplayService): IBinder {
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val holder = activityManager.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            IBinder::class.java,
            String::class.java,
        ).invoke(activityManager, authority, PRIMARY_USER, Binder(), SHELL_PACKAGE)!!
        val provider = holder.javaClass.getField("provider").get(holder)!!
        val call = provider.javaClass.getMethod(
            "call",
            AttributionSource::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Bundle::class.java,
        )
        val source = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        val extras = Bundle().apply { putBinder(DisplayServiceProvider.KEY_SERVICE, service.asBinder()) }
        val reply = call.invoke(provider, source, authority, DisplayServiceProvider.METHOD_REGISTER, null, extras) as Bundle
        return reply.getBinder(DisplayServiceProvider.KEY_CLIENT)!!
    }
}
