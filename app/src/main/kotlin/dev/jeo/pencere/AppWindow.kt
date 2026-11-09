package dev.jeo.pencere

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.RemoteException
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot

/** A movable window showing [app] running at phone size on its own virtual display. */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class AppWindow(
    context: Context,
    val app: AppEntry,
    private val service: IDisplayService,
    private val spec: DisplaySpec,
    initialWidth: Int,
    maxContentHeight: Int,
    private val onFocus: (AppWindow) -> Unit,
    private val onClose: (AppWindow) -> Unit,
    private val onFilledChanged: () -> Unit,
    private val onOpenFullscreen: (AppWindow) -> Unit,
) : FrameLayout(context) {

    /** Size of the virtual display the app believes is its screen. */
    data class DisplaySpec(val width: Int, val height: Int, val dpi: Int)

    /** Size to return to; [x] is relative to the filled window so paging cannot make it stale. */
    private data class Restore(val x: Float, val y: Float, val width: Int, val height: Int)

    private val density = resources.displayMetrics.density
    private val titleHeight = (TITLE_HEIGHT_DP * density).toInt()
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
        y = y.coerceIn(top, maxTop(parent as View))
        if (restore != null && !fillsArea()) post(::fillArea)
    }

    val isFilled: Boolean get() = restore != null

    private fun availableContentHeight() = (parent as View).height - topLimit.toInt() - titleHeight

    private fun fillsArea() = windowWidth == (parent as View).width && contentHeight == availableContentHeight()

    private fun toggleFilled() {
        val saved = restore
        if (saved == null) {
            restore = Restore(x, y, windowWidth, contentHeight)
            x = 0f
            fillArea()
        } else {
            restore = null
            x += saved.x
            y = saved.y
            windowWidth = saved.width
            contentHeight = saved.height
            displayWidth = spec.width
            applySize()
            resizeDisplay()
        }
        onFilledChanged()
    }

    /** Runs the app at the panel's own proportions instead of the scaled phone layout. */
    private fun fillArea() {
        windowWidth = (parent as View).width
        contentHeight = availableContentHeight()
        y = topLimit
        displayWidth = (windowWidth * 2 / 3) and 1.inv()
        applySize()
        resizeDisplay()
    }

    private fun maxTop(layer: View) = (layer.height - titleHeight * 2f).coerceAtLeast(topLimit)

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
            displayId = service.createDisplay("pencere-${app.packageName}", displayWidth, displayHeight, spec.dpi, created)
            service.launch(displayId, app.component.flattenToString())
        }
    }

    private fun applySize() {
        layoutParams = LayoutParams(windowWidth, contentHeight + titleHeight)
    }

    private fun titleBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(TITLE_COLOR)
        }
        bar.addView(resizeHandle(), LinearLayout.LayoutParams(buttonWidth, titleHeight))
        bar.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        bar.addView(closeButton(), LinearLayout.LayoutParams(buttonWidth, titleHeight))
        attachDrag(bar)
        return bar
    }

    private fun closeButton() = TextView(context).apply {
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
        val hold = Runnable {
            handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onOpenFullscreen(this)
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
                    handle.postDelayed(hold, FULLSCREEN_HOLD_MS)
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
                    if (!dragging) {
                        if (event.eventTime - lastTapTime <= doubleTapTimeout) {
                            lastTapTime = 0L
                            toggleFilled()
                        } else {
                            lastTapTime = event.eventTime
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> handle.removeCallbacks(hold)
            }
            true
        }
    }

    /** Keeps the title bar reachable so that a window can always be dragged back. */
    private fun moveTo(newX: Float, newY: Float) {
        val parentView = parent as View
        val keep = titleHeight * 2f
        x = newX.coerceIn(keep - width, parentView.width - keep)
        y = newY.coerceIn(topLimit, maxTop(parentView))
    }

    /** Top-left grip: the opposite corner stays where it is while the window is resized. */
    private fun resizeHandle() = TextView(context).apply {
        text = "◤"
        gravity = Gravity.CENTER
        textSize = 11f
        setTextColor(Color.LTGRAY)
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var startWidth = 0
        var startHeight = 0
        setOnTouchListener { _, event ->
            if (isFilled) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onFocus(this@AppWindow)
                    downX = event.rawX
                    downY = event.rawY
                    startX = this@AppWindow.x
                    startY = this@AppWindow.y
                    startWidth = windowWidth
                    startHeight = contentHeight
                }
                MotionEvent.ACTION_MOVE -> {
                    val layer = this@AppWindow.parent as View
                    val maxWidth = (startX + startWidth).toInt().coerceIn(minWidth, layer.width)
                    windowWidth = (startWidth - (event.rawX - downX).toInt()).coerceIn(minWidth, maxWidth)
                    val minHeight = windowWidth * MIN_DISPLAY_HEIGHT_RATIO_PERCENT / 100
                    val maxHeight = minOf(windowWidth * maxDisplayHeight / spec.width, (startY + startHeight - topLimit).toInt())
                    contentHeight = (startHeight - (event.rawY - downY).toInt())
                        .coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))
                    this@AppWindow.x = startX + startWidth - windowWidth
                    this@AppWindow.y = startY + startHeight - contentHeight
                    this@AppWindow.applySize()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resizeDisplay()
            }
            true
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

    /** A dead service closes every window through [ShizukuBridge], so failures need no handling. */
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
        const val BUTTON_WIDTH_DP = 44
        const val MIN_WIDTH_DP = 140
        const val MIN_DISPLAY_HEIGHT_RATIO_PERCENT = 60
        const val TITLE_COLOR = 0xEE1B1B20.toInt()
        const val STROKE_COLOR = 0x55FFFFFF
    }
}
