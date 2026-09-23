package com.example.hd_camera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hd_camera.R

/**
 * The Home bottom bar's background: a flat bar whose top edge rises into a smooth hump in
 * the middle, where the camera button sits. The flat edge starts `home_nav_hump` below the
 * top of the view, so the hump has room to rise without the layout reserving a gap around it.
 */
class CurvedNavBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val humpHeight = resources.getDimension(R.dimen.home_nav_hump)
    private val humpHalfWidth = HUMP_HALF_WIDTH_DP * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.dc_surface)
    }

    /** A hairline along the top edge, so a white bar still reads on a light page. */
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.dc_border)
    }

    private val outline = Path()
    private val topEdge = Path()

    /**
     * A background has no height of its own. Left to View's default it would claim all the
     * room its wrap_content parent offers on the first pass; the parent's second pass then
     * hands it the bar's real height exactly.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            0
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val left = cx - humpHalfWidth
        val right = cx + humpHalfWidth
        // Two cubics per side keep the shoulders soft, like the design's bar.
        topEdge.reset()
        topEdge.moveTo(0f, humpHeight)
        topEdge.lineTo(left, humpHeight)
        topEdge.cubicTo(
            left + humpHalfWidth * 0.45f, humpHeight,
            cx - humpHalfWidth * 0.45f, 0f,
            cx, 0f
        )
        topEdge.cubicTo(
            cx + humpHalfWidth * 0.45f, 0f,
            right - humpHalfWidth * 0.45f, humpHeight,
            right, humpHeight
        )
        topEdge.lineTo(w.toFloat(), humpHeight)

        outline.reset()
        outline.addPath(topEdge)
        outline.lineTo(w.toFloat(), h.toFloat())
        outline.lineTo(0f, h.toFloat())
        outline.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(outline, fill)
        canvas.drawPath(topEdge, edge)
    }

    private companion object {
        const val HUMP_HALF_WIDTH_DP = 76f
    }
}
