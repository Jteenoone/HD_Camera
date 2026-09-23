package com.example.hd_camera.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * The arithmetic behind the zoom wheel: where a finger on the arc lands on the zoom scale,
 * and where a zoom ratio sits on the arc.
 *
 * The mapping is logarithmic, which is the whole point of having a wheel. Spread linearly,
 * a 0.5x–10x range gives the stretch from 1x to 2x about five percent of the travel — the
 * part people actually frame with — while most of the arc goes on the far end nobody can
 * hold steady anyway. On a log scale every doubling gets the same arc length, so 1x to 2x
 * is as easy to land on as 4x to 8x.
 *
 * Kept free of Android types so the cases a phone makes awkward — a camera that cannot
 * zoom, a finger past the end of the arc, a ratio that arrives as NaN — can be tested.
 */
object ZoomArcMath {

    /** A camera with no zoom at all: both ends the same. */
    private const val FLAT_RANGE_EPSILON = 1e-4f

    /**
     * The zoom [progress] stands for, 0 at the wide end of the arc and 1 at the long end.
     * Anything outside 0..1, or not a number at all, is pulled back to the nearest end.
     */
    fun progressToZoom(progress: Float, range: ClosedFloatingPointRange<Float>): Float {
        val min = range.start
        val max = range.endInclusive
        if (!min.isFinite() || !max.isFinite() || min <= 0f) return 1f
        if (max - min < FLAT_RANGE_EPSILON) return min
        val safe = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        return (min * (max / min).pow(safe)).coerceIn(min, max)
    }

    /** Where [zoom] sits on the arc. The inverse of [progressToZoom]. */
    fun zoomToProgress(zoom: Float, range: ClosedFloatingPointRange<Float>): Float {
        val min = range.start
        val max = range.endInclusive
        if (!min.isFinite() || !max.isFinite() || min <= 0f) return 0f
        if (max - min < FLAT_RANGE_EPSILON) return 0f
        val safe = if (zoom.isFinite()) zoom.coerceIn(min, max) else min
        return (ln(safe / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Where a touch at [angle] falls along an arc that starts at [startAngle] and runs
     * [sweepAngle] degrees. All three are in the canvas's own degrees: zero at three
     * o'clock, growing clockwise.
     *
     * A finger that leaves the arc — dragged out past either end, or down into the dead
     * half of the circle — is held at whichever end it is nearer, so the wheel does not
     * jump from one extreme to the other as the thumb slips off it.
     */
    fun angleToProgress(angle: Float, startAngle: Float, sweepAngle: Float): Float {
        if (!angle.isFinite() || !sweepAngle.isFinite() || abs(sweepAngle) < FLAT_RANGE_EPSILON) {
            return 0f
        }
        var delta = (angle - startAngle) % 360f
        if (delta < 0f) delta += 360f
        if (delta <= sweepAngle) return (delta / sweepAngle).coerceIn(0f, 1f)

        // Past the far end: the gap beyond it against the gap back round to the start.
        val pastEnd = delta - sweepAngle
        val beforeStart = 360f - delta
        return if (pastEnd <= beforeStart) 1f else 0f
    }

    /**
     * The ratio read out for a screen reader: "1.7", "0.5", "2". [ZoomMath.label] is for
     * the eye and drops the leading zero, which a reader would say as "point five".
     */
    fun spoken(ratio: Float): String {
        if (!ratio.isFinite()) return "1"
        val rounded = Math.round(ratio * 10f) / 10f
        return if (abs(rounded - Math.round(rounded)) < 0.05f) {
            Math.round(rounded).toString()
        } else {
            String.format(Locale.US, "%.1f", rounded)
        }
    }
}
