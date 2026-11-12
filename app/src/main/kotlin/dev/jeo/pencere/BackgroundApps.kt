package dev.jeo.pencere

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.widget.Toast

/** Closes running apps that are neither in a window nor, with [keepForeground], on screen. */
fun closeBackgroundApps(context: Context, keepForeground: Boolean) {
    val app = context.applicationContext
    val service = ShizukuBridge.service
    if (service == null) {
        Toast.makeText(app, R.string.background_needs_shizuku, Toast.LENGTH_SHORT).show()
        return
    }
    val home = app.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
    val windowed = (AppWindows.current?.openApps.orEmpty() + FloatingWindows.openApps).map { it.packageName }.toSet()
    val ours = setOfNotNull(app.packageName, home?.activityInfo?.packageName)
    val launchable = AppCatalog.apps.map { it.packageName }.toSet()
    Thread {
        val closed = try {
            val running = service.openPackages()
            // Recent tasks come newest first, so the first app that isn't ours is the one on screen.
            val foreground = if (keepForeground) running.firstOrNull { it !in ours } else null
            val kept = windowed + ours + listOfNotNull(foreground)
            running.filter { it in launchable && it !in kept }.onEach(service::closePackage).size
        } catch (_: RemoteException) {
            0
        }
        Handler(Looper.getMainLooper()).post {
            val message = if (closed == 0) app.getString(R.string.background_none) else app.getString(R.string.background_closed, closed)
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }.start()
}
