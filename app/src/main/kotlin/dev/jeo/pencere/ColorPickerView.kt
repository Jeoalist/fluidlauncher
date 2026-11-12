package dev.jeo.pencere

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Picks a colour: a square for saturation (across) and brightness (down) above a bar of hues.
 * [onChanged] runs on every change while the finger moves.
 */
@SuppressLint("ClickableViewAccessibility")
class ColorPickerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val hsv = floatArrayOf(0f, 1f, 1f)
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
        setShadowLayer(4 * density, 0f, 0f, Color.BLACK)
    }
    private val square = RectF()
    private val hueBar = RectF()
    private var shaderHue = -1f

    /** Which part the finger started on, so a drag keeps editing it. */
    private var dragging: Int = NONE

    var onChanged: (Int) -> Unit = {}

    var color: Int
        get() = Color.HSVToColor(hsv)
        set(value) {
            Color.colorToHSV(value, hsv)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 0.75f + 48 * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = 16 * density
        square.set(0f, 0f, w.toFloat(), h - 32 * density - gap)
        hueBar.set(0f, h - 32 * density, w.toFloat(), h.toFloat())
        huePaint.shader = LinearGradient(
            hueBar.left, 0f, hueBar.right, 0f,
            IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP,
        )
        shaderHue = -1f
    }

    override fun onDraw(canvas: Canvas) {
        if (shaderHue != hsv[0]) {
            shaderHue = hsv[0]
            val saturation = LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)), Shader.TileMode.CLAMP)
            val brightness = LinearGradient(0f, square.top, 0f, square.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
            squarePaint.shader = ComposeShader(brightness, saturation, PorterDuff.Mode.MULTIPLY)
        }
        val radius = 12 * density
        canvas.drawRoundRect(square, radius, radius, squarePaint)
        canvas.drawRoundRect(hueBar, hueBar.height() / 2, hueBar.height() / 2, huePaint)
        val sx = square.left + hsv[1] * square.width()
        val sy = square.top + (1 - hsv[2]) * square.height()
        canvas.drawCircle(sx, sy, 10 * density, ringPaint)
        val hx = hueBar.left + hsv[0] / 360f * hueBar.width()
        canvas.drawCircle(hx, hueBar.centerY(), hueBar.height() / 2 - 2 * density, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            dragging = if (event.y >= hueBar.top - 8 * density) HUE else SQUARE
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        when (dragging) {
            HUE -> hsv[0] = ((event.x - hueBar.left) / hueBar.width()).coerceIn(0f, 1f) * 359.9f
            SQUARE -> {
                hsv[1] = ((event.x - square.left) / square.width()).coerceIn(0f, 1f)
                hsv[2] = 1 - ((event.y - square.top) / square.height()).coerceIn(0f, 1f)
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dragging = NONE
        invalidate()
        onChanged(color)
        return true
    }

    private companion object {
        const val NONE = 0
        const val SQUARE = 1
        const val HUE = 2
    }
}
