package com.example.hd_camera.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.example.hd_camera.R
import com.example.hd_camera.camera.ZoomArcMath
import com.example.hd_camera.camera.ZoomMath
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The half-circle zoom wheel.
 *
 * It draws an arc, ticks it, marks the steps the camera really has, and turns a finger on
 * it into a zoom ratio. It decides nothing else: which lens that ratio needs, and whether
 * the camera will take it at all, stay with the engine. The view asks for a ratio and
 * redraws whatever comes back.
 */
class ZoomArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Everything the camera can reach, which is what the arc spans. */
    var range: ClosedFloatingPointRange<Float> = 1f..1f
        set(value) {
            field = value
            invalidate()
        }

    /**
     * What it can reach *now*. While a clip is recording the session cannot change lens, so
     * part of the arc is out of reach; those ticks are drawn faint and a drag stops there.
     */
    var reachable: ClosedFloatingPointRange<Float> = 1f..1f
        set(value) {
            field = value
            invalidate()
        }

    /** The steps worth marking, from the camera. */
    var stops: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** The ratio the camera is on. Setting it redraws without raising a callback. */
    var zoom: Float = 1f
        set(value) {
            field = value
            updateAccessibilityValue()
            invalidate()
        }

    var onZoomStart: (() -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null
    var onZoomEnd: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val arcRect = RectF()
    private var lastHapticStop: Float? = null
    private var dragging = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = TRACK_WIDTH_DP * density
        color = ContextCompat.getColor(context, R.color.dc_scrim_62)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.dc_text_70)
    }
    private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.dc_text)
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.dc_accent)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dc_text)
        textAlign = Paint.Align.CENTER
        textSize = LABEL_SP * resources.displayMetrics.scaledDensity
    }

    init {
        isClickable = true
        isFocusable = true
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = arcRadius()
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = arcCentreY()

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        drawTicks(canvas, cx, cy, radius)
        drawStops(canvas, cx, cy, radius)
        drawIndicator(canvas, cx, cy, radius)

        // The value sits under the arc's middle, where the thumb is not covering it.
        canvas.drawText(
            ZoomMath.label(zoom),
            cx,
            cy - radius + LABEL_DROP_DP * density,
            labelPaint
        )
    }

    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        tickPaint.strokeWidth = TICK_WIDTH_DP * density
        for (index in 0..TICK_COUNT) {
            val progress = index.toFloat() / TICK_COUNT
            val ratio = ZoomArcMath.progressToZoom(progress, range)
            // A tick the camera cannot reach right now reads as unavailable, not missing.
            tickPaint.alpha = if (isReachable(ratio)) TICK_ALPHA else UNREACHABLE_ALPHA
            drawRadial(canvas, cx, cy, radius, progress, TICK_LENGTH_DP, tickPaint)
        }
    }

    private fun drawStops(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        stopPaint.strokeWidth = STOP_WIDTH_DP * density
        stops.forEach { stop ->
            stopPaint.alpha = if (isReachable(stop)) FULL_ALPHA else UNREACHABLE_ALPHA
            drawRadial(
                canvas,
                cx,
                cy,
                radius,
                ZoomArcMath.zoomToProgress(stop, range),
                STOP_LENGTH_DP,
                stopPaint
            )
        }
    }

    private fun drawIndicator(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val angle = angleFor(ZoomArcMath.zoomToProgress(zoom, range))
        val radians = Math.toRadians(angle.toDouble())
        canvas.drawCircle(
            cx + (radius * cos(radians)).toFloat(),
            cy + (radius * sin(radians)).toFloat(),
            INDICATOR_RADIUS_DP * density,
            indicatorPaint
        )
    }

    private fun drawRadial(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        progress: Float,
        lengthDp: Float,
        paint: Paint
    ) {
        val radians = Math.toRadians(angleFor(progress).toDouble())
        val cosine = cos(radians).toFloat()
        val sine = sin(radians).toFloat()
        val inner = radius - lengthDp * density / 2f
        val outer = radius + lengthDp * density / 2f
        canvas.drawLine(
            cx + inner * cosine,
            cy + inner * sine,
            cx + outer * cosine,
            cy + outer * sine,
            paint
        )
    }

    private fun angleFor(progress: Float): Float = START_ANGLE + SWEEP_ANGLE * progress

    /** The arc sits against the bottom of the view, opening upward. */
    private fun arcCentreY(): Float = height - PADDING_DP * density

    private fun arcRadius(): Float {
        val horizontal = width / 2f - PADDING_DP * density
        val vertical = height - PADDING_DP * 2f * density
        return minOf(horizontal, vertical).coerceAtLeast(0f)
    }

    private fun isReachable(ratio: Float): Boolean =
        ratio >= reachable.start - REACH_TOLERANCE &&
            ratio <= reachable.endInclusive + REACH_TOLERANCE

    // ── Touch ──────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val radius = arcRadius()
        if (radius <= 0f) return false
        val cx = width / 2f
        val cy = arcCentreY()
        val distance = hypot(event.x - cx, event.y - cy)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a touch on the band itself is a drag; anywhere else falls through
                // so the screen underneath is not made unreachable by an invisible box.
                if (abs(distance - radius) > TOUCH_BAND_DP * density) return false
                dragging = true
                lastHapticStop = null
                parent?.requestDisallowInterceptTouchEvent(true)
                onZoomStart?.invoke()
                applyTouch(event, cx, cy)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                applyTouch(event, cx, cy)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // The ratio the finger left it on is kept; a cancel is not a rollback.
                onZoomEnd?.invoke()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean = super.performClick()

    private fun applyTouch(event: MotionEvent, cx: Float, cy: Float) {
        val degrees = Math.toDegrees(atan2(event.y - cy, event.x - cx).toDouble()).toFloat()
        val normalised = (degrees + 360f) % 360f
        val progress = ZoomArcMath.angleToProgress(normalised, START_ANGLE, SWEEP_ANGLE)
        val requested = ZoomArcMath.progressToZoom(progress, range)
        // Out of reach is clamped rather than refused, so the wheel keeps following the
        // thumb instead of sticking while the picture does nothing.
        val allowed = requested.coerceIn(reachable.start, reachable.endInclusive)
        hapticIfCrossingStop(allowed)
        onZoomChanged?.invoke(allowed)
    }

    /**
     * A tick at every step would buzz continuously through a drag. Only the marked steps
     * get one, and only on the way past. [performHapticFeedback] already honours the
     * system's touch-feedback setting.
     */
    private fun hapticIfCrossingStop(ratio: Float) {
        val crossed = stops.firstOrNull { abs(it - ratio) < STOP_SNAP } ?: run {
            lastHapticStop = null
            return
        }
        if (lastHapticStop == crossed) return
        lastHapticStop = crossed
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // ── Accessibility ──────────────────────────────────────────────────────

    /**
     * The wheel is a shortcut, not the only way in: the quick-zoom chips stay on screen for
     * anyone who cannot make this gesture. It still answers to the scroll actions, so a
     * screen reader can step the zoom without dragging an arc.
     */
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.stateDescription = contentDescription
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val step = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> ACCESSIBILITY_STEP
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -ACCESSIBILITY_STEP
            else -> return super.performAccessibilityAction(action, arguments)
        }
        val progress = ZoomArcMath.zoomToProgress(zoom, range) + step
        val requested = ZoomArcMath.progressToZoom(progress, range)
        onZoomChanged?.invoke(requested.coerceIn(reachable.start, reachable.endInclusive))
        return true
    }

    private fun updateAccessibilityValue() {
        contentDescription = context.getString(R.string.cd_zoom_value, ZoomArcMath.spoken(zoom))
    }

    private companion object {
        /** A half circle opening upward: nine o'clock, over the top, to three o'clock. */
        const val START_ANGLE = 180f
        const val SWEEP_ANGLE = 180f

        const val PADDING_DP = 12f
        const val TRACK_WIDTH_DP = 2f
        const val TICK_WIDTH_DP = 1.5f
        const val TICK_LENGTH_DP = 7f
        const val STOP_WIDTH_DP = 2.5f
        const val STOP_LENGTH_DP = 13f
        const val INDICATOR_RADIUS_DP = 5.5f
        const val TOUCH_BAND_DP = 34f
        const val LABEL_SP = 20f
        const val LABEL_DROP_DP = 34f

        const val TICK_COUNT = 40
        const val FULL_ALPHA = 255
        const val TICK_ALPHA = 150
        const val UNREACHABLE_ALPHA = 60

        /** How near a step counts as passing it, for the tick of feedback. */
        const val STOP_SNAP = 0.06f
        const val REACH_TOLERANCE = 0.02f

        /** One scroll action moves this much of the arc. */
        const val ACCESSIBILITY_STEP = 0.05f
    }
}
