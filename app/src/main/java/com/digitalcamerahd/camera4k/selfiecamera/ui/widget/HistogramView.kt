package com.digitalcamerahd.camera4k.selfiecamera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.digitalcamerahd.camera4k.selfiecamera.R

/**
 * The Pro screen's luma histogram, fed from the preview stream.
 * Shows the design's shape until the first frame arrives.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dc_accent_fill)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dc_accent)
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * resources.displayMetrics.density
    }
    private val path = Path()

    /** Bin heights in 0..1, left (shadows) to right (highlights). */
    private var bins: FloatArray = PLACEHOLDER

    fun submit(values: FloatArray) {
        if (values.isEmpty()) return
        bins = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        path.reset()
        path.moveTo(0f, height)
        bins.forEachIndexed { index, value ->
            val x = width * index / (bins.size - 1).coerceAtLeast(1)
            val y = height - value.coerceIn(0f, 1f) * height
            path.lineTo(x, y)
        }
        path.lineTo(width, height)
        path.close()

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    private companion object {
        /** Mirrors the curve drawn in the design document. */
        val PLACEHOLDER = floatArrayOf(
            0f, 0.15f, 0.45f, 0.35f, 0.70f, 0.55f, 0.80f, 0.60f,
            0.75f, 0.50f, 0.65f, 0.35f, 0.45f, 0.20f, 0.10f, 0f
        )
    }
}
