package dev.jeo.pencere

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.util.concurrent.Executors

/** Starts [DisplayMain] in a shell-privileged process through the phone's own adbd. */
object DisplayStarter {
    enum class Result { STARTED, NEEDS_PAIRING, NO_WIRELESS_DEBUGGING, FAILED }

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    var busy = false
        private set

    /**
     * Connects over Wireless debugging and starts the display service, unless it already runs.
     * The same shell also grants the launcher the right to switch Wireless debugging on by
     * itself, which is what lets it start the service again after a reboot. [onDone] runs on the
     * worker thread.
     */
    fun start(context: Context, onDone: (Result) -> Unit = {}) {
        if (busy) return
        busy = true
        val app = context.applicationContext
        worker.execute {
            val result = try {
                val adb = AdbClient.get(app)
                if (!adb.isConnected && !adb.connectTls(app, CONNECT_TIMEOUT_MS)) {
                    Result.NO_WIRELESS_DEBUGGING
                } else {
                    LauncherPrefs(app).adbPaired = true
                    adb.shell(command(app))
                    adb.disconnect()
                    Result.STARTED
                }
            } catch (_: AdbPairingRequiredException) {
                LauncherPrefs(app).adbPaired = false
                Result.NEEDS_PAIRING
            } catch (e: Exception) {
                Log.w(TAG, "could not start the display service", e)
                Result.FAILED
            }
            busy = false
            onDone(result)
        }
    }

    /** Pairs with the code shown by Wireless debugging; blocks, so call it off the main thread. */
    fun pair(context: Context, port: Int, code: String): Boolean = try {
        AdbClient.get(context).pair(LOCALHOST, port, code).also {
            if (it) LauncherPrefs(context).adbPaired = true
        }
    } catch (e: Exception) {
        Log.w(TAG, "pairing failed", e)
        false
    }

    /** The same as fluidlauncher-start.sh, plus the grant for starting again after a reboot. */
    private fun command(context: Context): String {
        val apk = context.applicationInfo.sourceDir
        val authority = "${context.packageName}.display"
        return "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS; " +
            "pidof fluidlauncher_display >/dev/null || " +
            "CLASSPATH='$apk' setsid nohup app_process /system/bin --nice-name=fluidlauncher_display " +
            "${DisplayMain::class.java.name} '$authority' </dev/null >/dev/null 2>&1 & " +
            // adbd ends the session's processes when the command returns; this gives the service
            // time to leave the session first.
            "sleep 2"
    }

    private const val TAG = "FluidLauncher"
    private const val LOCALHOST = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 10_000L
}
