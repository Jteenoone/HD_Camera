package com.example.hd_camera.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.hd_camera.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The crop frame of the Edit screen: a draggable rectangle with corner handles and
 * rule-of-thirds guides, constrained to the area the photo actually occupies.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class Grab { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val density = resources.displayMetrics.density
    private val handleLength = 22f * density
    private val handleWidth = 3f * density
    private val touchSlop = 28f * density
    private val minimumSize = 56f * density

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dc_accent)
        style = Paint.Style.STROKE
        strokeWidth = handleWidth
        strokeCap = Paint.Cap.ROUND
    }

    /** Where the photo is actually drawn inside this view. */
    private val content = RectF()
    private val crop = RectF()
    private var grab = Grab.NONE
    private var lastX = 0f
    private var lastY = 0f

    /** null means a free crop; otherwise width / height. */
    var aspect: Float? = null
        set(value) {
            field = value
            applyAspect()
            invalidate()
        }

    var onCropChanged: (() -> Unit)? = null

    fun setContentBounds(bounds: RectF) {
        if (content == bounds) return
        content.set(bounds)
        reset()
    }

    fun reset() {
        crop.set(content)
        applyAspect()
        invalidate()
        onCropChanged?.invoke()
    }

    val isCropped: Boolean
        get() = content.width() > 0f && (
            abs(crop.left - content.left) > 1f || abs(crop.top - content.top) > 1f ||
                abs(crop.right - content.right) > 1f || abs(crop.bottom - content.bottom) > 1f
            )

    /** The crop expressed as 0..1 of the photo, ready to apply to the full-size bitmap. */
    fun normalizedCrop(): RectF {
        if (content.width() <= 0f || content.height() <= 0f) return RectF(0f, 0f, 1f, 1f)
        return RectF(
            ((crop.left - content.left) / content.width()).coerceIn(0f, 1f),
            ((crop.top - content.top) / content.height()).coerceIn(0f, 1f),
            ((crop.right - content.left) / content.width()).coerceIn(0f, 1f),
            ((crop.bottom - content.top) / content.height()).coerceIn(0f, 1f)
        )
    }

    private fun applyAspect() {
        val ratio = aspect ?: return
        if (crop.width() <= 0f || crop.height() <= 0f) return

        val centreX = crop.centerX()
        val centreY = crop.centerY()
        var width = crop.width()
        var height = width / ratio
        if (height > content.height()) {
            height = content.height()
            width = height * ratio
        }
        if (width > content.width()) {
            width = content.width()
            height = width / ratio
        }
        crop.set(
            centreX - width / 2f,
            centreY - height / 2f,
            centreX + width / 2f,
            centreY + height / 2f
        )
        clampIntoContent()
    }

    private fun clampIntoContent() {
        if (crop.left < content.left) crop.offset(content.left - crop.left, 0f)
        if (crop.top < content.top) crop.offset(0f, content.top - crop.top)
        if (crop.right > content.right) crop.offset(content.right - crop.right, 0f)
        if (crop.bottom > content.bottom) crop.offset(0f, content.bottom - crop.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (crop.isEmpty) return

        // Dim everything outside the frame.
        canvas.drawRect(content.left, content.top, content.right, crop.top, scrimPaint)
        canvas.drawRect(content.left, crop.bottom, content.right, content.bottom, scrimPaint)
        canvas.drawRect(content.left, crop.top, crop.left, crop.bottom, scrimPaint)
        canvas.drawRect(crop.right, crop.top, content.right, crop.bottom, scrimPaint)

        for (index in 1..2) {
            val x = crop.left + crop.width() * index / 3f
            val y = crop.top + crop.height() * index / 3f
            canvas.drawLine(x, crop.top, x, crop.bottom, gridPaint)
            canvas.drawLine(crop.left, y, crop.right, y, gridPaint)
        }
        canvas.drawRect(crop, borderPaint)

        drawCorner(canvas, crop.left, crop.top, 1f, 1f)
        drawCorner(canvas, crop.right, crop.top, -1f, 1f)
        drawCorner(canvas, crop.left, crop.bottom, 1f, -1f)
        drawCorner(canvas, crop.right, crop.bottom, -1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val inset = handleWidth / 2f
        canvas.drawLine(x + dx * inset, y + dy * inset, x + dx * handleLength, y + dy * inset, handlePaint)
        canvas.drawLine(x + dx * inset, y + dy * inset, x + dx * inset, y + dy * handleLength, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || content.isEmpty) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grab = grabAt(event.x, event.y)
                if (grab == Grab.NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                performClick()
            }

            MotionEvent.ACTION_MOVE -> {
                if (grab == Grab.NONE) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                applyDrag(dx, dy)
                invalidate()
                onCropChanged?.invoke()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grab = Grab.NONE
        }
        return true
    }

    private fun grabAt(x: Float, y: Float): Grab = when {
        near(x, y, crop.left, crop.top) -> Grab.TOP_LEFT
        near(x, y, crop.right, crop.top) -> Grab.TOP_RIGHT
        near(x, y, crop.left, crop.bottom) -> Grab.BOTTOM_LEFT
        near(x, y, crop.right, crop.bottom) -> Grab.BOTTOM_RIGHT
        crop.contains(x, y) -> Grab.MOVE
        else -> Grab.NONE
    }

    private fun near(x: Float, y: Float, cornerX: Float, cornerY: Float): Boolean =
        abs(x - cornerX) <= touchSlop && abs(y - cornerY) <= touchSlop

    private fun applyDrag(dx: Float, dy: Float) {
        when (grab) {
            Grab.MOVE -> {
                crop.offset(dx, dy)
                clampIntoContent()
            }

            Grab.TOP_LEFT -> resize(left = dx, top = dy)
            Grab.TOP_RIGHT -> resize(right = dx, top = dy)
            Grab.BOTTOM_LEFT -> resize(left = dx, bottom = dy)
            Grab.BOTTOM_RIGHT -> resize(right = dx, bottom = dy)
            Grab.NONE -> Unit
        }
    }

    private fun resize(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f) {
        val ratio = aspect
        var newLeft = crop.left + left
        var newTop = crop.top + top
        var newRight = crop.right + right
        var newBottom = crop.bottom + bottom

        newLeft = max(content.left, min(newLeft, crop.right - minimumSize))
        newTop = max(content.top, min(newTop, crop.bottom - minimumSize))
        newRight = min(content.right, max(newRight, crop.left + minimumSize))
        newBottom = min(content.bottom, max(newBottom, crop.top + minimumSize))

        crop.set(newLeft, newTop, newRight, newBottom)

        if (ratio != null) {
            // Keep the locked ratio by adjusting the edge that was not dragged.
            val anchorLeft = left == 0f
            val anchorTop = top == 0f
            var width = crop.width()
            var height = width / ratio
            if (crop.top + height > content.bottom || crop.bottom - height < content.top) {
                height = min(crop.height(), content.height())
                width = height * ratio
            }
            if (anchorLeft) crop.right = crop.left + width else crop.left = crop.right - width
            if (anchorTop) crop.bottom = crop.top + height else crop.top = crop.bottom - height
            clampIntoContent()
        }
    }

    override fun performClick(): Boolean = super.performClick()
}
