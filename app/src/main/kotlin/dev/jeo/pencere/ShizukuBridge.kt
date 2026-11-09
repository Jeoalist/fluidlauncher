package dev.jeo.pencere

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** Tracks whether Shizuku can run [DisplayMain] and holds the connection to it. */
object ShizukuBridge {
    enum class State { NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, READY }

    @Volatile
    var service: IDisplayService? = null
        private set

    var onStateChanged: () -> Unit = {}

    val state: State
        get() = when {
            !Shizuku.pingBinder() -> State.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NEEDS_PERMISSION
            service == null -> State.CONNECTING
            else -> State.READY
        }

    /** Its death tells the shell process that the launcher is gone. */
    private val clientToken = Binder()
    private var process: IRemoteProcess? = null
    private var classPath = ""
    private var authority = ""
    private var started = false

    /** Safe to call again when the launcher activity is recreated. */
    fun start(context: Context) {
        if (started) return
        started = true
        classPath = context.applicationInfo.sourceDir
        authority = "${context.packageName}.display"
        Shizuku.addBinderReceivedListenerSticky { connect() }
        Shizuku.addBinderDeadListener {
            process = null
            onStateChanged()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> connect() }
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST)

    /** Called from [DisplayServiceProvider] on a binder thread. */
    fun attach(binder: IBinder): IBinder {
        binder.linkToDeath({
            service = null
            process = null
            connect()
        }, 0)
        service = IDisplayService.Stub.asInterface(binder)
        onStateChanged()
        return clientToken
    }

    private fun connect() {
        if (state == State.CONNECTING && process == null) startProcess()
        onStateChanged()
    }

    private fun startProcess() {
        val command = "CLASSPATH='$classPath' exec app_process /system/bin " +
            "--nice-name=fluidlauncher_display ${DisplayMain::class.java.name} '$authority' >/dev/null 2>&1"
        try {
            process = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                .newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Exception) {
            Log.e("FluidLauncher", "could not start display service", e)
        }
    }

    private const val PERMISSION_REQUEST = 1
}
