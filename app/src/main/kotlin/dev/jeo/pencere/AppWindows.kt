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
            // A new title bar height changes every window's size; put them back in order.
            layer.post {
                windows.forEach(::keepOnPage)
                if (tiling) retileAll() else windows.toList().forEach { if (!it.isFilled) resolve(it) }
            }
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
            windows.forEach { it.keepInArea(topLimit, value, leftInset, rightInset) }
            if (tiling) retileAll()
        }

    /** Room kept free on either side for a camera cutout or side bars. */
    private var leftInset = 0f
    private var rightInset = 0f

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
            if (!tiling) resolve(window, dragging = true)
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
            if (tiling) retile(pageOf(window)) else resolve(window, dragging = true)
        }

        override val canResize get() = !tiling
    }

    /**
     * Pushes every window that [mover] overlaps out of its way, along the shortest way out that
     * stays on screen, and passes the push on to the windows those run into. A window that cannot
     * move further blocks: then [mover] itself is put back just short of it.
     */
    private fun resolve(mover: AppWindow, dragging: Boolean = false) {
        val page = pageOf(mover)
        val pageLeft = page * layer.width - offset + leftInset
        val others = arrangedOn(page).filter { it !== mover }
        val queue = ArrayDeque(listOf(mover))
        val pushed = HashSet<AppWindow>()
        var steps = 0
        while (queue.isNotEmpty() && steps++ < MAX_PUSH_STEPS) {
            val pusher = queue.removeFirst()
            for (other in others) {
                if (other === pusher || other === mover || !overlaps(pusher, other)) continue
                if (pushOut(other, pusher, pageLeft, awayOnly = dragging)) {
                    pushed += other
                    queue.addLast(other)
                }
            }
        }
        // Whatever could not make room stops the mover instead.
        for (other in others) {
            if (overlaps(mover, other)) pushOut(mover, other, pageLeft, force = true, awayOnly = dragging)
        }
    }

    private fun overlaps(a: AppWindow, b: AppWindow) =
        a.x < b.x + b.frameWidth && b.x < a.x + a.frameWidth && a.y < b.y + b.frameHeight && b.y < a.y + a.frameHeight

    /**
     * Moves [window] clear of [blocker] the shortest way that keeps it on its desktop. Returns
     * false when no way fits; with [force] it then takes the shortest way regardless.
     */
    private fun pushOut(window: AppWindow, blocker: AppWindow, pageLeft: Float, force: Boolean = false, awayOnly: Boolean = true): Boolean {
        val minX = pageLeft
        val maxX = (pageLeft - leftInset + layer.width - rightInset - window.frameWidth).coerceAtLeast(minX)
        val minY = topLimit
        val maxY = (areaBottom - window.frameHeight).coerceAtLeast(topLimit)
        // While dragging, only away from the other window: a window never jumps over the finger's
        // window. Otherwise, as after a rotation, any side that has room will do, nearest first.
        val leftOf = window.x + window.frameWidth / 2f <= blocker.x + blocker.frameWidth / 2f
        val above = window.y + window.frameHeight / 2f <= blocker.y + blocker.frameHeight / 2f
        val toLeft = blocker.x - window.frameWidth - window.x
        val toRight = blocker.x + blocker.frameWidth - window.x
        val toTop = blocker.y - window.frameHeight - window.y
        val toBottom = blocker.y + blocker.frameHeight - window.y
        val away = listOf((if (leftOf) toLeft else toRight) to 0f, 0f to (if (above) toTop else toBottom))
        val all = listOf(toLeft to 0f, toRight to 0f, 0f to toTop, 0f to toBottom)
        val moves = (if (awayOnly) away else all).sortedBy { (dx, dy) -> abs(dx) + abs(dy) }
        val fitting = moves.firstOrNull { (dx, dy) ->
            window.x + dx in minX..maxX && window.y + dy in minY..maxY
        } ?: if (force) moves.first() else return false
        // Even a forced move never leaves the desktop or slides under the desktop picker.
        window.x = (window.x + fitting.first).coerceIn(minX, maxX)
        window.y = (window.y + fitting.second).coerceIn(minY, maxY)
        return true
    }

    /** A free spot on [page] for a new window, scanning from the top; null when there is none. */
    private fun freeSpot(window: AppWindow, page: Int): Pair<Float, Float>? {
        val pageLeft = page * layer.width - offset + leftInset
        val pageRight = page * layer.width - offset + layer.width - rightInset
        val others = arrangedOn(page).filter { it !== window }
        val step = 24 * layer.resources.displayMetrics.density
        var y = topLimit
        while (y + window.frameHeight <= areaBottom) {
            var x = pageLeft
            while (x + window.frameWidth <= pageRight) {
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
        // A wide screen is short: windows go side by side in one row, a second row only past
        // three, so no tile gets too flat for its app.
        val rows = if (tall) (if (count <= 2) count else (count + 1) / 2) else (if (count <= 3) 1 else 2)
        val columns = (count + rows - 1) / rows
        val left = page * layer.width - offset + leftInset + gap
        val top = topLimit + gap
        val width = layer.width - leftInset - rightInset - gap * 2
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
        val placement = Placement.next(context, prefs, cascade, topLimit, areaBottom, leftInset, rightInset)
        val window = AppWindow(
            context, app, existing != null, service, placement.spec, placement.width, placement.maxContentHeight,
            ::focus, ::close, ::updateFullscreen, ::openFullscreen,
        )
        window.arranger = arranger
        layer.addView(window)
        window.keepInArea(topLimit, bottomLimit, leftInset, rightInset)
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

    /**
     * The room windows have: below [top], and clear of [left] and [right] for a camera cutout
     * or side bars. Called on every layout, so the windows are only rearranged on a change.
     */
    fun keepInArea(top: Float, left: Float = leftInset, right: Float = rightInset) {
        if (top == topLimit && left == leftInset && right == rightInset) return
        topLimit = top
        leftInset = left
        rightInset = right
        windows.forEach {
            it.keepInArea(top, bottomLimit, left, right)
            keepOnPage(it)
        }
        if (tiling) retileAll()
    }

    /** Puts a window back inside its own desktop, between the side insets. */
    private fun keepOnPage(window: AppWindow) {
        if (window.isFilled || layer.width == 0) return
        val page = pageOf(window)
        val pageLeft = page * layer.width - offset + leftInset
        val maxX = (pageLeft - leftInset + layer.width - rightInset - window.frameWidth).coerceAtLeast(pageLeft)
        val newX = window.x.coerceIn(pageLeft, maxX)
        if (newX != window.x) window.x = newX
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
        windows.forEach {
            it.scaleForRotation(scaleX, scaleY)
            keepOnPage(it)
        }
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
            /**
             * [top], [bottom], [left] and [right] bound the free room: the status bar, the desktop
             * picker (0 for the screen's bottom) and any cutout or side bars.
             */
            fun next(
                context: Context,
                prefs: LauncherPrefs,
                cascade: Int,
                top: Float = 0f,
                bottom: Float = 0f,
                left: Float = 0f,
                right: Float = 0f,
            ): Placement {
                val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                val density = context.resources.displayMetrics.density
                val dpi = context.resources.displayMetrics.densityDpi
                // Always portrait, even when the launcher is rotated.
                val phoneWidth = minOf(bounds.width(), bounds.height())
                val phoneHeight = maxOf(bounds.width(), bounds.height())
                // Two thirds of the panel at two thirds of the density: identical layout, less GPU work.
                val spec = AppWindow.DisplaySpec(even(phoneWidth * 2 / 3), even(phoneHeight * 2 / 3), dpi * 2 / 3)
                val areaTop = maxOf(top, bounds.height() / 16f)
                val areaBottom = if (bottom > 0f) bottom else bounds.height().toFloat()
                // Each new window steps a little down and right, in steps from its own axis.
                val stepX = bounds.width() * CASCADE_STEP
                val stepY = (areaBottom - areaTop) * CASCADE_STEP
                val windowTop = areaTop + cascade * stepY
                // Room for the whole window below its top, title bar included.
                val maxContentHeight = (areaBottom - windowTop - TITLE_ROOM_DP * density).toInt()
                // In landscape the portrait window is shrunk evenly to fit, so the app keeps its shape,
                // but never below a width its title bar and buttons fit in.
                val minWidth = (MIN_PLACE_WIDTH_DP * density).toInt()
                val width = minOf((phoneWidth * prefs.windowSize.scale).toInt(), maxContentHeight * spec.width / spec.height)
                    .coerceAtLeast(minWidth)
                val usable = bounds.width() - left - right
                val x = (left + (usable - width) / 2f + cascade * stepX).coerceIn(left, (bounds.width() - right - width).coerceAtLeast(left))
                return Placement(spec, width, maxContentHeight.coerceAtLeast(minWidth / 2), x, windowTop)
            }

            private fun even(value: Int) = value and 1.inv()
        }
    }

    companion object {
        const val CASCADE_LENGTH = 5
        const val DESKTOP_COUNT = 5
        private const val CASCADE_STEP = 0.05f
        private const val TITLE_ROOM_DP = 40
        private const val MIN_PLACE_WIDTH_DP = 160
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
