package dev.jeo.pencere

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Holds the connection to [DisplayMain] and starts it when it is not running. Starting goes
 * through Wireless debugging with the launcher's own ADB client; after the first pairing the
 * launcher may switch Wireless debugging on by itself, so it also comes back after a reboot.
 * The PC script fluidlauncher-start.sh still works and attaches the same way.
 */
object DisplayBridge {
    enum class State { NEEDS_PAIRING, NOT_RUNNING, STARTING, READY }

    @Volatile
    var service: IDisplayService? = null
        private set

    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null

    /** Whether the launcher switched Wireless debugging on itself, so it switches it off again. */
    private var wirelessDebuggingTurnedOn = false

    val state: State
        get() {
            val app = context
            return when {
                service != null -> State.READY
                DisplayStarter.busy -> State.STARTING
                app != null && !LauncherPrefs(app).adbPaired -> State.NEEDS_PAIRING
                else -> State.NOT_RUNNING
            }
        }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    /** Its death tells the shell process that the launcher is gone. */
    private val clientToken = Binder()

    /** Safe to call again when the launcher activity is recreated. */
    fun start(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        // Wi-Fi often comes up after the launcher at boot; that is when starting can work.
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(wifi, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.postDelayed({ startIfNeeded() }, WIFI_SETTLE_MS)
            }
        })
        startIfNeeded()
    }

    /** Starts the service in the background when it is down and the launcher was paired. */
    fun startIfNeeded() {
        val app = context ?: return
        if (service != null || DisplayStarter.busy || !LauncherPrefs(app).adbPaired) return
        if (canSwitchWirelessDebugging(app) && isOnWifi(app) && !wirelessDebuggingOn(app)) {
            Settings.Global.putInt(app.contentResolver, ADB_WIFI_ENABLED, 1)
            wirelessDebuggingTurnedOn = true
        }
        startNow()
    }

    /** Starts it right away, for the button in the settings; [onDone] runs on the main thread. */
    fun startNow(onDone: (DisplayStarter.Result) -> Unit = {}) {
        val app = context ?: return
        DisplayStarter.start(app) { result ->
            main.post {
                if (result != DisplayStarter.Result.STARTED) switchWirelessDebuggingBack()
                notifyChanged()
                onDone(result)
            }
        }
        notifyChanged()
    }

    /** Granted by the start command, once the launcher has been paired. */
    fun canSwitchWirelessDebugging(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun wirelessDebuggingOn(context: Context) =
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    private fun isOnWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Leaves Wireless debugging as it found it; the service keeps running without it. */
    private fun switchWirelessDebuggingBack() {
        val app = context ?: return
        if (!wirelessDebuggingTurnedOn) return
        wirelessDebuggingTurnedOn = false
        if (canSwitchWirelessDebugging(app)) Settings.Global.putInt(app.contentResolver, ADB_WIFI_ENABLED, 0)
    }

    /** Called from [DisplayServiceProvider] on a binder thread; null turns away a second service. */
    @Synchronized
    fun attach(binder: IBinder): IBinder? {
        if (service?.asBinder()?.isBinderAlive == true) return null
        binder.linkToDeath({
            service = null
            notifyChanged()
            main.post { startIfNeeded() }
        }, 0)
        service = IDisplayService.Stub.asInterface(binder)
        main.post(::switchWirelessDebuggingBack)
        notifyChanged()
        return clientToken
    }

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WIFI_SETTLE_MS = 3000L
}
