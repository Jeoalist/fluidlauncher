package dev.jeo.pencere

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.hardware.display.DisplayManager
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns the scaled app windows on the desktop; only exists while the launcher does. */
class AppWindows(private val layer: FrameLayout, private val prefs: LauncherPrefs) {
    private val windows = ArrayList<AppWindow>()
    private var cascade = 0
    private var topLimit = 0f

    /** How far right the visible page has moved along the strip of pages. */
    private var offset = 0f
    private var panStart = 0f
    private var panAnimator: ValueAnimator? = null

    /** Called with the 1-based number of the desktop being shown. */
    var onDesktopChanged: (Int) -> Unit = {}

    /** Called when a filled window comes into or leaves the visible desktop. */
    var onFullscreenChanged: (Boolean) -> Unit = {}

    var isFullscreenVisible = false
        private set

    /** Returns false when Shizuku is unavailable, so the caller can fall back to system windows. */
    fun open(app: AppEntry): Boolean {
        val service = ShizukuBridge.service ?: return false
        windows.firstOrNull { it.app.key == app.key }?.let {
            focus(it)
            return true
        }
        val context = layer.context
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val dpi = context.resources.displayMetrics.densityDpi
        // Always portrait, even when the launcher is rotated.
        val phoneWidth = minOf(bounds.width(), bounds.height())
        val phoneHeight = maxOf(bounds.width(), bounds.height())
        // Two thirds of the panel at two thirds of the density: identical layout, less GPU work.
        val spec = AppWindow.DisplaySpec(even(phoneWidth * 2 / 3), even(phoneHeight * 2 / 3), dpi * 2 / 3)
        val width = (phoneWidth * prefs.windowSize.scale).toInt()
        val step = bounds.width() * CASCADE_STEP
        val top = bounds.height() / 8f + cascade * step
        // Keeps the resize corner on screen, which matters in landscape.
        val maxContentHeight = (bounds.height() - top).toInt() - bounds.height() / 8
        val window = AppWindow(context, app, service, spec, width, maxContentHeight, ::focus, ::close, ::updateFullscreen, ::openFullscreen)
        layer.addView(window)
        window.x = (bounds.width() - width) / 2f + cascade * step
        window.y = top
        window.keepInArea(topLimit)
        cascade = (cascade + 1) % CASCADE_LENGTH
        windows += window
        return true
    }

    fun isOnTitleBar(screenX: Float, screenY: Float) = windows.any { it.isOnTitleBar(screenX, screenY) }

    fun isOnContent(screenX: Float, screenY: Float) = windows.any { it.isOnContent(screenX, screenY) }

    fun keepInArea(top: Float) {
        topLimit = top
        windows.forEach { it.keepInArea(top) }
    }

    fun beginPan() {
        panAnimator?.cancel()
        panStart = offset
    }

    /** Dragging right shows the page to the right; there is nothing left of the first page. */
    fun pan(dragDistance: Float) {
        applyOffset((panStart + dragDistance).coerceAtLeast(0f))
    }

    fun endPan(dragDistance: Float) {
        val pageWidth = layer.width.toFloat()
        val threshold = pageWidth * PAGE_TURN_THRESHOLD
        val turn = when {
            dragDistance > threshold -> 1
            dragDistance < -threshold -> -1
            else -> 0
        }
        val page = ((panStart / pageWidth).roundToInt() + turn).coerceAtLeast(0)
        onDesktopChanged(page + 1)
        panAnimator = ValueAnimator.ofFloat(offset, page * pageWidth).apply {
            duration = PAGE_TURN_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = updateFullscreen()
            })
            start()
        }
    }

    private fun applyOffset(value: Float) {
        val delta = value - offset
        offset = value
        windows.forEach { it.x -= delta }
    }

    val openApps: List<AppEntry> get() = windows.map { it.app }

    fun closeApp(key: String) {
        windows.firstOrNull { it.app.key == key }?.let(::close)
    }

    fun closeAll() {
        windows.toList().forEach(::close)
    }

    private fun updateFullscreen() {
        val visible = windows.any { it.isFilled && abs(it.x) < layer.width / 2f }
        if (visible == isFullscreenVisible) return
        isFullscreenVisible = visible
        onFullscreenChanged(visible)
    }

    /**
     * For apps that refuse to run on a virtual display, such as banking apps. Waits for the
     * display to go away, because launching sooner reuses the task that is being destroyed.
     */
    private fun openFullscreen(window: AppWindow) {
        val context = layer.context
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val closedDisplay = window.displayId
        var launched = false
        lateinit var listener: DisplayManager.DisplayListener
        val launch = Runnable {
            if (!launched) {
                launched = true
                displayManager.unregisterDisplayListener(listener)
                WindowOpener(context).open(window.app, false, prefs.windowSize, null)
            }
        }
        listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == closedDisplay) layer.postDelayed(launch, TASK_CLEANUP_MS)
            }
        }
        displayManager.registerDisplayListener(listener, layer.handler)
        close(window)
        layer.postDelayed(launch, DISPLAY_REMOVAL_TIMEOUT_MS)
    }

    private fun focus(window: AppWindow) {
        window.bringToFront()
    }

    private fun close(window: AppWindow) {
        window.close()
        layer.removeView(window)
        windows -= window
        updateFullscreen()
    }

    private fun even(value: Int) = value and 1.inv()

    companion object {
        private const val CASCADE_STEP = 0.05f
        private const val CASCADE_LENGTH = 5
        private const val PAGE_TURN_THRESHOLD = 0.15f
        private const val PAGE_TURN_MS = 220L
        private const val TASK_CLEANUP_MS = 200L
        private const val DISPLAY_REMOVAL_TIMEOUT_MS = 2000L

        /** The live desktop's windows, reachable from the search screen. */
        var current: AppWindows? = null
    }
}
