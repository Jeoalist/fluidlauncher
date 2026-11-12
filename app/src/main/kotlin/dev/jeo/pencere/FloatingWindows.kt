package dev.jeo.pencere

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager

/**
 * App windows opened while another app covers the launcher, such as one in real full screen.
 * Each floats in its own system window above that app, so the launcher stays where it is.
 */
object FloatingWindows : AppWindow.Host {
    private val windows = ArrayList<AppWindow>()
    private val params = HashMap<AppWindow, WindowManager.LayoutParams>()
    private var windowManager: WindowManager? = null
    private var cascade = 0

    val openApps: List<AppEntry> get() = windows.map { it.app }

    override val areaWidth: Int get() = windowManager?.currentWindowMetrics?.bounds?.width() ?: 0
    override val areaHeight: Int get() = windowManager?.currentWindowMetrics?.bounds?.height() ?: 0

    /** Returns false when windows cannot float here, so the caller can use the launcher instead. */
    fun open(context: Context, app: AppEntry, newWindow: Boolean): Boolean {
        val service = DisplayBridge.service ?: return false
        val appContext = context.applicationContext
        if (!FloatingAppsButton.canShow(appContext)) return false
        val existing = windows.firstOrNull { it.app.key == app.key }
        if (existing != null && !newWindow) return true
        val manager = appContext.getSystemService(WindowManager::class.java).also { windowManager = it }
        val prefs = LauncherPrefs(appContext)
        val placement = AppWindows.Placement.next(appContext, prefs, cascade)
        val window = AppWindow(
            appContext, app, existing != null, service, placement.spec, placement.width, placement.maxContentHeight,
            onFocus = {},
            onClose = ::close,
            onFilledChanged = {},
            onOpenFullscreen = { AppWindows.reopenFullscreen(appContext, it, prefs, ::close) },
            host = this,
        )
        window.label = "ws"
        window.applyTheme(LauncherTheme.load(prefs), isActive = true)
        window.x = placement.x
        window.y = placement.y
        manager.addView(window, params.getValue(window))
        windows += window
        keepInScreen(window, manager)
        watchRotation(appContext)
        cascade = (cascade + 1) % AppWindows.CASCADE_LENGTH
        return true
    }

    override fun place(window: AppWindow, x: Float, y: Float, width: Int, height: Int) {
        val layout = params.getOrPut(window) {
            WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                preferChosenMode(window.context)
            }
        }
        layout.x = x.toInt()
        layout.y = y.toInt()
        layout.width = width
        layout.height = height
        if (window.isAttachedToWindow) windowManager?.updateViewLayout(window, layout)
    }

    fun closeApp(key: String) {
        windows.filter { it.app.key == key }.forEach(::close)
    }

    fun closeAll() {
        windows.toList().forEach(::close)
    }

    private fun close(window: AppWindow) {
        window.close()
        if (window.isAttachedToWindow) windowManager?.removeView(window)
        params -= window
        windows -= window
    }

    private var watching = false

    /** Windows over another app follow the screen as it turns, instead of being left off it. */
    private fun watchRotation(context: Context) {
        if (watching) return
        watching = true
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                val manager = windowManager ?: return
                // Posted: the new screen size is known once the change has gone through.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    windows.toList().forEach { keepInScreen(it, manager) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    /** Keeps a window whole on screen: below the status bar, above the gesture bar, clear of a cutout. */
    private fun keepInScreen(window: AppWindow, manager: WindowManager) {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures(),
        )
        val bounds = metrics.bounds
        window.keepInArea(
            statusBarHeight(manager).toFloat(),
            (bounds.height() - insets.bottom).toFloat(),
            insets.left.toFloat(),
            insets.right.toFloat(),
        )
        // A window from the wider screen shrinks evenly to fit across this one.
        val across = bounds.width() - insets.left - insets.right
        if (window.frameWidth > across && across > 0) {
            window.setFrame(insets.left.toFloat(), window.y, across, window.frameHeight * across / window.frameWidth)
        }
        val maxX = (bounds.width() - insets.right - window.frameWidth).toFloat().coerceAtLeast(insets.left.toFloat())
        window.x = window.x.coerceIn(insets.left.toFloat(), maxX)
    }

    private fun statusBarHeight(manager: WindowManager) =
        manager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
}
