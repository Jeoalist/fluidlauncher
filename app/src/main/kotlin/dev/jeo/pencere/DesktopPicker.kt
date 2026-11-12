package dev.jeo.pencere

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * The desktop picker along the bottom. A finger can scrub along it: the highlight follows the
 * finger and every new slot switches the desktop right away; on release it slides into its slot.
 * A tap goes straight to a desktop.
 */
@SuppressLint("ViewConstructor")
class DesktopPicker(
    context: Context,
    private val count: Int,
    private val labels: List<String>,
    private val onPicked: (Int) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    var theme: LauncherTheme? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The desktop shown, from 1. */
    private var selected = 1

    /** Centre of the highlight along the picker, in pixels. */
    private var highlightX = Float.NaN
    private var dragging = false
    private var slideAnimator: ValueAnimator? = null

    private val trayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val ease = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private val padding get() = dp(6f)
    private val slotWidth get() = (width - padding * 2) / count
    private fun slotCentre(desktop: Int) = padding + slotWidth * (desktop - 0.5f)
    private fun slotAt(x: Float) = (((x - padding) / slotWidth).toInt() + 1).coerceIn(1, count)

    /** Moves the highlight to [desktop] when it changes from elsewhere, such as a swipe. */
    fun setDesktop(desktop: Int) {
        if (desktop == selected && !highlightX.isNaN()) return
        selected = desktop
        if (dragging) return
        if (highlightX.isNaN() || width == 0) {
            highlightX = Float.NaN
            invalidate()
        } else {
            slideTo(slotCentre(desktop))
        }
    }

    private fun slideTo(target: Float) {
        slideAnimator?.cancel()
        slideAnimator = ValueAnimator.ofFloat(highlightX, target).apply {
            duration = SLIDE_MS
            interpolator = ease
            addUpdateListener {
                highlightX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(padding + slotWidth / 2, width - padding - slotWidth / 2)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                if (highlightX.isNaN()) highlightX = slotCentre(selected)
                // The highlight glides under the finger rather than jumping there.
                slideTo(x)
                pick(slotAt(x))
            }
            MotionEvent.ACTION_MOVE -> {
                slideAnimator?.cancel()
                highlightX = x
                pick(slotAt(x))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                slideTo(slotCentre(selected))
            }
        }
        return true
    }

    private fun pick(desktop: Int) {
        if (desktop == selected) return
        selected = desktop
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onPicked(desktop)
    }

    override fun onDraw(canvas: Canvas) {
        val theme = theme ?: return
        if (width == 0) return
        if (highlightX.isNaN()) highlightX = slotCentre(selected)
        val h = height.toFloat()
        trayPaint.color = theme.surface.withAlpha(TRAY_ALPHA)
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, h / 2, h / 2, trayPaint)

        val highlightW = slotWidth - dp(4f)
        rect.set(highlightX - highlightW / 2, dp(5f), highlightX + highlightW / 2, h - dp(5f))
        highlightPaint.color = Color.WHITE.withAlpha(HIGHLIGHT_ALPHA)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), highlightPaint)

        labelPaint.typeface = Typeface.create(theme.typeface, Typeface.BOLD)
        labelPaint.textSize = dp(18f)
        labels.forEachIndexed { i, label ->
            val centre = slotCentre(i + 1)
            labelPaint.color = if (abs(centre - highlightX) < slotWidth / 2) theme.accent else theme.clock
            val baseline = h / 2 - (labelPaint.descent() + labelPaint.ascent()) / 2
            canvas.drawText(label, centre, baseline, labelPaint)
        }
    }

    private companion object {
        const val TRAY_ALPHA = 0.55f
        const val HIGHLIGHT_ALPHA = 0.14f
        const val SLIDE_MS = 220L
    }
}
