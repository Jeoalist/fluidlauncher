package dev.jeo.pencere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.muntashirakon.adb.android.AdbMdns
import java.util.concurrent.Executors

/**
 * Pairs the launcher with Wireless debugging. The system shows the pairing code in a dialog that
 * closes as soon as another app opens, so the code is typed into this service's notification,
 * which can be answered on top of the system settings.
 */
class PairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var mdns: AdbMdns? = null

    /** The port of the pairing dialog, found on the local network; -1 until it is open. */
    @Volatile
    private var pairingPort = -1
    private val stopLater = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPLY -> onCodeTyped(intent)
            ACTION_CANCEL -> stopSelf()
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        startForeground(NOTIFICATION_ID, waiting(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (mdns == null) {
            mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
                pairingPort = port
                main.post { show(if (port > 0) askForCode(null) else waiting()) }
            }.also { it.start() }
        }
        main.removeCallbacks(stopLater)
        main.postDelayed(stopLater, GIVE_UP_MS)
    }

    private fun onCodeTyped(intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
        val port = pairingPort
        if (code.isEmpty() || port <= 0) {
            show(waiting())
            return
        }
        show(progress(getString(R.string.pairing_in_progress)))
        worker.execute {
            if (!DisplayStarter.pair(this, port, code)) {
                main.post { show(askForCode(getString(R.string.pairing_failed))) }
                return@execute
            }
            main.post { show(progress(getString(R.string.pairing_starting))) }
            DisplayStarter.start(this) { result ->
                main.post {
                    val message = if (result == DisplayStarter.Result.STARTED) R.string.pairing_done else R.string.pairing_start_failed
                    finish(getString(message))
                }
            }
        }
    }

    /** Leaves a last word in a normal notification, since the service's own goes with it. */
    private fun finish(message: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(RESULT_ID, base(message).setAutoCancel(true).build())
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(stopLater)
        mdns?.stop()
        worker.shutdown()
        super.onDestroy()
    }

    private fun show(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

    private fun base(text: String) = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_grid)
        .setContentTitle(getString(R.string.pairing_title))
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))

    private fun waiting() = base(getString(R.string.pairing_waiting))
        .setOngoing(true)
        .addAction(cancelAction())
        .build()

    private fun askForCode(error: String?): Notification {
        val reply = PendingIntent.getForegroundService(
            this, 1, Intent(this, PairingService::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.pairing_code_label)).build()
        val action = Notification.Action.Builder(null, getString(R.string.pairing_enter_code), reply)
            .addRemoteInput(input)
            .build()
        val text = listOfNotNull(error, getString(R.string.pairing_found)).joinToString("\n")
        return base(text).setOngoing(true).addAction(action).addAction(cancelAction()).build()
    }

    private fun progress(text: String) = base(text).setOngoing(true).setProgress(0, 0, true).build()

    private fun cancelAction(): Notification.Action {
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, PairingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, getString(R.string.cancel), cancel).build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.pairing_channel), NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "pairing"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_ID = 2
        private const val KEY_CODE = "code"
        private const val ACTION_REPLY = "dev.jeo.pencere.PAIRING_REPLY"
        private const val ACTION_CANCEL = "dev.jeo.pencere.PAIRING_CANCEL"
        private const val GIVE_UP_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PairingService::class.java))
        }
    }
}
