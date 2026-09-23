package com.example.hd_camera.camera

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The arithmetic behind the zoom dial: where a zoom ratio sits on the scale, how far a turn
 * of the dial moves the zoom, and where its ticks fall.
 *
 * The mapping is logarithmic, which is the whole point of having a wheel. Spread linearly,
 * a 0.5x–10x range gives the stretch from 1x to 2x about five percent of the travel — the
 * part people actually frame with — while most of the arc goes on the far end nobody can
 * hold steady anyway. On a log scale every doubling gets the same arc length, so 1x to 2x
 * is as easy to land on as 4x to 8x.
 *
 * Kept free of Android types so the cases a phone makes awkward — a camera that cannot
 * zoom, a turn past the end of the range, a ratio that arrives as NaN — can be tested.
 */
object ZoomArcMath {

    /** A camera with no zoom at all: both ends the same. */
    private const val FLAT_RANGE_EPSILON = 1e-4f

    /** Slack for tick values that land a hair off a tenth. */
    private const val TICK_EPSILON = 1e-3f

    /** A guard, not a design limit: a sane range produces a few dozen. */
    private const val MAX_TICKS = 400

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
     * How far round from the pointer [ratio] sits on a dial turned to [current], in degrees,
     * clockwise positive. Equal zoom factors take equal turns — the same log scale as the
     * progress mapping above — so the wide end reads on the left and the tele on the right.
     */
    fun dialOffset(ratio: Float, current: Float, degreesPerLn: Float): Float {
        if (!ratio.isFinite() || !current.isFinite() || !degreesPerLn.isFinite()) return 0f
        if (ratio <= 0f || current <= 0f) return 0f
        return ln(ratio / current) * degreesPerLn
    }

    /**
     * The zoom a turn of [deltaDegrees] leaves the dial on, from [start]. Turning clockwise
     * brings the wide end up to the pointer, so the zoom falls; the result is held inside
     * [range] however far the finger travels.
     */
    fun zoomAfterTurn(
        start: Float,
        deltaDegrees: Float,
        degreesPerLn: Float,
        range: ClosedFloatingPointRange<Float>
    ): Float {
        val min = range.start
        val max = range.endInclusive
        if (!min.isFinite() || !max.isFinite() || min <= 0f) return 1f
        val safeStart = if (start.isFinite() && start > 0f) start.coerceIn(min, max) else min
        if (!deltaDegrees.isFinite() || !degreesPerLn.isFinite() || abs(degreesPerLn) < 1e-3f) {
            return safeStart
        }
        return (safeStart * exp(-deltaDegrees / degreesPerLn)).coerceIn(min, max)
    }

    /**
     * Where the dial's minor ticks fall across [range]. Tenths up to 3×, then coarser steps
     * as the zoom climbs: on a log dial even tenths crowd together at the long end, and a
     * wall of lines there says nothing a thinner set would not.
     */
    fun ticks(range: ClosedFloatingPointRange<Float>): List<Float> {
        val min = range.start
        val max = range.endInclusive
        if (!min.isFinite() || !max.isFinite() || min <= 0f || max - min < FLAT_RANGE_EPSILON) {
            return emptyList()
        }
        val ticks = mutableListOf<Float>()
        var value = ceil(min * 10f - TICK_EPSILON) / 10f
        while (value <= max + TICK_EPSILON && ticks.size < MAX_TICKS) {
            ticks += value
            value = Math.round((value + tickStepAt(value)) * 100f) / 100f
        }
        return ticks
    }

    private fun tickStepAt(value: Float): Float = when {
        value < 3f - TICK_EPSILON -> 0.1f
        value < 6f - TICK_EPSILON -> 0.25f
        value < 10f - TICK_EPSILON -> 0.5f
        value < 20f - TICK_EPSILON -> 1f
        else -> 5f
    }

    /**
     * A dial label in the reader's own number format: "0,5" in Vietnamese, "0.5" in English,
     * one decimal at most and none on a whole number.
     */
    fun dialLabel(ratio: Float, locale: Locale): String {
        val safe = if (ratio.isFinite()) ratio else 1f
        val rounded = Math.round(safe * 10f) / 10f
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }.format(rounded)
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
