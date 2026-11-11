package dev.jeo.pencere

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect

/** The search popup or drawer opened by the apps button; a second tap on the button closes it. */
object AppsPanel {
    private var shown: Activity? = null

    fun onShown(activity: Activity) {
        shown = activity
    }

    fun onGone(activity: Activity) {
        if (shown === activity) shown = null
    }

    fun toggle(context: Context, bounds: Rect, newTask: Boolean) {
        val current = shown
        if (current != null && !current.isFinishing) {
            current.finish()
            shown = null
            return
        }
        val target = if (LauncherPrefs(context).appsButtonOpensDrawer) DrawerActivity::class.java else SearchActivity::class.java
        context.startActivity(
            Intent(context, target).apply {
                sourceBounds = bounds
                if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
