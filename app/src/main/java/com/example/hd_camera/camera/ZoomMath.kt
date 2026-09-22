package com.example.hd_camera.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * The arithmetic behind the zoom control, kept away from the camera so it can be tested
 * without one: what a pinch does to the current ratio, which quick-zoom steps a camera with
 * a given range can actually reach, and how a ratio reads on screen.
 */
object ZoomMath {

    /** Below this the field of view is wider than a main lens reaches on its own. */
    const val WIDE_THRESHOLD = 0.95f

    /** How close a ratio has to be to a step before the chip for it counts as selected. */
    private const val STEP_TOLERANCE = 0.05f

    /** The quick-zoom steps the design offers above 1x. */
    private val STEPS_ABOVE_ONE = listOf(2f, 3f, 5f)

    fun clamp(ratio: Float, range: ClosedFloatingPointRange<Float>): Float =
        ratio.coerceIn(range.start, range.endInclusive)

    /**
     * One pinch event. [factor] is the scale the gesture reports since the previous event,
     * so the ratio grows and shrinks with the fingers and never leaves [range].
     */
    fun pinch(current: Float, factor: Float, range: ClosedFloatingPointRange<Float>): Float =
        clamp(current * factor, range)

    /**
     * The chips: the widest lens when there is one wider than the main lens, 1x, and
     * whichever of 2x / 3x / 5x the camera really reaches. A step outside [range] would be a
     * chip that silently did nothing, so it is left out.
     */
    fun stops(range: ClosedFloatingPointRange<Float>): List<Float> {
        val stops = mutableListOf<Float>()
        if (range.start < WIDE_THRESHOLD) stops += roundToTenth(range.start)
        stops += clamp(1f, range)
        STEPS_ABOVE_ONE.forEach { step -> if (step <= range.endInclusive) stops += step }
        return stops.distinct()
    }

    /** ".5×", "1×", "2.4×": the leading zero and a trailing ".0" are both noise. */
    fun label(ratio: Float): String {
        val rounded = roundToTenth(ratio)
        val text = if (abs(rounded - round(rounded)) < STEP_TOLERANCE) {
            round(rounded).toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", rounded).removePrefix("0")
        }
        return text + "×"
    }

    /** True when [ratio] is the step [stop] stands for, give or take rounding. */
    fun matches(ratio: Float, stop: Float): Boolean = abs(ratio - stop) < STEP_TOLERANCE

    /** True when [ratio] is inside [range], forgiving the rounding a chip label carries. */
    fun reachable(ratio: Float, range: ClosedFloatingPointRange<Float>): Boolean =
        ratio >= range.start - STEP_TOLERANCE && ratio <= range.endInclusive + STEP_TOLERANCE

    private fun roundToTenth(value: Float): Float = round(value * 10f) / 10f
}
