package dev.jeo.pencere

import android.app.Activity
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast

fun Activity.launchApp(app: AppEntry, asWindow: Boolean, source: View) {
    val bounds = Rect().also(source::getGlobalVisibleRect)
    val prefs = LauncherPrefs(this)
    try {
        val scaled = asWindow && app.user == Process.myUserHandle() && AppWindows.current?.open(app) == true
        if (!scaled) WindowOpener(this).open(app, asWindow, prefs.windowSize, bounds)
        prefs.recordOpened(app.key)
    } catch (_: RuntimeException) {
        Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
    }
}

/** Runs [onLaunched] after an item that opens the app, so a search screen can dismiss itself. */
fun Activity.showAppMenu(app: AppEntry, anchor: View, onLaunched: () -> Unit = {}) {
    PopupMenu(this, anchor).apply {
        menu.add(R.string.open_window).setOnMenuItemClickListener {
            launchApp(app, true, anchor)
            onLaunched()
            true
        }
        menu.add(R.string.open_fullscreen).setOnMenuItemClickListener {
            launchApp(app, false, anchor)
            onLaunched()
            true
        }
        menu.add(R.string.app_info).setOnMenuItemClickListener {
            getSystemService(LauncherApps::class.java)
                .startAppDetailsActivity(app.component, app.user, null, null)
            true
        }
        menu.add(R.string.uninstall).setOnMenuItemClickListener {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")))
            true
        }
        show()
    }
}
