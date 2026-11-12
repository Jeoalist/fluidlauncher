package dev.jeo.pencere

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Entry point of the shell-privileged process, started with app_process by the launcher over
 * Wireless debugging or by
 * fluidlauncher-start.sh.
 * It builds its own system context like scrcpy, which also works where other service starters crash, such as on Xiaomi.
 */
object DisplayMain {
    private const val TAG = "FluidDisplay"
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000
    private const val PRIMARY_USER = 0
    private const val MAX_REGISTER_FAILURES = 60
    private const val RETRY_DELAY_MS = 2000L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Looper.prepareMainLooper()
            val service = DisplayService(shellContext(systemContext()))
            thread { keepRegistered(args[0], service) }
            Looper.loop()
        } catch (e: Throwable) {
            Log.e(TAG, "display service failed", e)
            exitProcess(1)
        }
    }

    /** Reconnects when the launcher restarts, so one start per boot is enough. */
    private fun keepRegistered(authority: String, service: DisplayService) {
        var failures = 0
        while (failures < MAX_REGISTER_FAILURES) {
            // An app update replaces the file this process was loaded from; its code is now stale.
            if (!File(System.getenv("CLASSPATH").orEmpty()).exists()) exitProcess(0)
            val reply = try {
                register(authority, service)
            } catch (e: Exception) {
                failures++
                SystemClock.sleep(RETRY_DELAY_MS)
                continue
            }
            // No client binder means another service is already connected to the launcher.
            val client = reply.getBinder(DisplayServiceProvider.KEY_CLIENT) ?: exitProcess(0)
            failures = 0
            awaitDeath(client)
            service.releaseAllDisplays()
        }
        exitProcess(1)
    }

    private fun awaitDeath(binder: IBinder) {
        val gone = CountDownLatch(1)
        try {
            binder.linkToDeath({ gone.countDown() }, 0)
            gone.await()
        } catch (_: RemoteException) {
            // It was already dead.
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

    /** Hands the service to the launcher; the reply holds a binder that dies along with it. */
    private fun register(authority: String, service: IDisplayService): Bundle {
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
        return call.invoke(provider, source, authority, DisplayServiceProvider.METHOD_REGISTER, null, extras) as Bundle
    }
}
