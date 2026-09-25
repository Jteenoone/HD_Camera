package com.digitalcamerahd.camera4k.selfiecamera.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Where a dial position lands on the sensor's own scale.
 *
 * ISO and shutter both run logarithmically — the useful steps between 50 and 6400 are not
 * evenly spaced in ISO, they are evenly spaced in stops — so a dial that ran linearly spent
 * most of its travel on values nobody wants. Everything here works in plain numbers rather
 * than [android.util.Range] so it can be tested without a device.
 */
object ExposureScale {

    /**
     * [position] is 0..1 along the dial. The result never leaves [lower]..[upper], which
     * come from the sensor: a camera that stops at ISO 3200 must not be offered 6400.
     */
    fun isoAt(position: Float, lower: Int, upper: Int): Int {
        val low = lower.coerceAtLeast(1)
        val high = upper.coerceAtLeast(low)
        if (high == low) return low
        val value = low * (high.toDouble() / low).pow(position.coerceIn(0f, 1f).toDouble())
        return value.roundToInt().coerceIn(low, high)
    }

    /** The inverse, so a value restored from elsewhere can put the marker back. */
    fun positionOfIso(iso: Int, lower: Int, upper: Int): Float {
        val low = lower.coerceAtLeast(1)
        val high = upper.coerceAtLeast(low)
        if (high == low) return 0f
        val position = ln(iso.coerceIn(low, high).toDouble() / low) / ln(high.toDouble() / low)
        return position.toFloat().coerceIn(0f, 1f)
    }

    /** Exposure time in nanoseconds, on the same logarithmic footing. */
    fun shutterAt(position: Float, lower: Long, upper: Long): Long {
        val low = lower.coerceAtLeast(1L)
        val high = upper.coerceAtLeast(low)
        if (high == low) return low
        val value = low * (high.toDouble() / low).pow(position.coerceIn(0f, 1f).toDouble())
        return value.roundToLong().coerceIn(low, high)
    }

    fun positionOfShutter(nanos: Long, lower: Long, upper: Long): Float {
        val low = lower.coerceAtLeast(1L)
        val high = upper.coerceAtLeast(low)
        if (high == low) return 0f
        val position = ln(nanos.coerceIn(low, high).toDouble() / low) / ln(high.toDouble() / low)
        return position.toFloat().coerceIn(0f, 1f)
    }

    /** True when the exposure is long enough to read as seconds rather than a fraction. */
    fun isWholeSeconds(nanos: Long): Boolean = nanos >= 1_000_000_000L

    fun seconds(nanos: Long): Double = nanos / 1_000_000_000.0

    /** The 125 of "1/125". Never zero, so the label cannot come out as 1/0. */
    fun shutterDenominator(nanos: Long): Int =
        (1_000_000_000.0 / nanos.coerceAtLeast(1L)).roundToInt().coerceAtLeast(1)

    /**
     * Exposure compensation is an index into the camera's own range, in steps of its own
     * size; the number the user reads is the two multiplied together.
     */
    fun evOf(index: Int, step: Double): Double = index * step

    /** The index nearest to [ev], kept inside what the camera accepts. */
    fun evIndexFor(ev: Double, step: Double, lower: Int, upper: Int): Int {
        if (step <= 0.0 || upper < lower) return 0
        return (ev / step).roundToInt().coerceIn(lower, upper)
    }

    /** A dial position for an index somewhere in [lower]..[upper]. */
    fun positionOfEv(index: Int, lower: Int, upper: Int): Float {
        val span = upper - lower
        if (span <= 0) return 0f
        return ((index - lower).toFloat() / span).coerceIn(0f, 1f)
    }

    fun evIndexAt(position: Float, lower: Int, upper: Int): Int {
        val span = upper - lower
        if (span <= 0) return lower
        return lower + (position.coerceIn(0f, 1f) * span).roundToInt()
    }

    /**
     * Focus is set in dioptres: 0 is infinity and [minFocusDistance] — the reciprocal of
     * the closest the lens will go — is as near as it focuses.
     */
    fun focusDioptresAt(position: Float, minFocusDistance: Float): Float =
        position.coerceIn(0f, 1f) * minFocusDistance.coerceAtLeast(0f)

    fun positionOfFocus(dioptres: Float, minFocusDistance: Float): Float {
        if (minFocusDistance <= 0f) return 0f
        return (dioptres / minFocusDistance).coerceIn(0f, 1f)
    }

    /** True when the lens is as good as at infinity. */
    fun isInfinity(dioptres: Float): Boolean = abs(dioptres) < 1e-4f

    /** Metres, from the dioptres the sensor works in. */
    fun metres(dioptres: Float): Float = if (isInfinity(dioptres)) Float.NaN else 1f / dioptres
}
