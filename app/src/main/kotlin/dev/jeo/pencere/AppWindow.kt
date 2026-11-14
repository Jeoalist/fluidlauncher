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
import android.widget.SeekBar
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

        /** Where [window] may reach at the bottom where it is now. */
        fun bottomFor(window: AppWindow): Float
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
        // An app locked to portrait gets a tile no wider than tall, centred in its tile.
        val newWidth = portraitWidth(width.coerceAtLeast(minWidth), height - titleHeight)
        val tileX = newX + (width - newWidth) / 2f
        // The app lays itself out for the tile at its usual size, as when filling the screen,
        // instead of a phone layout blown up to the tile's width.
        val newDisplayWidth = zoomed(newWidth * 2 / 3)
        val maxContent = newWidth * maxDisplayHeight / newDisplayWidth
        val newContent = (height - titleHeight).coerceIn(titleHeight, maxContent.coerceAtLeast(titleHeight))
        if (x != tileX) x = tileX
        if (y != newY) y = newY
        // Unchanged tiles are left alone: every new size lays the launcher out again, and that
        // used to come straight back here, resizing the app's display many times a second.
        if (newWidth == windowWidth && newContent == contentHeight && newDisplayWidth == displayWidth) return
        windowWidth = newWidth
        displayWidth = newDisplayWidth
        contentHeight = newContent
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

    /** The bottom windows stay above, such as the tray's desktop picker; else the screen's. */
    private fun areaBottom() = arranger?.bottomFor(this) ?: if (bottomLimit > 0f) bottomLimit else areaHeight().toFloat()

    /** The usable width, between a camera cutout or side bars on either side. */
    private fun usableWidth() = (areaWidth() - leftInset - rightInset).toInt()

    /**
     * Whether the app only runs in portrait. Android squeezes such an app into a narrow strip
     * with bars beside it on a display wider than tall, so its window never gets wider than tall.
     */
    private val portraitOnly: Boolean = runCatching {
        context.packageManager.getActivityInfo(app.component, 0).screenOrientation in PORTRAIT_ORIENTATIONS
    }.getOrDefault(false)

    /** [width] narrowed, for an app locked to portrait, to no more than [height]. */
    private fun portraitWidth(width: Int, height: Int) =
        if (portraitOnly && height > 0) width.coerceAtMost(height.coerceAtLeast(minWidth)) else width

    /** Size of the virtual display the app believes is its screen. */
    data class DisplaySpec(val width: Int, val height: Int, val dpi: Int)

    /** Size to return to; [x] is relative to the filled window so paging cannot make it stale. */
    private data class Restore(val x: Float, val y: Float, val width: Int, val height: Int, val displayWidth: Int, val zoom: Float)

    private val density = resources.displayMetrics.density
    private val prefs = LauncherPrefs(context)
    private var titleHeight = (TITLE_HEIGHT_DP * density).toInt()
    private lateinit var titleBarView: LinearLayout
    private lateinit var column: LinearLayout
    private lateinit var gripView: TextView
    private lateinit var closeView: TextView
    private lateinit var scaleButton: TextView
    private lateinit var scalePanel: LinearLayout
    private lateinit var scaleBar: SeekBar
    private lateinit var scaleLabel: TextView
    private lateinit var scaleReset: TextView

    /**
     * The app's size on top of what the window would show anyway, chosen with the scale slider
     * and kept per app: 2 draws it twice as big, so it gets a display half as wide.
     */
    private var zoom = prefs.appZoom(app.key)
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
    private var windowWidth = initialWidth.coerceAtLeast(minWidth)
    private var displayWidth = zoomed(spec.width)
    private var restore: Restore? = null
    private var topLimit = 0f
    private var bottomLimit = 0f
    private var leftInset = 0f
    private var rightInset = 0f

    /** How much the last turn to landscape shrank the window, undone when it turns back. */
    private var rotationShrink = 1f
    private var contentHeight = minOf(windowWidth * spec.height / spec.width, maxContentHeight)

    /** Display pixels hidden above the window, for apps that leave a blank strip on virtual displays. */
    private val topCrop = (TOP_CROP_DP[app.packageName] ?: 0) * spec.dpi / DisplayMetrics.DENSITY_DEFAULT

    // The width stays fixed so that width sets the app's scale and height reveals more of it.
    private var displayHeight = computeDisplayHeight()

    init {
        column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(titleBar(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, titleHeight))
        // The scale slider lies over the top of the app, right under the title bar.
        val content = FrameLayout(context)
        content.addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(scalePanel(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
        column.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            setStroke((1 * density).toInt(), STROKE_COLOR)
        }
        setPadding(1, 1, 1, 1)
        applySize()

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                displayHeight = computeDisplayHeight()
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

    /**
     * Coming back into view, as when the launcher returns from behind another app, the picture
     * display can keep drawing at a stale buffer size and show the app unscaled in a corner. The
     * size is set again and the display's surface attached afresh, so the system reads it anew.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) refreshSurface()
    }

    /** See [onWindowVisibilityChanged]; also called when the launcher comes back to the front. */
    fun refreshSurface() {
        if (displayId == NO_DISPLAY) return
        val current = surface ?: return
        texture.surfaceTexture?.setDefaultBufferSize(displayWidth, displayHeight)
        // An older display service without this call just ignores it.
        call { service.refreshSurface(displayId, current) }
    }

    /** Touches here belong to the window's own drag and resize handling. */
    fun isOnTitleBar(screenX: Float, screenY: Float): Boolean {
        if (screenX < x || screenX > x + width) return false
        return if (isFilled) screenY >= y + height - titleHeight && screenY <= y + height else screenY >= y && screenY <= y + titleHeight
    }

    fun isOnContent(screenX: Float, screenY: Float): Boolean {
        if (screenX < x || screenX > x + width) return false
        return if (isFilled) screenY >= y && screenY < y + height - titleHeight else screenY > y + titleHeight && screenY <= y + height
    }

    /** A window filling the screen has its title bar at the bottom, out of the app's way at the top. */
    private fun placeTitleBar() {
        val atBottom = isFilled
        val index = column.indexOfChild(titleBarView)
        if (atBottom == (index == 1)) return
        val params = titleBarView.layoutParams
        column.removeView(titleBarView)
        column.addView(titleBarView, if (atBottom) 1 else 0, params)
        // The slider stays next to the title bar.
        (scalePanel.layoutParams as LayoutParams).gravity = if (atBottom) Gravity.BOTTOM else Gravity.TOP
        scalePanel.requestLayout()
    }

    /** Width of the app's display for a window that would show it [base] pixels wide at usual size. */
    private fun zoomed(base: Int) = ((base / zoom).roundToInt() and 1.inv()).coerceAtLeast(MIN_DISPLAY_WIDTH)

    private fun scalePanel(): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isClickable = true
            setBackgroundColor(TITLE_COLOR)
            val pad = (8 * density).toInt()
            setPadding(pad, pad / 2, pad / 2, pad / 2)
        }
        scalePanel = panel
        scaleLabel = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        scaleBar = SeekBar(context).apply {
            max = ((MAX_ZOOM - MIN_ZOOM) / ZOOM_STEP).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    showZoom(zoomAt(progress))
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                // Every new display size makes the app lay out again, so it waits for the finger.
                override fun onStopTrackingTouch(bar: SeekBar) = setZoom(zoomAt(bar.progress))
            })
        }
        scaleReset = TextView(context).apply {
            text = "↺"
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(Color.WHITE)
            contentDescription = context.getString(R.string.window_scale_reset)
            setOnClickListener {
                setZoom(1f)
                scaleBar.progress = progressOf(1f)
            }
        }
        panel.addView(scaleLabel, LinearLayout.LayoutParams((44 * density).toInt(), LayoutParams.MATCH_PARENT))
        panel.addView(scaleBar, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(scaleReset, LinearLayout.LayoutParams(buttonWidth, (36 * density).toInt()))
        return panel
    }

    private fun zoomAt(progress: Int) = MIN_ZOOM + progress * ZOOM_STEP

    private fun progressOf(value: Float) = ((value - MIN_ZOOM) / ZOOM_STEP).roundToInt()

    private fun showZoom(value: Float) {
        scaleLabel.text = "%${(value * 100).roundToInt()}"
    }

    private fun toggleScalePanel() {
        val show = scalePanel.visibility != View.VISIBLE
        if (show) {
            scaleBar.progress = progressOf(zoom)
            showZoom(zoom)
        }
        scalePanel.visibility = if (show) View.VISIBLE else View.GONE
        theme?.let { scaleButton.setTextColor(if (show) it.accent else it.muted) }
            ?: scaleButton.setTextColor(if (show) SCALE_ON_COLOR else Color.WHITE)
    }

    /** Draws the app [value] times its usual size by giving it a narrower or wider display. */
    private fun setZoom(value: Float) {
        if (value == zoom) return
        displayWidth = ((displayWidth * zoom / value).roundToInt() and 1.inv()).coerceAtLeast(MIN_DISPLAY_WIDTH)
        zoom = value
        prefs.setAppZoom(app.key, value)
        showZoom(value)
        resizeDisplay()
    }

    /**
     * Keeps the title bar below the status bar and reachable after the screen rotates. [bottom]
     * is where windows stop above the desktop picker (0 for the screen's bottom); [left] and
     * [right] keep clear of a camera cutout or side bars.
     */
    fun keepInArea(top: Float, bottom: Float = bottomLimit, left: Float = leftInset, right: Float = rightInset) {
        topLimit = top
        bottomLimit = bottom
        leftInset = left
        rightInset = right
        if (parent == null && host == null) return
        if (restore != null) {
            if (!fillsArea()) post(::fillArea)
            return
        }
        val newY = y.coerceIn(top, maxTop())
        if (newY != y) y = newY
    }

    val isFilled: Boolean get() = restore != null

    /**
     * Rotation keeps the app portrait and only scales the window evenly: turning to landscape
     * shrinks it just enough to fit the shorter screen, turning back undoes that shrink.
     */
    fun scaleForRotation(widthScale: Float, heightScale: Float) {
        x *= widthScale
        // A filled window stays against the top; scaling would push it down past the picker.
        y = if (isFilled) FILLED_TOP else y * heightScale
        val scale = if (heightScale < 1f) {
            val room = areaHeight() - topLimit - titleHeight * 2f
            minOf(1f, room / contentHeight).also { rotationShrink = it }
        } else {
            (1f / rotationShrink).also { rotationShrink = 1f }
        }
        restore = restore?.let {
            Restore(it.x * widthScale, it.y * heightScale, (it.width * scale).roundToInt(), (it.height * scale).roundToInt(), it.displayWidth, it.zoom)
        }
        if (!isFilled) {
            windowWidth = (windowWidth * scale).roundToInt()
            contentHeight = (contentHeight * scale).roundToInt()
            // A window from the wider screen must still fit across this one.
            val across = usableWidth()
            if (windowWidth > across && across > 0) {
                val shrink = across / windowWidth.toFloat()
                windowWidth = across
                contentHeight = (contentHeight * shrink).roundToInt()
            }
            applySize()
            resizeDisplay()
            y = y.coerceAtMost(areaBottom() - contentHeight - titleHeight.toFloat())
        }
        keepInArea(topLimit)
    }

    /**
     * Turns the app's display sideways at the same app scale, so the app lays itself out in the
     * other orientation. The window shrinks evenly if the turned window would not fit.
     */
    private fun toggleLandscape() {
        // An app locked to portrait would only be squeezed into a strip on a turned display.
        if (isFilled || displayId == NO_DISPLAY || portraitOnly) return
        val appScale = windowWidth / displayWidth.toFloat()
        val turnedDisplayWidth = (displayHeight - topCrop) and 1.inv()
        val width = turnedDisplayWidth * appScale
        val height = displayWidth * appScale
        // Grows or shrinks evenly to make good use of the room, so a turned small window does not
        // squeeze the app into a thin strip.
        val fit = minOf(usableWidth() * TURN_WIDTH_SHARE / width, (areaBottom() - topLimit - titleHeight) / height)
        displayWidth = turnedDisplayWidth
        windowWidth = (width * fit).roundToInt().coerceAtLeast(minWidth)
        contentHeight = (height * fit).roundToInt()
        x = x.coerceIn(leftInset, (areaWidth() - rightInset - windowWidth).coerceAtLeast(leftInset))
        y = y.coerceAtMost(areaBottom() - contentHeight - titleHeight.toFloat())
        applySize()
        resizeDisplay()
        keepInArea(topLimit)
        // The turned window is a new shape: the others make room for it, as after a resize.
        // Tiling would only put it back in its tile, so it is left alone there.
        if (arranger?.canResize == true) arranger?.resized(this)
    }

    private fun availableContentHeight() = (areaBottom() - FILLED_TOP).toInt() - titleHeight

    /**
     * A filled window covers exactly the room from the top of the screen, where the status bar
     * hides while it is in view, down to the desktop picker.
     */
    private fun fillsArea() = windowWidth == fillWidth() && contentHeight == availableContentHeight() && y == FILLED_TOP

    /** The whole width, or for an app locked to portrait no wider than tall. */
    private fun fillWidth() = portraitWidth(areaWidth(), availableContentHeight())

    private fun toggleFilled() {
        val saved = restore
        if (saved == null) {
            restore = Restore(x, y, windowWidth, contentHeight, displayWidth, zoom)
            x = 0f
            fillArea()
        } else {
            restore = null
            // Back from the filled window's left edge, which a portrait app's narrower window left
            // in the middle.
            x = x - (areaWidth() - windowWidth) / 2f + saved.x
            y = saved.y
            windowWidth = saved.width
            contentHeight = saved.height
            // The scale may have changed while filled.
            displayWidth = ((saved.displayWidth * saved.zoom / zoom).roundToInt() and 1.inv()).coerceAtLeast(MIN_DISPLAY_WIDTH)
            applySize()
            resizeDisplay()
            // Back among the other windows, which may have moved into its old place meanwhile.
            arranger?.resized(this)
        }
        placeTitleBar()
        onFilledChanged()
    }

    /**
     * Runs the app at the panel's own proportions instead of the scaled phone layout. It covers the
     * whole width: the bars beside it hide while it is in view, as they do at the top.
     */
    private fun fillArea() {
        windowWidth = fillWidth()
        // A narrower window for a portrait app sits in the middle.
        x += (areaWidth() - windowWidth) / 2f - x.mod(areaWidth().toFloat().coerceAtLeast(1f))
        contentHeight = availableContentHeight()
        y = FILLED_TOP
        displayWidth = zoomed(windowWidth * 2 / 3)
        applySize()
        resizeDisplay()
    }

    private fun maxTop() = (areaBottom() - titleHeight * 2f).coerceAtLeast(topLimit)

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
            sentWidth = displayWidth
            sentHeight = displayHeight
            service.launch(displayId, app.component.flattenToString(), separateTask)
        }
    }

    private fun applySize() {
        if (host != null) {
            host.place(this, hostX, hostY, windowWidth, contentHeight + titleHeight)
        } else {
            val current = layoutParams
            if (current == null || current.width != windowWidth || current.height != contentHeight + titleHeight) {
                layoutParams = LayoutParams(windowWidth, contentHeight + titleHeight)
            }
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
        scaleButton = TextView(context).apply {
            text = "Aa"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            contentDescription = context.getString(R.string.window_scale)
            setOnClickListener {
                onFocus(this@AppWindow)
                toggleScalePanel()
            }
        }
        labelView.visibility = View.GONE
        labelView.gravity = Gravity.CENTER_VERTICAL
        bar.addView(gripView, LinearLayout.LayoutParams(buttonWidth, LayoutParams.MATCH_PARENT))
        bar.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        bar.addView(labelView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        bar.addView(scaleButton, LinearLayout.LayoutParams(buttonWidth, LayoutParams.MATCH_PARENT))
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
            labelView.visibility = View.GONE
            labelView.typeface = newTheme.typeface
            labelView.textSize = 14f
            labelView.setTextColor(newTheme.muted)
            closeView.setTextColor(newTheme.muted)
            val panelOpen = scalePanel.visibility == View.VISIBLE
            scaleButton.setTextColor(if (panelOpen) newTheme.accent else newTheme.muted)
            scaleButton.typeface = newTheme.typeface
            scalePanel.setBackgroundColor(newTheme.surface.withAlpha(ACTIVE_ALPHA))
            scaleLabel.setTextColor(newTheme.text)
            scaleLabel.typeface = newTheme.typeface
            scaleReset.setTextColor(newTheme.muted)
            val tint = android.content.res.ColorStateList.valueOf(newTheme.accent)
            scaleBar.progressTintList = tint
            scaleBar.thumbTintList = tint
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
            scaleButton.setTextColor(if (scalePanel.visibility == View.VISIBLE) SCALE_ON_COLOR else Color.WHITE)
            scaleButton.typeface = android.graphics.Typeface.DEFAULT
            scalePanel.setBackgroundColor(TITLE_COLOR)
            scaleLabel.setTextColor(Color.WHITE)
            scaleLabel.typeface = android.graphics.Typeface.DEFAULT
            scaleReset.setTextColor(Color.WHITE)
            scaleBar.progressTintList = null
            scaleBar.thumbTintList = null
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
        val nx = newX.coerceIn(leftInset, (areaWidth() - rightInset - windowWidth).coerceAtLeast(leftInset))
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
        val maxWidth = (startX + startWidth - leftInset).toInt().coerceIn(minWidth, areaWidth())
        windowWidth = (startWidth - (event.rawX - downX).toInt()).coerceIn(minWidth, maxWidth)
        // An app locked to portrait is never made wider than tall: it keeps at least its width in height.
        val minHeight = if (portraitOnly) windowWidth else minOf(windowWidth * MIN_DISPLAY_HEIGHT_RATIO_PERCENT / 100, minWidth)
        val maxHeight = minOf(windowWidth * maxDisplayHeight / displayWidth, (startY + startHeight - topLimit).toInt())
        contentHeight = (startHeight - (event.rawY - downY).toInt()).coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))
        // An app locked to portrait is never made wider than tall.
        windowWidth = portraitWidth(windowWidth, contentHeight)
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
        // Kept up to date before the display exists too: the window is placed and sized before
        // its surface is ready, and the display is then created at this size. Otherwise it would
        // get the height of the window's first size and the app would look stretched sideways.
        displayHeight = computeDisplayHeight()
        if (displayId == NO_DISPLAY) return
        // Each resize makes the app lay itself out again, so only a real change is sent.
        if (displayWidth == sentWidth && displayHeight == sentHeight) return
        sentWidth = displayWidth
        sentHeight = displayHeight
        texture.surfaceTexture?.setDefaultBufferSize(displayWidth, displayHeight)
        call { service.resizeDisplay(displayId, displayWidth, displayHeight, spec.dpi) }
        applyCrop()
    }

    /** The display size the service last got, to skip repeats. */
    private var sentWidth = -1
    private var sentHeight = -1

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
        val PORTRAIT_ORIENTATIONS = setOf(
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        )
        const val FULLSCREEN_HOLD_MS = 1500L
        val TOP_CROP_DP = mapOf("com.instagram.android" to 56, "com.zhiliaoapp.musically" to 56)
        const val TITLE_HEIGHT_DP = 24
        const val TRAY_TITLE_HEIGHT_DP = 28
        const val TRAY_RADIUS_DP = 20
        const val TRAY_BORDER_DP = 2
        const val ACTIVE_ALPHA = 0.94f
        const val INACTIVE_ALPHA = 0.6f
        const val TURN_WIDTH_SHARE = 0.9f
        const val BUTTON_WIDTH_DP = 44
        const val MIN_WIDTH_DP = 140
        const val MIN_DISPLAY_HEIGHT_RATIO_PERCENT = 60
        const val TITLE_COLOR = 0xEE1B1B20.toInt()
        const val STROKE_COLOR = 0x55FFFFFF
        /** Filled windows reach the top of the screen; the status bar hides over them. */
        const val FILLED_TOP = 0f
        const val SCALE_ON_COLOR = 0xFF7DCFFF.toInt()
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 2f
        const val ZOOM_STEP = 0.05f
        /** The narrowest display an app gets, however big it is drawn. */
        const val MIN_DISPLAY_WIDTH = 240
    }
}
