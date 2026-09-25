package com.digitalcamerahd.camera4k.selfiecamera.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.digitalcamerahd.camera4k.selfiecamera.R
import com.digitalcamerahd.camera4k.selfiecamera.camera.ZoomArcMath
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The zoom dial: the top of a large translucent disc whose rim carries the zoom scale.
 *
 * The scale turns and the pointer stays put. A yellow notch at the top marks where the
 * camera is, and dragging sideways rolls the scale under it, the way a lens barrel turns
 * under a fixed index line. The steps the camera really has are marked long and labelled
 * with their ratio and 35mm-equivalent focal length; the ticks between them get denser
 * toward the long end, because the scale is logarithmic.
 *
 * It decides nothing about lenses: it asks for a ratio and redraws whatever comes back.
 */
class ZoomArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Everything the camera can reach, which is what the scale spans. */
    var range: ClosedFloatingPointRange<Float> = 1f..1f
        set(value) {
            field = value
            ticks = ZoomArcMath.ticks(value)
            invalidate()
        }

    /**
     * What it can reach *now*. While a clip is recording the session cannot change lens, so
     * part of the scale is out of reach; those ticks are drawn faint and a turn stops there.
     */
    var reachable: ClosedFloatingPointRange<Float> = 1f..1f
        set(value) {
            field = value
            invalidate()
        }

    /** The steps worth marking and labelling, from the camera. */
    var stops: List<Float> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The main lens's 35mm-equivalent focal length, for the "26MM" line under each step.
     * Null leaves the line out: a guessed focal length is worse than none.
     */
    var baseFocalLengthMm: Float? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The ratio the camera is on. Setting it turns the dial without raising a callback. */
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
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var ticks: List<Float> = emptyList()

    // Geometry, recomputed in onSizeChanged.
    private var centreX = 0f
    private var centreY = 0f
    private var radius = 0f
    private var visibleHalfAngle = 0f

    // Drag state.
    private var dragging = false
    private var turned = false
    private var downX = 0f
    private var downY = 0f
    private var startZoom = 1f
    private var lastHapticStop: Float? = null
    private var snapAnimator: ValueAnimator? = null

    private val white = ContextCompat.getColor(context, R.color.white)
    private val pointerColour = ContextCompat.getColor(context, R.color.dc_amber)

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = white
        alpha = RIM_ALPHA
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = white
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = pointerColour
    }
    private val numberPaint = labelPaint(NUMBER_SP, R.font.ibm_plex_sans_medium)
    private val focalPaint = labelPaint(FOCAL_SP, R.font.ibm_plex_sans_medium).apply {
        letterSpacing = FOCAL_LETTER_SPACING
    }
    private val valuePaint = labelPaint(VALUE_SP, R.font.ibm_plex_sans_semibold).apply {
        color = pointerColour
    }
    private val pointerPath = Path()

    init {
        isClickable = true
        isFocusable = true
    }

    private fun labelPaint(sizeSp: Float, font: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sizeSp,
            resources.displayMetrics
        )
        // The fonts are bundled, but a layout preview has no access to them.
        typeface = runCatching { ResourcesCompat.getFont(context, font) }.getOrNull()
        // The dial is translucent; a bright scene behind it needs the text to hold its edge.
        setShadowLayer(TEXT_SHADOW_DP * density, 0f, density, SHADOW_COLOUR)
    }

    // ── Geometry ───────────────────────────────────────────────────────────

    /**
     * The disc is sized so its rim runs from one bottom corner of the view, over the top,
     * to the other: the view shows the cap of a circle wider than itself, and the rest of
     * the disc continues below it, behind the shutter panel.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = TOP_INSET_DP * density
        val cap = h - top
        val halfWidth = w / 2f
        if (cap <= 0f || halfWidth <= 0f) {
            radius = 0f
            return
        }
        radius = (halfWidth * halfWidth + cap * cap) / (2f * cap)
        centreX = halfWidth
        centreY = top + radius
        val edge = asin((halfWidth / radius).coerceAtMost(1f))
        visibleHalfAngle = Math.toDegrees(edge.toDouble()).toFloat()
        discPaint.shader = LinearGradient(
            0f, top, 0f, h.toFloat(),
            DISC_TOP_COLOUR, DISC_BOTTOM_COLOUR,
            Shader.TileMode.CLAMP
        )
    }

    /** Where [ratio] sits on the dial, in degrees clockwise from the pointer. */
    private fun offsetOf(ratio: Float): Float =
        ZoomArcMath.dialOffset(ratio, zoom, DEGREES_PER_LN)

    private fun isOnScreen(offset: Float): Boolean =
        abs(offset) <= visibleHalfAngle + OFFSCREEN_MARGIN_DEG

    // ── Drawing ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        canvas.drawCircle(centreX, centreY, radius, discPaint)
        canvas.drawCircle(centreX, centreY, radius, rimPaint)

        drawTicks(canvas)
        drawStops(canvas)
        drawValue(canvas)
        drawPointer(canvas)
    }

    private fun drawTicks(canvas: Canvas) {
        tickPaint.strokeWidth = TICK_WIDTH_DP * density
        val minGap = MIN_TICK_GAP_DP * density
        var lastDrawn: Float? = null
        ticks.forEach { ratio ->
            val offset = offsetOf(ratio)
            if (!isOnScreen(offset)) return@forEach
            // Where the scale crowds at the long end, a tick closer than a couple of dp to
            // the last one adds grey, not information.
            val previous = lastDrawn
            if (previous != null && arcLength(offset - previous) < minGap) return@forEach
            lastDrawn = offset
            val whole = abs(ratio - ratio.roundToInt()) < WHOLE_TOLERANCE
            tickPaint.alpha = when {
                !isReachable(ratio) -> UNREACHABLE_ALPHA
                whole -> WHOLE_TICK_ALPHA
                else -> TICK_ALPHA
            }
            val length = if (whole) WHOLE_TICK_LENGTH_DP else TICK_LENGTH_DP
            drawRadial(canvas, offset, length)
        }
    }

    private fun drawStops(canvas: Canvas) {
        tickPaint.strokeWidth = STOP_WIDTH_DP * density
        val locale = resources.configuration.locales[0]
        val focal = baseFocalLengthMm
        val labelGap = LABEL_MIN_GAP_DP * density
        // Labels are placed from the pointer outward, so the steps nearest the live value
        // win: one that would crowd a label already placed is left off, its tick kept.
        val placed = mutableListOf<Float>()
        stops.map { it to offsetOf(it) }
            .filter { (_, offset) -> isOnScreen(offset) }
            .sortedBy { (_, offset) -> abs(offset) }
            .forEach { (stop, offset) ->
                val alpha = if (isReachable(stop)) FULL_ALPHA else UNREACHABLE_ALPHA
                tickPaint.alpha = alpha
                drawRadial(canvas, offset, STOP_LENGTH_DP)

                val crowdsValue = arcLength(offset) < VALUE_CLEARANCE_DP * density
                val crowdsLabel = placed.any { arcLength(offset - it) < labelGap }
                if (crowdsLabel || !labelFits(offset)) return@forEach
                placed += offset

                canvas.save()
                canvas.rotate(offset, centreX, centreY)
                // Under the pointer the live value takes the number's place; the focal
                // length below it stays, since it describes the same spot.
                if (!crowdsValue) {
                    numberPaint.alpha = alpha
                    canvas.drawText(
                        ZoomArcMath.dialLabel(stop, locale),
                        centreX,
                        rimY() + NUMBER_DROP_DP * density,
                        numberPaint
                    )
                }
                if (focal != null) {
                    focalPaint.alpha = alpha * FOCAL_ALPHA / FULL_ALPHA
                    canvas.drawText(
                        context.getString(R.string.zoom_focal_mm, (focal * stop).roundToInt()),
                        centreX,
                        rimY() + FOCAL_DROP_DP * density,
                        focalPaint
                    )
                }
                canvas.restore()
            }
    }

    /**
     * Near the corners the rim drops toward the bottom of the view, and a label there would
     * be cut in half by the panel below. Only labels that clear the bottom are drawn.
     */
    private fun labelFits(offset: Float): Boolean {
        val lowest = radius - (FOCAL_DROP_DP + LABEL_DESCENT_DP) * density
        val y = centreY - lowest * cos(Math.toRadians(offset.toDouble())).toFloat()
        return y <= height
    }

    private fun drawValue(canvas: Canvas) {
        val locale = resources.configuration.locales[0]
        canvas.drawText(
            ZoomArcMath.dialLabel(zoom, locale) + "×",
            centreX,
            rimY() + NUMBER_DROP_DP * density,
            valuePaint
        )
    }

    /** A small downward notch on the rim, which the scale turns under. */
    private fun drawPointer(canvas: Canvas) {
        val top = rimY() + POINTER_INSET_DP * density
        val half = POINTER_HALF_WIDTH_DP * density
        pointerPath.reset()
        pointerPath.moveTo(centreX - half, top)
        pointerPath.lineTo(centreX + half, top)
        pointerPath.lineTo(centreX, top + POINTER_HEIGHT_DP * density)
        pointerPath.close()
        canvas.drawPath(pointerPath, pointerPaint)
    }

    /** A tick hanging inward from the rim at [offset] degrees from the pointer. */
    private fun drawRadial(canvas: Canvas, offset: Float, lengthDp: Float) {
        val radians = Math.toRadians(offset.toDouble())
        val sine = sin(radians).toFloat()
        val cosine = cos(radians).toFloat()
        val outer = radius - TICK_INSET_DP * density
        val inner = outer - lengthDp * density
        canvas.drawLine(
            centreX + outer * sine,
            centreY - outer * cosine,
            centreX + inner * sine,
            centreY - inner * cosine,
            tickPaint
        )
    }

    private fun rimY(): Float = centreY - radius

    private fun arcLength(degrees: Float): Float =
        abs(Math.toRadians(degrees.toDouble()).toFloat() * radius)

    private fun isReachable(ratio: Float): Boolean =
        ratio >= reachable.start - REACH_TOLERANCE &&
            ratio <= reachable.endInclusive + REACH_TOLERANCE

    // ── Touch ──────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (radius <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the disc itself is a control; the clear corners beside it fall
                // through to the viewfinder underneath.
                val distance = hypot(event.x - centreX, event.y - centreY)
                if (distance > radius + TOUCH_MARGIN_DP * density) return false
                snapAnimator?.cancel()
                dragging = true
                turned = false
                downX = event.x
                downY = event.y
                startZoom = zoom
                lastHapticStop = null
                parent?.requestDisallowInterceptTouchEvent(true)
                onZoomStart?.invoke()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val dx = event.x - downX
                if (!turned && hypot(dx, event.y - downY) < touchSlop) return true
                turned = true
                // Sideways travel along the rim, as the angle it sweeps at the centre: the
                // tick under the finger stays under the finger.
                val degrees = Math.toDegrees((dx / radius).toDouble()).toFloat()
                val requested =
                    ZoomArcMath.zoomAfterTurn(startZoom, degrees, DEGREES_PER_LN, range)
                requestZoom(requested)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (!turned) {
                        val stop = stopNear(event.x, event.y)
                        if (stop != null) {
                            // A tap on a labelled step rolls the dial there.
                            snapTo(stop)
                            performClick()
                            return true
                        }
                    }
                    performClick()
                }
                // The ratio the finger left it on is kept; a cancel is not a rollback.
                onZoomEnd?.invoke()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean = super.performClick()

    private fun requestZoom(requested: Float) {
        // Out of reach is clamped rather than refused, so the dial keeps following the
        // finger instead of sticking while the picture does nothing.
        val allowed = requested.coerceIn(reachable.start, reachable.endInclusive)
        hapticIfCrossingStop(allowed)
        onZoomChanged?.invoke(allowed)
    }

    /** The labelled step whose label [x], [y] lands on, if any. */
    private fun stopNear(x: Float, y: Float): Float? {
        val labelRadius = radius - (NUMBER_DROP_DP + FOCAL_DROP_DP) / 2f * density
        return stops
            .filter { isReachable(it) && isOnScreen(offsetOf(it)) }
            .map { stop ->
                val radians = Math.toRadians(offsetOf(stop).toDouble())
                val labelX = centreX + labelRadius * sin(radians).toFloat()
                val labelY = centreY - labelRadius * cos(radians).toFloat()
                stop to hypot(x - labelX, y - labelY)
            }
            .filter { (_, distance) -> distance < LABEL_HIT_DP * density }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    /** Rolls the dial to [target] over a short ease, in log steps so it moves evenly. */
    private fun snapTo(target: Float) {
        snapAnimator?.cancel()
        val from = zoom
        if (from <= 0f || target <= 0f) return
        val span = ln(target / from)
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { requestZoom(from * exp(span * it.animatedFraction)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onZoomEnd?.invoke()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        snapAnimator?.cancel()
        snapAnimator = null
        super.onDetachedFromWindow()
    }

    /**
     * A tick at every step would buzz continuously through a turn. Only the marked steps
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
     * The dial is a shortcut, not the only way in: the quick-zoom chips stay on screen for
     * anyone who cannot make this gesture. It still answers to the scroll actions, so a
     * screen reader can step the zoom without turning anything.
     */
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.stateDescription = contentDescription
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> ACCESSIBILITY_STEP
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -ACCESSIBILITY_STEP
            else -> return super.performAccessibilityAction(action, arguments)
        }
        val progress = ZoomArcMath.zoomToProgress(zoom, range) + step
        requestZoom(ZoomArcMath.progressToZoom(progress, range))
        return true
    }

    private fun updateAccessibilityValue() {
        contentDescription = context.getString(R.string.cd_zoom_value, ZoomArcMath.spoken(zoom))
    }

    private companion object {
        /**
         * How far the dial turns per e-fold of zoom: about 28° per doubling, so 0.5× and 2×
         * sit either side of 1× with room for their labels.
         */
        const val DEGREES_PER_LN = 40f

        /** Room above the rim, so the pointer and the rim's hairline are not clipped. */
        const val TOP_INSET_DP = 4f

        const val TICK_INSET_DP = 3f
        const val TICK_WIDTH_DP = 1.2f
        const val TICK_LENGTH_DP = 9f
        const val WHOLE_TICK_LENGTH_DP = 13f
        const val STOP_WIDTH_DP = 2f
        const val STOP_LENGTH_DP = 18f
        const val MIN_TICK_GAP_DP = 2.5f

        const val POINTER_INSET_DP = 1f
        const val POINTER_HALF_WIDTH_DP = 5f
        const val POINTER_HEIGHT_DP = 9f

        /** Baselines of the two label lines, measured in from the rim. */
        const val NUMBER_DROP_DP = 40f
        const val FOCAL_DROP_DP = 55f

        /** How far the focal line reaches below its baseline once it is turned on the rim. */
        const val LABEL_DESCENT_DP = 6f

        /** A step label nearer the pointer than this would sit under the live value. */
        const val VALUE_CLEARANCE_DP = 30f

        /** Room a label needs along the rim: "122MM" at 10sp, with air either side. */
        const val LABEL_MIN_GAP_DP = 52f

        const val NUMBER_SP = 15f
        const val VALUE_SP = 17f
        const val FOCAL_SP = 10f
        const val FOCAL_LETTER_SPACING = 0.06f
        const val TEXT_SHADOW_DP = 3f

        /** A touch just outside the rim still counts: the rim is where the thumb aims. */
        const val TOUCH_MARGIN_DP = 16f
        const val LABEL_HIT_DP = 30f
        const val SNAP_MS = 220L

        /** Draw a little past the visible edge so ticks slide in rather than pop in. */
        const val OFFSCREEN_MARGIN_DEG = 4f

        const val FULL_ALPHA = 255
        const val WHOLE_TICK_ALPHA = 220
        const val TICK_ALPHA = 150
        const val UNREACHABLE_ALPHA = 60
        const val FOCAL_ALPHA = 150
        const val RIM_ALPHA = 46

        /** Translucent graphite, lighter at the rim, as the reference dial is drawn. */
        const val DISC_TOP_COLOUR = 0x8C3A3A40.toInt()
        const val DISC_BOTTOM_COLOUR = 0xB31A1A1E.toInt()
        const val SHADOW_COLOUR = 0x99000000.toInt()

        const val WHOLE_TOLERANCE = 0.01f

        /** How near a step counts as passing it, for the tick of feedback. */
        const val STOP_SNAP = 0.06f
        const val REACH_TOLERANCE = 0.02f

        /** One scroll action moves this much of the range. */
        const val ACCESSIBILITY_STEP = 0.05f
    }
}
