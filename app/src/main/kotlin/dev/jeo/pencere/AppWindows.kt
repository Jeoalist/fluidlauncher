package dev.jeo.pencere

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
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

    /** Called while the desktops move, with 0 at the first desktop and 1 at the last. */
    var onScrolled: (Float) -> Unit = {}

    /** Called when a filled window comes into or leaves the visible desktop. */
    var onFullscreenChanged: (Boolean) -> Unit = {}

    var isFullscreenVisible = false
        private set

    /** How windows are drawn; set by the launcher whenever the theme changes. */
    var theme: LauncherTheme? = null
        set(value) {
            field = value
            restyle()
        }

    /**
     * In the tray layout the first desktop is home, not a place for windows: apps opened there go
     * to the second desktop, while ones opened on another desktop stay where they are opened.
     */
    var firstDesktopIsHome = false

    /** The desktop on screen, starting at 1. */
    private val currentDesktop get() = if (layer.width == 0) 1 else (offset / layer.width).roundToInt() + 1

    /**
     * Tiling lays each desktop's windows out in a grid by themselves; otherwise a moved or resized
     * window pushes the others out of its way. Either way windows never overlap, except one
     * filling the screen.
     */
    var tiling = prefs.tiling
        set(value) {
            field = value
            prefs.tiling = value
            if (value) retileAll() else windows.toList().forEach { if (!it.isFilled) resolve(it) }
        }

    /** Where windows may reach at the bottom, such as above the tray's desktop picker; 0 is the screen's. */
    var bottomLimit = 0f
        set(value) {
            if (field == value) return
            field = value
            if (tiling) retileAll()
        }

    private val gap get() = 6 * layer.resources.displayMetrics.density
    private val areaBottom get() = if (bottomLimit > 0f) bottomLimit else layer.height.toFloat()

    /** The desktop a window sits on, from 0, by its centre. */
    private fun pageOf(window: AppWindow): Int {
        val width = layer.width.coerceAtLeast(1)
        return ((window.x + offset + window.frameWidth / 2f) / width).toInt().coerceIn(0, DESKTOP_COUNT - 1)
    }

    /** Windows that take part in the arranging on [page]: open and not filling the screen. */
    private fun arrangedOn(page: Int) = windows.filter { !it.isFilled && pageOf(it) == page }

    private val arranger = object : AppWindow.Arranger {
        override fun move(window: AppWindow, x: Float, y: Float) {
            window.x = x
            window.y = y
            // While tiling, the window floats freely until dropped onto another one.
            if (!tiling) resolve(window)
        }

        override fun dropped(window: AppWindow) {
            if (!tiling) return
            val page = pageOf(window)
            val centreX = window.x + window.frameWidth / 2f
            val centreY = window.y + window.frameHeight / 2f
            val target = arrangedOn(page).firstOrNull {
                it !== window && centreX in it.x..(it.x + it.frameWidth) && centreY in it.y..(it.y + it.frameHeight)
            }
            if (target != null) {
                val a = windows.indexOf(window)
                val b = windows.indexOf(target)
                windows[a] = target
                windows[b] = window
                restyle()
            }
            retile(page)
        }

        override fun resized(window: AppWindow) {
            if (tiling) retile(pageOf(window)) else resolve(window)
        }

        override val canResize get() = !tiling
    }

    /**
     * Pushes every window that [mover] overlaps out of its way, along the shortest way out that
     * stays on screen, and passes the push on to the windows those run into. A window that cannot
     * move further blocks: then [mover] itself is put back just short of it.
     */
    private fun resolve(mover: AppWindow) {
        val page = pageOf(mover)
        val pageLeft = page * layer.width - offset
        val others = arrangedOn(page).filter { it !== mover }
        val queue = ArrayDeque(listOf(mover))
        val pushed = HashSet<AppWindow>()
        var steps = 0
        while (queue.isNotEmpty() && steps++ < MAX_PUSH_STEPS) {
            val pusher = queue.removeFirst()
            for (other in others) {
                if (other === pusher || other === mover || !overlaps(pusher, other)) continue
                if (pushOut(other, pusher, pageLeft)) {
                    pushed += other
                    queue.addLast(other)
                }
            }
        }
        // Whatever could not make room stops the mover instead.
        for (other in others) {
            if (overlaps(mover, other)) pushOut(mover, other, pageLeft, force = true)
        }
    }

    private fun overlaps(a: AppWindow, b: AppWindow) =
        a.x < b.x + b.frameWidth && b.x < a.x + a.frameWidth && a.y < b.y + b.frameHeight && b.y < a.y + a.frameHeight

    /**
     * Moves [window] clear of [blocker] the shortest way that keeps it on its desktop. Returns
     * false when no way fits; with [force] it then takes the shortest way regardless.
     */
    private fun pushOut(window: AppWindow, blocker: AppWindow, pageLeft: Float, force: Boolean = false): Boolean {
        val minX = pageLeft
        val maxX = pageLeft + layer.width - window.frameWidth
        val minY = topLimit
        val maxY = (areaBottom - window.frameHeight).coerceAtLeast(topLimit)
        // Only away from the other window: a window never jumps over the one pushing it.
        val leftOf = window.x + window.frameWidth / 2f <= blocker.x + blocker.frameWidth / 2f
        val above = window.y + window.frameHeight / 2f <= blocker.y + blocker.frameHeight / 2f
        val moves = listOf(
            (if (leftOf) blocker.x - window.frameWidth - window.x else blocker.x + blocker.frameWidth - window.x) to 0f,
            0f to (if (above) blocker.y - window.frameHeight - window.y else blocker.y + blocker.frameHeight - window.y),
        ).sortedBy { (dx, dy) -> abs(dx) + abs(dy) }
        val fitting = moves.firstOrNull { (dx, dy) ->
            window.x + dx in minX..maxX && window.y + dy in minY..maxY
        } ?: if (force) moves.first() else return false
        window.x += fitting.first
        window.y += fitting.second
        return true
    }

    /** A free spot on [page] for a new window, scanning from the top; null when there is none. */
    private fun freeSpot(window: AppWindow, page: Int): Pair<Float, Float>? {
        val pageLeft = page * layer.width - offset
        val others = arrangedOn(page).filter { it !== window }
        val step = 24 * layer.resources.displayMetrics.density
        var y = topLimit
        while (y + window.frameHeight <= areaBottom) {
            var x = pageLeft
            while (x + window.frameWidth <= pageLeft + layer.width) {
                val free = others.none {
                    x < it.x + it.frameWidth && it.x < x + window.frameWidth && y < it.y + it.frameHeight && it.y < y + window.frameHeight
                }
                if (free) return x to y
                x += step
            }
            y += step
        }
        return null
    }

    /**
     * Lays [page]'s windows out in a grid: on a tall screen one column for up to two windows and
     * two columns beyond that, on a wide screen side by side up to three. A short last row
     * stretches to the full width.
     */
    private fun retile(page: Int) {
        val tiles = arrangedOn(page)
        if (tiles.isEmpty() || layer.width == 0) return
        val count = tiles.size
        val tall = layer.height >= layer.width
        val columns = if (tall) (if (count <= 2) 1 else 2) else minOf(count, 3)
        val rows = (count + columns - 1) / columns
        val left = page * layer.width - offset + gap
        val top = topLimit + gap
        val width = layer.width - gap * 2
        val height = areaBottom - topLimit - gap * 2
        val rowHeight = (height - gap * (rows - 1)) / rows
        tiles.forEachIndexed { i, window ->
            val row = i / columns
            val inRow = if (row == rows - 1) count - row * columns else columns
            val column = i - row * columns
            val tileWidth = (width - gap * (inRow - 1)) / inRow
            window.setFrame(left + column * (tileWidth + gap), top + row * (rowHeight + gap), tileWidth.toInt(), rowHeight.toInt())
        }
    }

    private fun retileAll() = (0 until DESKTOP_COUNT).forEach(::retile)

    /** The window last touched, drawn as active in the tray layout. */
    private var active: AppWindow? = null

    /** Numbers the windows "ws 1", "ws 2"... in the order they were opened and styles them. */
    private fun restyle() {
        val current = theme ?: return
        windows.forEachIndexed { index, window ->
            window.label = "ws ${index + 1}"
            window.applyTheme(current, window === active)
        }
    }

    /**
     * Returns false when the display service is unavailable, so the caller can fall back to system
     * windows. [newWindow] opens another copy even when the app already has a window.
     */
    fun open(app: AppEntry, newWindow: Boolean): Boolean {
        val service = DisplayBridge.service ?: return false
        val existing = windows.firstOrNull { it.app.key == app.key }
        if (existing != null && !newWindow) {
            focus(existing)
            // The window may be on another desktop; go there so it can be seen.
            val desktop = ((existing.x + offset + existing.width / 2f) / layer.width).toInt() + 1
            if (desktop != currentDesktop) goToDesktop(desktop)
            return true
        }
        val target = if (firstDesktopIsHome && currentDesktop == 1) 2 else currentDesktop
        val context = layer.context
        val placement = Placement.next(context, prefs, cascade)
        val window = AppWindow(
            context, app, existing != null, service, placement.spec, placement.width, placement.maxContentHeight,
            ::focus, ::close, ::updateFullscreen, ::openFullscreen,
        )
        window.arranger = arranger
        layer.addView(window)
        active = window
        restyle()
        // Positions are relative to the desktop on screen, so another desktop is a page width away.
        window.x = placement.x + (target - currentDesktop) * layer.width
        window.y = placement.y
        window.keepInArea(topLimit)
        cascade = (cascade + 1) % CASCADE_LENGTH
        windows += window
        restyle()
        // Never on top of another window: tiling makes room, otherwise it takes a free spot,
        // and on a full desktop it pushes the others aside.
        val page = target - 1
        if (tiling) {
            retile(page)
        } else {
            val spot = freeSpot(window, page)
            if (spot != null) {
                window.x = spot.first
                window.y = spot.second
            } else {
                resolve(window)
            }
        }
        if (target != currentDesktop) goToDesktop(target)
        return true
    }

    fun isOnTitleBar(screenX: Float, screenY: Float) = windows.any { it.isOnTitleBar(screenX, screenY) }

    fun isOnContent(screenX: Float, screenY: Float) = windows.any { it.isOnContent(screenX, screenY) }

    fun keepInArea(top: Float) {
        topLimit = top
        windows.forEach { it.keepInArea(top) }
        if (tiling) retileAll()
    }

    fun beginPan() {
        panAnimator?.cancel()
        panStart = offset
    }

    /** Dragging right shows the page to the right; there is nothing left of the first page. */
    fun pan(dragDistance: Float) {
        applyOffset((panStart + dragDistance).coerceIn(0f, lastPageOffset()))
    }

    fun endPan(dragDistance: Float) {
        val pageWidth = layer.width.toFloat()
        val threshold = pageWidth * PAGE_TURN_THRESHOLD
        val turn = when {
            dragDistance > threshold -> 1
            dragDistance < -threshold -> -1
            else -> 0
        }
        val page = ((panStart / pageWidth).roundToInt() + turn).coerceIn(0, DESKTOP_COUNT - 1)
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
        val last = lastPageOffset()
        onScrolled(if (last > 0f) offset / last else 0f)
    }

    private fun lastPageOffset() = layer.width * (DESKTOP_COUNT - 1f)

    init {
        layer.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            val width = right - left
            val height = bottom - top
            val rotated = oldWidth > 0 && oldHeight > 0 && (oldWidth > oldHeight) != (width > height)
            // Posted because resizing children from inside a layout pass is not allowed.
            if (rotated) layer.post { onRotated(width / oldWidth.toFloat(), height / oldHeight.toFloat()) }
        }
    }

    private fun onRotated(scaleX: Float, scaleY: Float) {
        panAnimator?.cancel()
        offset *= scaleX
        val last = lastPageOffset()
        onScrolled(if (last > 0f) offset / last else 0f)
        windows.forEach { it.scaleForRotation(scaleX, scaleY) }
        if (tiling) retileAll() else windows.toList().forEach { if (!it.isFilled) resolve(it) }
    }

    val openApps: List<AppEntry> get() = windows.map { it.app }

    fun closeApp(key: String) {
        windows.filter { it.app.key == key }.forEach(::close)
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

    private fun openFullscreen(window: AppWindow) = reopenFullscreen(layer.context, window, prefs, ::close)

    private fun focus(window: AppWindow) {
        window.bringToFront()
        if (active !== window) {
            active = window
            windows.forEach { it.setActive(it === window) }
        }
    }

    /** Slides to a desktop, as picked in the tray. [number] starts at 1. */
    fun goToDesktop(number: Int) {
        val pageWidth = layer.width.toFloat()
        val page = (number - 1).coerceIn(0, DESKTOP_COUNT - 1)
        panAnimator?.cancel()
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

    private fun close(window: AppWindow) {
        val page = pageOf(window)
        window.close()
        layer.removeView(window)
        windows -= window
        if (active === window) active = windows.lastOrNull()
        restyle()
        if (tiling) retile(page)
        updateFullscreen()
    }

    /** Where a new window goes and how big its app's display is. */
    class Placement(
        val spec: AppWindow.DisplaySpec,
        val width: Int,
        val maxContentHeight: Int,
        val x: Float,
        val y: Float,
    ) {
        companion object {
            fun next(context: Context, prefs: LauncherPrefs, cascade: Int): Placement {
                val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                val dpi = context.resources.displayMetrics.densityDpi
                // Always portrait, even when the launcher is rotated.
                val phoneWidth = minOf(bounds.width(), bounds.height())
                val phoneHeight = maxOf(bounds.width(), bounds.height())
                // Two thirds of the panel at two thirds of the density: identical layout, less GPU work.
                val spec = AppWindow.DisplaySpec(even(phoneWidth * 2 / 3), even(phoneHeight * 2 / 3), dpi * 2 / 3)
                val step = bounds.width() * CASCADE_STEP
                val top = bounds.height() / 8f + cascade * step
                // Keeps the resize corner on screen, which matters in landscape.
                val maxContentHeight = (bounds.height() - top).toInt() - bounds.height() / 8
                // In landscape the portrait window is shrunk evenly to fit, so the app keeps its shape.
                val width = minOf((phoneWidth * prefs.windowSize.scale).toInt(), maxContentHeight * spec.width / spec.height)
                return Placement(spec, width, maxContentHeight, (bounds.width() - width) / 2f + cascade * step, top)
            }

            private fun even(value: Int) = value and 1.inv()
        }
    }

    companion object {
        const val CASCADE_LENGTH = 5
        const val DESKTOP_COUNT = 5
        private const val CASCADE_STEP = 0.05f
        private const val PAGE_TURN_THRESHOLD = 0.15f
        private const val PAGE_TURN_MS = 220L
        private const val TASK_CLEANUP_MS = 200L
        private const val DISPLAY_REMOVAL_TIMEOUT_MS = 2000L
        private const val MAX_PUSH_STEPS = 40

        /**
         * For apps that refuse to run on a virtual display, such as banking apps. Waits for the
         * display to go away, because launching sooner reuses the task that is being destroyed.
         */
        fun reopenFullscreen(context: Context, window: AppWindow, prefs: LauncherPrefs, close: (AppWindow) -> Unit) {
            val handler = Handler(Looper.getMainLooper())
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
                    if (displayId == closedDisplay) handler.postDelayed(launch, TASK_CLEANUP_MS)
                }
            }
            displayManager.registerDisplayListener(listener, handler)
            close(window)
            handler.postDelayed(launch, DISPLAY_REMOVAL_TIMEOUT_MS)
        }

        /** The live desktop's windows, reachable from the search screen. */
        var current: AppWindows? = null
    }
}
