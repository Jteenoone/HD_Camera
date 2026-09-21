package com.example.hd_camera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hd_camera.R
import com.example.hd_camera.edit.MarkupStroke

/** The drawing surface behind the Markup tool of the Edit screen. */
class MarkupOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.dc_accent)
    }

    private val strokes = mutableListOf<MarkupStroke>()
    private var current: MutableList<PointF>? = null

    /** Stroke width as a fraction of the image width, matching MarkupStroke. */
    var strokeFraction: Float = 0.012f

    var strokeColor: Int = ContextCompat.getColor(context, R.color.dc_accent)

    var onStrokesChanged: (() -> Unit)? = null

    val hasStrokes: Boolean get() = strokes.isNotEmpty()

    fun strokes(): List<MarkupStroke> = strokes.toList()

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate()
            onStrokesChanged?.invoke()
        }
    }

    fun clear() {
        if (strokes.isEmpty()) return
        strokes.clear()
        invalidate()
        onStrokesChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        strokes.forEach { stroke ->
            paint.color = stroke.color
            paint.strokeWidth = stroke.width * width
            canvas.drawPath(stroke.scaledPath(width, height), paint)
        }
        current?.let { points ->
            paint.color = strokeColor
            paint.strokeWidth = strokeFraction * width
            canvas.drawPath(
                MarkupStroke(points, strokeColor, strokeFraction).scaledPath(width, height),
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val point = PointF(
            (event.x / width).coerceIn(0f, 1f),
            (event.y / height).coerceIn(0f, 1f)
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                current = mutableListOf(point)
                invalidate()
                performClick()
            }

            MotionEvent.ACTION_MOVE -> {
                current?.add(point)
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { points ->
                    if (points.size > 1) {
                        strokes += MarkupStroke(points.toList(), strokeColor, strokeFraction)
                        onStrokesChanged?.invoke()
                    }
                }
                current = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
