package dev.jeo.pencere

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Tracks whether Shizuku can run [DisplayService] and holds the connection to it. */
object ShizukuBridge {
    enum class State { NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, READY }

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

    private lateinit var args: Shizuku.UserServiceArgs

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IDisplayService.Stub.asInterface(binder)
            onStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            onStateChanged()
        }
    }

    /** Safe to call again when the launcher activity is recreated. */
    fun start(context: Context) {
        if (::args.isInitialized) return
        args = Shizuku.UserServiceArgs(ComponentName(context, DisplayService::class.java))
            .daemon(false)
            .processNameSuffix("display")
            .version(SERVICE_VERSION)
        Shizuku.addBinderReceivedListenerSticky { connect() }
        Shizuku.addBinderDeadListener {
            service = null
            onStateChanged()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> connect() }
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_REQUEST)

    private fun connect() {
        if (state == State.CONNECTING) Shizuku.bindUserService(args, connection)
        onStateChanged()
    }

    private const val SERVICE_VERSION = 6
    private const val PERMISSION_REQUEST = 1
}
