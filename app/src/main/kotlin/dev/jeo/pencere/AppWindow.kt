package dev.jeo.pencere

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.RemoteException
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A movable window showing [app] running at phone size on its own virtual display. */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class AppWindow(
    context: Context,
    val app: AppEntry,
    private val separateTask: Boolean,
    private val service: IDisplayService,
    private val spec: DisplaySpec,
    initialWidth: Int,
    maxContentHeight: Int,
    private val onFocus: (AppWindow) -> Unit,
    private val onClose: (AppWindow) -> Unit,
    private val onFilledChanged: () -> Unit,
    private val onOpenFullscreen: (AppWindow) -> Unit,
    /** Set when the window floats in its own system window instead of inside the launcher. */
    private val host: Host? = null,
) : FrameLayout(context) {

    /**
     * Decides where a dragged or resized window may go, given the other windows on its desktop.
     * Without one a window just moves where it is dragged.
     */
    interface Arranger {
        fun move(window: AppWindow, x: Float, y: Float)
        fun dropped(window: AppWindow)
        fun resized(window: AppWindow)
        val canResize: Boolean
    }

    var arranger: Arranger? = null

    /** The window's full size, title bar included, as last set rather than as last laid out. */
    val frameWidth: Int get() = windowWidth
    val frameHeight: Int get() = contentHeight + titleHeight

    /**
     * Gives the window a new place and size, as tiling does; the app lays itself out for it.
     * A filled window keeps filling the screen.
     */
    fun setFrame(newX: Float, newY: Float, width: Int, height: Int) {
        if (isFilled) return
        windowWidth = width.coerceAtLeast(minWidth)
        // The app lays itself out for the tile at its usual size, as when filling the screen,
        // instead of a phone layout blown up to the tile's width.
        displayWidth = (windowWidth * 2 / 3) and 1.inv()
        val maxContent = windowWidth * maxDisplayHeight / displayWidth
        contentHeight = (height - titleHeight).coerceIn(titleHeight, maxContent.coerceAtLeast(titleHeight))
        x = newX
        y = newY
        applySize()
        resizeDisplay()
    }

    /** Places a window that is not inside a parent view. */
    interface Host {
        val areaWidth: Int
        val areaHeight: Int
        fun place(window: AppWindow, x: Float, y: Float, width: Int, height: Int)
    }

    // In a host the position lives here, because there is no parent to translate within.
    private var hostX = 0f
    private var hostY = 0f

    override fun getX(): Float = if (host != null) hostX else super.getX()

    override fun setX(x: Float) {
        if (host == null) return super.setX(x)
        hostX = x
        host.place(this, hostX, hostY, windowWidth, contentHeight + titleHeight)
    }

    override fun getY(): Float = if (host != null) hostY else super.getY()

    override fun setY(y: Float) {
        if (host == null) return super.setY(y)
        hostY = y
        host.place(this, hostX, hostY, windowWidth, contentHeight + titleHeight)
    }

    private fun areaWidth() = host?.areaWidth ?: (parent as View).width

    private fun areaHeight() = host?.areaHeight ?: (parent as View).height

    /** Size of the virtual display the app believes is its screen. */
    data class DisplaySpec(val width: Int, val height: Int, val dpi: Int)

    /** Size to return to; [x] is relative to the filled window so paging cannot make it stale. */
    private data class Restore(val x: Float, val y: Float, val width: Int, val height: Int, val displayWidth: Int)

    private val density = resources.displayMetrics.density
    private val prefs = LauncherPrefs(context)
    private var titleHeight = (TITLE_HEIGHT_DP * density).toInt()
    private lateinit var titleBarView: LinearLayout
    private lateinit var gripView: TextView
    private lateinit var closeView: TextView
    private val labelView = TextView(context)
    private var theme: LauncherTheme? = null
    private var active = true

    /** Shown on the title bar in the tray layout, such as "ws 2". */
    var label: String = ""
        set(value) {
            field = value
            labelView.text = value
        }
    private val buttonWidth = (BUTTON_WIDTH_DP * density).toInt()
    private val minWidth = (MIN_WIDTH_DP * density).toInt()
    private val texture = TextureView(context)
    private val maxDisplayHeight = spec.height * 3 / 2
    private var surface: Surface? = null
    var displayId = NO_DISPLAY
        private set
    private var windowWidth = initialWidth
    private var displayWidth = spec.width
    private var restore: Restore? = null
    private var topLimit = 0f

    /** How much the last turn to landscape shrank the window, undone when it turns back. */
    private var rotationShrink = 1f
    private var contentHeight = minOf(initialWidth * spec.height / spec.width, maxContentHeight)

    /** Display pixels hidden above the window, for apps that leave a blank strip on virtual displays. */
    private val topCrop = (TOP_CROP_DP[app.packageName] ?: 0) * spec.dpi / DisplayMetrics.DENSITY_DEFAULT

    // The width stays fixed so that width sets the app's scale and height reveals more of it.
    private var displayHeight = computeDisplayHeight()

    init {
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(titleBar(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, titleHeight))
        column.addView(texture, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke((1 * density).toInt(), STROKE_COLOR)
        }
        setPadding(1, 1, 1, 1)
        applySize()

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(displayWidth, displayHeight)
                startApp(texture)
                applyCrop()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(displayWidth, displayHeight)
                applyCrop()
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        texture.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onFocus(this)
            forwardTouch(event)
            true
        }
    }

    /** Touches here belong to the window's own drag and resize handling. */
    fun isOnTitleBar(screenX: Float, screenY: Float): Boolean =
        screenX >= x && screenX <= x + width && screenY >= y && screenY <= y + titleHeight

    fun isOnContent(screenX: Float, screenY: Float): Boolean =
        screenX >= x && screenX <= x + width && screenY > y + titleHeight && screenY <= y + height

    /** Keeps the title bar below the status bar and reachable after the screen rotates. */
    fun keepInArea(top: Float) {
        topLimit = top
        y = y.coerceIn(top, maxTop())
        if (restore != null && !fillsArea()) post(::fillArea)
    }

    val isFilled: Boolean get() = restore != null

    /**
     * Rotation keeps the app portrait and only scales the window evenly: turning to landscape
     * shrinks it just enough to fit the shorter screen, turning back undoes that shrink.
     */
    fun scaleForRotation(widthScale: Float, heightScale: Float) {
        x *= widthScale
        y *= heightScale
        val scale = if (heightScale < 1f) {
            val room = areaHeight() - topLimit - titleHeight * 2f
            minOf(1f, room / contentHeight).also { rotationShrink = it }
        } else {
            (1f / rotationShrink).also { rotationShrink = 1f }
        }
        restore = restore?.let {
            Restore(it.x * widthScale, it.y * heightScale, (it.width * scale).roundToInt(), (it.height * scale).roundToInt(), it.displayWidth)
        }
        if (!isFilled) {
            windowWidth = (windowWidth * scale).roundToInt()
            contentHeight = (contentHeight * scale).roundToInt()
            applySize()
            resizeDisplay()
            y = y.coerceAtMost(areaHeight() - contentHeight - titleHeight.toFloat())
        }
        keepInArea(topLimit)
    }

    /**
     * Turns the app's display sideways at the same app scale, so the app lays itself out in the
     * other orientation. The window shrinks evenly if the turned window would not fit.
     */
    private fun toggleLandscape() {
        if (isFilled || displayId == NO_DISPLAY) return
        val appScale = windowWidth / displayWidth.toFloat()
        val turnedDisplayWidth = (displayHeight - topCrop) and 1.inv()
        val width = turnedDisplayWidth * appScale
        val height = displayWidth * appScale
        val fit = minOf(1f, areaWidth() / width, (areaHeight() - topLimit - titleHeight) / height)
        displayWidth = turnedDisplayWidth
        windowWidth = (width * fit).roundToInt()
        contentHeight = (height * fit).roundToInt()
        x = x.coerceAtMost(areaWidth() - windowWidth.toFloat())
        y = y.coerceAtMost(areaHeight() - contentHeight - titleHeight.toFloat())
        applySize()
        resizeDisplay()
        keepInArea(topLimit)
    }

    private fun availableContentHeight() = areaHeight() - topLimit.toInt() - titleHeight

    private fun fillsArea() = windowWidth == areaWidth() && contentHeight == availableContentHeight()

    private fun toggleFilled() {
        val saved = restore
        if (saved == null) {
            restore = Restore(x, y, windowWidth, contentHeight, displayWidth)
            x = 0f
            fillArea()
        } else {
            restore = null
            x += saved.x
            y = saved.y
            windowWidth = saved.width
            contentHeight = saved.height
            displayWidth = saved.displayWidth
            applySize()
            resizeDisplay()
            // Back among the other windows, which may have moved into its old place meanwhile.
            arranger?.resized(this)
        }
        onFilledChanged()
    }

    /** Runs the app at the panel's own proportions instead of the scaled phone layout. */
    private fun fillArea() {
        windowWidth = areaWidth()
        contentHeight = availableContentHeight()
        y = topLimit
        displayWidth = (windowWidth * 2 / 3) and 1.inv()
        applySize()
        resizeDisplay()
    }

    private fun maxTop() = (areaHeight() - titleHeight * 2f).coerceAtLeast(topLimit)

    fun close() {
        call { if (displayId != NO_DISPLAY) service.releaseDisplay(displayId) }
        displayId = NO_DISPLAY
        surface?.release()
        surface = null
    }

    private fun startApp(surfaceTexture: SurfaceTexture) {
        val created = Surface(surfaceTexture)
        surface = created
        call {
            val rate = context.chosenRefreshRate().toFloat()
            displayId = service.createDisplay("pencere-${app.packageName}", displayWidth, displayHeight, spec.dpi, created, rate)
            service.launch(displayId, app.component.flattenToString(), separateTask)
        }
    }

    private fun applySize() {
        if (host != null) {
            host.place(this, hostX, hostY, windowWidth, contentHeight + titleHeight)
        } else {
            layoutParams = LayoutParams(windowWidth, contentHeight + titleHeight)
        }
    }

    private fun titleBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(TITLE_COLOR)
        }
        titleBarView = bar
        gripView = resizeHandle()
        closeView = closeButton()
        labelView.visibility = View.GONE
        labelView.gravity = Gravity.CENTER_VERTICAL
        bar.addView(gripView, LinearLayout.LayoutParams(buttonWidth, LayoutParams.MATCH_PARENT))
        bar.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        bar.addView(labelView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        bar.addView(closeView, LinearLayout.LayoutParams(buttonWidth, LayoutParams.MATCH_PARENT))
        attachDrag(bar)
        return bar
    }

    /**
     * Draws the window for the desktop layout: the plain frame of the classic layout, or a rounded
     * card in the tray layout whose outline lights up while it is the active window.
     */
    fun applyTheme(newTheme: LauncherTheme, isActive: Boolean) {
        theme = newTheme
        active = isActive
        val tray = newTheme.isTray
        val newTitleHeight = ((if (tray) TRAY_TITLE_HEIGHT_DP else TITLE_HEIGHT_DP) * density).toInt()
        if (tray) {
            val radius = TRAY_RADIUS_DP * density
            val border = TRAY_BORDER_DP * density
            background = CardDrawable(newTheme.surface.withAlpha(if (isActive) ACTIVE_ALPHA else INACTIVE_ALPHA), radius)
            // Over the content rather than behind it: the app's picture has square corners and
            // would otherwise cover the outline at the bottom corners.
            foreground = if (isActive) {
                CardDrawable(Color.TRANSPARENT, radius, border, intArrayOf(newTheme.accent, newTheme.accent.darker()))
            } else {
                null
            }
            val inset = border.toInt()
            setPadding(inset, inset, inset, inset)
            clipToRoundRect(radius)
            titleBarView.setBackgroundColor(Color.TRANSPARENT)
            gripView.text = "—"
            gripView.textSize = 16f
            gripView.setTextColor(if (isActive) newTheme.accent else newTheme.muted)
            gripView.typeface = android.graphics.Typeface.create(newTheme.typeface, android.graphics.Typeface.BOLD)
            labelView.visibility = View.VISIBLE
            labelView.typeface = newTheme.typeface
            labelView.textSize = 14f
            labelView.setTextColor(newTheme.muted)
            closeView.setTextColor(newTheme.muted)
        } else {
            foreground = null
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke((1 * density).toInt(), STROKE_COLOR)
            }
            setPadding(1, 1, 1, 1)
            clipToRoundRect(0f)
            titleBarView.setBackgroundColor(TITLE_COLOR)
            gripView.text = "◤"
            gripView.textSize = 11f
            gripView.setTextColor(Color.LTGRAY)
            gripView.typeface = android.graphics.Typeface.DEFAULT
            labelView.visibility = View.GONE
            closeView.setTextColor(Color.WHITE)
        }
        if (newTitleHeight != titleHeight) {
            titleHeight = newTitleHeight
            titleBarView.layoutParams = titleBarView.layoutParams.apply { height = titleHeight }
            applySize()
            // Before it is placed the window has no area to stay inside yet.
            if (parent != null || host != null) keepInArea(topLimit)
        }
    }

    fun setActive(isActive: Boolean) {
        val current = theme ?: return
        if (isActive != active) applyTheme(current, isActive)
    }

    private fun closeButton(): TextView = TextView(context).apply {
        text = "✕"
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        setOnClickListener { onClose(this@AppWindow) }
    }

    private fun attachDrag(handle: View) {
        val configuration = ViewConfiguration.get(context)
        val touchSlop = configuration.scaledTouchSlop
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout()
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        var lastTapTime = 0L
        var tapCount = 0
        // Waits out the tap window so that a double tap is not mistaken for the start of a triple.
        val dispatchTaps = Runnable {
            if (tapCount == 2) perform(WindowGesture.TITLE_DOUBLE_TAP)
            tapCount = 0
        }
        val hold = Runnable {
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            perform(WindowGesture.TITLE_HOLD)
        }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onFocus(this)
                    downX = event.rawX
                    downY = event.rawY
                    startX = x
                    startY = y
                    dragging = false
                    if (prefs.windowAction(WindowGesture.TITLE_HOLD) != WindowAction.NONE) {
                        handle.postDelayed(hold, FULLSCREEN_HOLD_MS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (hypot(dx, dy) > touchSlop) {
                        dragging = true
                        handle.removeCallbacks(hold)
                    }
                    if (dragging && !isFilled) moveTo(startX + dx, startY + dy)
                }
                MotionEvent.ACTION_UP -> {
                    handle.removeCallbacks(hold)
                    if (dragging && !isFilled) arranger?.dropped(this)
                    if (!dragging) {
                        handle.removeCallbacks(dispatchTaps)
                        tapCount = if (event.eventTime - lastTapTime <= doubleTapTimeout) tapCount + 1 else 1
                        lastTapTime = event.eventTime
                        val tripleUnused = prefs.windowAction(WindowGesture.TITLE_TRIPLE_TAP) == WindowAction.NONE
                        when {
                            tapCount >= 3 -> {
                                tapCount = 0
                                perform(WindowGesture.TITLE_TRIPLE_TAP)
                            }
                            // Nothing to wait for when triple taps do nothing.
                            tapCount == 2 && tripleUnused -> dispatchTaps.run()
                            else -> handle.postDelayed(dispatchTaps, doubleTapTimeout.toLong())
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> handle.removeCallbacks(hold)
            }
            true
        }
    }

    /** Keeps the title bar reachable so that a window can always be dragged back. */
    /** Stays whole on its own desktop, so a window can never be dragged onto the next one. */
    private fun moveTo(newX: Float, newY: Float) {
        val nx = newX.coerceIn(0f, (areaWidth() - windowWidth).toFloat().coerceAtLeast(0f))
        val ny = newY.coerceIn(topLimit, maxTop())
        val placer = arranger
        if (placer != null) {
            placer.move(this, nx, ny)
        } else {
            x = nx
            y = ny
        }
    }

    /**
     * Top-left grip: a tap sends Back to the app, a drag resizes the window while the
     * opposite corner stays where it is. A filled window only takes the tap.
     */
    private fun resizeHandle(): TextView = TextView(context).apply {
        text = "◤"
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(Color.LTGRAY)
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var startWidth = 0
        var startHeight = 0
        var resizing = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onFocus(this@AppWindow)
                    downX = event.rawX
                    downY = event.rawY
                    startX = this@AppWindow.x
                    startY = this@AppWindow.y
                    startWidth = windowWidth
                    startHeight = contentHeight
                    resizing = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.rawX - downX, event.rawY - downY) > touchSlop) resizing = true
                    if (resizing && !isFilled && arranger?.canResize != false) {
                        resizeFromTopLeft(event, downX, downY, startX, startY, startWidth, startHeight)
                        arranger?.resized(this@AppWindow)
                    }
                }
                MotionEvent.ACTION_UP -> if (!resizing) perform(WindowGesture.GRIP_TAP) else if (!isFilled) resizeDisplay()
                MotionEvent.ACTION_CANCEL -> if (resizing && !isFilled) resizeDisplay()
            }
            true
        }
    }

    private fun resizeFromTopLeft(
        event: MotionEvent,
        downX: Float,
        downY: Float,
        startX: Float,
        startY: Float,
        startWidth: Int,
        startHeight: Int,
    ) {
        val maxWidth = (startX + startWidth).toInt().coerceIn(minWidth, areaWidth())
        windowWidth = (startWidth - (event.rawX - downX).toInt()).coerceIn(minWidth, maxWidth)
        val minHeight = minOf(windowWidth * MIN_DISPLAY_HEIGHT_RATIO_PERCENT / 100, minWidth)
        val maxHeight = minOf(windowWidth * maxDisplayHeight / displayWidth, (startY + startHeight - topLimit).toInt())
        contentHeight = (startHeight - (event.rawY - downY).toInt()).coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))
        x = startX + startWidth - windowWidth
        y = startY + startHeight - contentHeight
        applySize()
    }

    private fun perform(gesture: WindowGesture) {
        when (prefs.windowAction(gesture)) {
            WindowAction.NONE -> Unit
            WindowAction.FILL -> toggleFilled()
            WindowAction.ROTATE -> toggleLandscape()
            WindowAction.REAL_FULLSCREEN -> onOpenFullscreen(this)
            WindowAction.BACK -> sendBack()
            WindowAction.CLOSE -> onClose(this)
        }
    }

    private fun sendBack() {
        if (displayId == NO_DISPLAY) return
        val now = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            val key = KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD)
            call { service.injectKey(displayId, key) }
        }
    }

    /** Waits for the finger to lift, because every display resize makes the app lay out again. */
    private fun resizeDisplay() {
        if (displayId == NO_DISPLAY) return
        displayHeight = computeDisplayHeight()
        texture.surfaceTexture?.setDefaultBufferSize(displayWidth, displayHeight)
        call { service.resizeDisplay(displayId, displayWidth, displayHeight, spec.dpi) }
        applyCrop()
    }

    private fun computeDisplayHeight() = (contentHeight * displayWidth / windowWidth + topCrop) and 1.inv()

    private fun applyCrop() {
        if (topCrop == 0 || texture.height == 0) return
        val visibleHeight = (displayHeight - topCrop).toFloat()
        texture.setTransform(Matrix().apply {
            setScale(1f, displayHeight / visibleHeight)
            postTranslate(0f, -topCrop * texture.height / visibleHeight)
        })
    }

    private fun forwardTouch(event: MotionEvent) {
        if (displayId == NO_DISPLAY) return
        val scaleX = displayWidth / texture.width.toFloat()
        val scaleY = (displayHeight - topCrop) / texture.height.toFloat()
        val count = event.pointerCount
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(i, it) }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().also {
                event.getPointerCoords(i, it)
                it.x *= scaleX
                it.y = it.y * scaleY + topCrop
            }
        }
        val scaled = MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, count, properties, coords,
            event.metaState, event.buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        call { service.injectMotion(displayId, scaled) }
        scaled.recycle()
    }

    /** A dead service closes every window through [DisplayBridge], so failures need no handling. */
    private inline fun call(block: () -> Unit) {
        try {
            block()
        } catch (_: RemoteException) {
        }
    }

    private companion object {
        const val NO_DISPLAY = -1
        const val FULLSCREEN_HOLD_MS = 1500L
        val TOP_CROP_DP = mapOf("com.instagram.android" to 56, "com.zhiliaoapp.musically" to 56)
        const val TITLE_HEIGHT_DP = 24
        const val TRAY_TITLE_HEIGHT_DP = 36
        const val TRAY_RADIUS_DP = 20
        const val TRAY_BORDER_DP = 2
        const val ACTIVE_ALPHA = 0.94f
        const val INACTIVE_ALPHA = 0.6f
        const val BUTTON_WIDTH_DP = 44
        const val MIN_WIDTH_DP = 140
        const val MIN_DISPLAY_HEIGHT_RATIO_PERCENT = 60
        const val TITLE_COLOR = 0xEE1B1B20.toInt()
        const val STROKE_COLOR = 0x55FFFFFF
    }
}
