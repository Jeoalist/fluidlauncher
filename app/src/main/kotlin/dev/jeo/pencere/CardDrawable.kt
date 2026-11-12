package dev.jeo.pencere

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider

/**
 * A rounded card. With [borderColors] it gets an outline that runs from one colour into the
 * next, corner to corner, like the active window in the tray layout.
 */
class CardDrawable(
    var fill: Int,
    var radius: Float,
    var borderWidth: Float = 0f,
    var borderColors: IntArray? = null,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        rect.set(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat())
        fillPaint.color = fill
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        val colors = borderColors ?: return
        if (borderWidth <= 0f) return
        val inset = borderWidth / 2
        rect.inset(inset, inset)
        borderPaint.strokeWidth = borderWidth
        // All the way round, corner to corner, so no part of the outline fades into the dark.
        borderPaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, colors, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, borderPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }
}

/** Clips a view, including a live app picture, to rounded corners. */
fun View.clipToRoundRect(radius: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
    clipToOutline = radius > 0f
    invalidateOutline()
}
