package com.example.hd_camera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hd_camera.R

/**
 * The ISO dial of the Pro screen: a repeating tick track with an accent marker.
 * Ticks repeat every 13dp, matching `repeating-linear-gradient(90deg,#3B3B47 0 1px,transparent 1px 13px)`.
 */
class TickRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val tickSpacing = 13f * density
    private val inset = 3f * density

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dc_border_3)
        strokeWidth = 1f * density
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dc_accent)
    }
    private val markerRect = RectF()

    /** Marker position as a fraction of the track width. */
    var position: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Called while the user drags the dial. */
    var onPositionChanged: ((Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        var x = 0f
        while (x <= width) {
            canvas.drawLine(x, 0f, x, h, tickPaint)
            x += tickSpacing
        }
        val markerWidth = 2f * density
        val cx = width * position
        markerRect.set(cx - markerWidth / 2f, inset, cx + markerWidth / 2f, h - inset)
        canvas.drawRoundRect(markerRect, markerWidth, markerWidth, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                position = if (width == 0) 0.5f else event.x / width
                onPositionChanged?.invoke(position)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
