package dev.jeo.pencere

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
    private var shrink: Runnable? = null

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
        val button = object : ImageView(app) {
            /** Keeps the saved spot, as a share of the screen, when the screen rotates. */
            override fun onConfigurationChanged(newConfig: Configuration) {
                super.onConfigurationChanged(newConfig)
                post {
                    if (view !== this) return@post
                    val screen = windowManager.currentWindowMetrics.bounds
                    val shift = (size - params.width) / 2
                    params.x = ((screen.width() - size) * prefs.floatingX).toInt() + shift
                    params.y = ((screen.height() - size) * prefs.floatingY).toInt() + shift
                    windowManager.updateViewLayout(this, params)
                }
            }
        }.apply {
            setBackgroundResource(R.drawable.fab)
            setImageResource(R.drawable.ic_grid)
            setPadding(padding, padding, padding, padding)
            contentDescription = app.getString(R.string.all_apps)
        }
        val small = (SMALL_SIZE_DP * density).toInt()
        val smallPadding = padding * small / size
        val shrinkNow = Runnable { resize(button, params, windowManager, small, smallPadding, IDLE_ALPHA) }
        attachTouch(button, params, windowManager, prefs) {
            resize(button, params, windowManager, size, padding, 1f)
            button.removeCallbacks(shrinkNow)
            button.postDelayed(shrinkNow, IDLE_SHRINK_MS)
        }
        windowManager.addView(button, params)
        view = button
        shrink = shrinkNow
        button.postDelayed(shrinkNow, IDLE_SHRINK_MS)
    }

    /** Changes the button's size around its center, so it stays where it was. */
    private fun resize(
        button: View,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager,
        size: Int,
        padding: Int,
        alpha: Float,
    ) {
        if (params.width == size) return
        val shift = (params.width - size) / 2
        params.x += shift
        params.y += shift
        params.width = size
        params.height = size
        button.setPadding(padding, padding, padding, padding)
        button.alpha = alpha
        windowManager.updateViewLayout(button, params)
    }

    fun hide(context: Context) {
        val current = view ?: return
        shrink?.let(current::removeCallbacks)
        shrink = null
        context.applicationContext.getSystemService(WindowManager::class.java).removeView(current)
        view = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(
        button: View,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager,
        prefs: LauncherPrefs,
        onTouched: () -> Unit,
    ) {
        val touchSlop = ViewConfiguration.get(button.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var heldToClose = false
        val closeOnHold = Runnable {
            heldToClose = true
            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            closeBackgroundApps(button.context, keepForeground = true)
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onTouched()
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    heldToClose = false
                    if (prefs.closeBackgroundOnHold) button.postDelayed(closeOnHold, MainActivity.CLOSE_BACKGROUND_HOLD_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!heldToClose && hypot(event.rawX - downX, event.rawY - downY) > touchSlop) {
                        dragging = true
                        button.removeCallbacks(closeOnHold)
                    }
                    if (dragging) {
                        val bounds = windowManager.currentWindowMetrics.bounds
                        params.x = (startX + (event.rawX - downX).toInt()).coerceIn(0, bounds.width() - params.width)
                        params.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, bounds.height() - params.height)
                        windowManager.updateViewLayout(button, params)
                    }
                }
                MotionEvent.ACTION_CANCEL -> button.removeCallbacks(closeOnHold)
                MotionEvent.ACTION_UP -> if (heldToClose) {
                    Unit
                } else if (dragging) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    prefs.floatingX = params.x / (bounds.width() - params.width).toFloat()
                    prefs.floatingY = params.y / (bounds.height() - params.height).toFloat()
                } else {
                    button.removeCallbacks(closeOnHold)
                    openLauncherApps(button)
                }
            }
            true
        }
    }

    /** Opens the search, or the drawer if chosen in the settings, over the full-screen app. */
    private fun openLauncherApps(button: View) {
        val location = IntArray(2).also(button::getLocationOnScreen)
        val bounds = Rect(location[0], location[1], location[0] + button.width, location[1] + button.height)
        AppsPanel.toggle(button.context, bounds, newTask = true)
    }

    private const val SIZE_DP = 56
    private const val SMALL_SIZE_DP = 30
    private const val IDLE_ALPHA = 0.6f
    private const val IDLE_SHRINK_MS = 10_000L
    private const val PADDING_DP = 15
}
