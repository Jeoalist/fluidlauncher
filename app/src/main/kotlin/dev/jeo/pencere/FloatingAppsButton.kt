package dev.jeo.pencere

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.hypot

/**
 * A copy of the apps button floating over other apps while the launcher is behind them, so the
 * launcher stays reachable from apps opened in real full screen. Needs the overlay permission.
 */
object FloatingAppsButton {
    private var view: View? = null

    /** True while the launcher is covered by another app. */
    val isShown get() = view != null

    fun canShow(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        val app = context.applicationContext
        val prefs = LauncherPrefs(app)
        if (view != null || !prefs.floatingButton || !canShow(app)) return
        val windowManager = app.getSystemService(WindowManager::class.java)
        val density = app.resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt()
        val padding = (PADDING_DP * density).toInt()
        val bounds = windowManager.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((bounds.width() - size) * prefs.floatingX).toInt()
            y = ((bounds.height() - size) * prefs.floatingY).toInt()
        }
        val button = ImageView(app).apply {
            setBackgroundResource(R.drawable.fab)
            setImageResource(R.drawable.ic_grid)
            setPadding(padding, padding, padding, padding)
            contentDescription = app.getString(R.string.all_apps)
        }
        attachTouch(button, params, windowManager, prefs)
        windowManager.addView(button, params)
        view = button
    }

    fun hide(context: Context) {
        val current = view ?: return
        context.applicationContext.getSystemService(WindowManager::class.java).removeView(current)
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(
        button: View,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager,
        prefs: LauncherPrefs,
    ) {
        val touchSlop = ViewConfiguration.get(button.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.rawX - downX, event.rawY - downY) > touchSlop) dragging = true
                    if (dragging) {
                        val bounds = windowManager.currentWindowMetrics.bounds
                        params.x = (startX + (event.rawX - downX).toInt()).coerceIn(0, bounds.width() - params.width)
                        params.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, bounds.height() - params.height)
                        windowManager.updateViewLayout(button, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    prefs.floatingX = params.x / (bounds.width() - params.width).toFloat()
                    prefs.floatingY = params.y / (bounds.height() - params.height).toFloat()
                } else {
                    openLauncherApps(button, prefs)
                }
            }
            true
        }
    }

    /** Opens the search, or the drawer if chosen in the settings, over the full-screen app. */
    private fun openLauncherApps(button: View, prefs: LauncherPrefs) {
        val location = IntArray(2).also(button::getLocationOnScreen)
        val bounds = Rect(location[0], location[1], location[0] + button.width, location[1] + button.height)
        val target = if (prefs.appsButtonOpensDrawer) DrawerActivity::class.java else SearchActivity::class.java
        button.context.startActivity(
            Intent(button.context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { sourceBounds = bounds },
        )
    }

    private const val SIZE_DP = 56
    private const val PADDING_DP = 15
}
