package com.example.hd_camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pro dial's arithmetic. The cases that matter are the ones a phone makes awkward to
 * reach: the ends of a sensor's range, a camera whose range is a single value, and a dial
 * position that has been dragged past either end.
 */
class ExposureScaleTest {

    // ── ISO ────────────────────────────────────────────────────────────────

    @Test
    fun `the ends of the dial are the ends of the sensor's range`() {
        assertEquals(50, ExposureScale.isoAt(0f, 50, 6400))
        assertEquals(6400, ExposureScale.isoAt(1f, 50, 6400))
    }

    @Test
    fun `the middle of the ISO dial is the geometric middle, not the arithmetic one`() {
        // Halfway between 50 and 6400 in stops is 566, not 3225. A linear dial spent its
        // whole second half on values nobody shoots at.
        assertEquals(566.0, ExposureScale.isoAt(0.5f, 50, 6400).toDouble(), 2.0)
    }

    @Test
    fun `a dial dragged past either end still lands inside the range`() {
        assertEquals(50, ExposureScale.isoAt(-3f, 50, 6400))
        assertEquals(6400, ExposureScale.isoAt(9f, 50, 6400))
    }

    @Test
    fun `a sensor with one ISO reports that ISO whatever the dial says`() {
        assertEquals(100, ExposureScale.isoAt(0f, 100, 100))
        assertEquals(100, ExposureScale.isoAt(1f, 100, 100))
        assertEquals(0f, ExposureScale.positionOfIso(100, 100, 100), TOLERANCE)
    }

    @Test
    fun `an ISO round-trips through its dial position`() {
        val position = ExposureScale.positionOfIso(800, 50, 6400)
        assertEquals(800.0, ExposureScale.isoAt(position, 50, 6400).toDouble(), 2.0)
    }

    @Test
    fun `an ISO outside the range is pulled back before being placed`() {
        assertEquals(0f, ExposureScale.positionOfIso(10, 50, 6400), TOLERANCE)
        assertEquals(1f, ExposureScale.positionOfIso(999_999, 50, 6400), TOLERANCE)
    }

    // ── Shutter ────────────────────────────────────────────────────────────

    @Test
    fun `shutter runs from the sensor's shortest exposure to its longest`() {
        val shortest = 125_000L
        val longest = 1_000_000_000L
        assertEquals(shortest, ExposureScale.shutterAt(0f, shortest, longest))
        assertEquals(longest, ExposureScale.shutterAt(1f, shortest, longest))
    }

    @Test
    fun `a shutter speed round-trips through its dial position`() {
        val nanos = 8_000_000L
        val position = ExposureScale.positionOfShutter(nanos, 125_000L, 1_000_000_000L)
        val back = ExposureScale.shutterAt(position, 125_000L, 1_000_000_000L)
        assertEquals(nanos.toDouble(), back.toDouble(), nanos * 0.01)
    }

    @Test
    fun `an exposure is read as a fraction until it reaches a whole second`() {
        assertFalse(ExposureScale.isWholeSeconds(8_000_000L))
        assertTrue(ExposureScale.isWholeSeconds(1_000_000_000L))
        assertTrue(ExposureScale.isWholeSeconds(30_000_000_000L))
    }

    @Test
    fun `the denominator of a fraction is the one a photographer would say`() {
        assertEquals(125, ExposureScale.shutterDenominator(8_000_000L))
        assertEquals(60, ExposureScale.shutterDenominator(16_666_666L))
        assertEquals(8000, ExposureScale.shutterDenominator(125_000L))
    }

    @Test
    fun `a zero exposure cannot produce a divide by zero label`() {
        assertEquals(1_000_000_000, ExposureScale.shutterDenominator(0L))
    }

    // ── Exposure compensation ──────────────────────────────────────────────

    @Test
    fun `EV is the camera's index times the camera's own step`() {
        assertEquals(1.0, ExposureScale.evOf(3, 1.0 / 3.0), TOLERANCE.toDouble())
        assertEquals(-2.0, ExposureScale.evOf(-4, 0.5), TOLERANCE.toDouble())
    }

    @Test
    fun `an EV index stays inside what the camera accepts`() {
        assertEquals(6, ExposureScale.evIndexFor(4.0, 1.0 / 3.0, -6, 6))
        assertEquals(-6, ExposureScale.evIndexFor(-9.0, 1.0 / 3.0, -6, 6))
    }

    @Test
    fun `a camera with no exposure compensation reports index zero`() {
        assertEquals(0, ExposureScale.evIndexFor(2.0, 0.0, 0, 0))
        assertEquals(0, ExposureScale.evIndexAt(1f, 0, 0))
        assertEquals(0f, ExposureScale.positionOfEv(0, 0, 0), TOLERANCE)
    }

    @Test
    fun `the EV dial spans the range evenly`() {
        assertEquals(-6, ExposureScale.evIndexAt(0f, -6, 6))
        assertEquals(0, ExposureScale.evIndexAt(0.5f, -6, 6))
        assertEquals(6, ExposureScale.evIndexAt(1f, -6, 6))
        assertEquals(0.5f, ExposureScale.positionOfEv(0, -6, 6), TOLERANCE)
    }

    // ── Focus ──────────────────────────────────────────────────────────────

    @Test
    fun `focus runs from infinity to the closest the lens goes`() {
        val closest = 10f // dioptres, so 10 cm
        assertEquals(0f, ExposureScale.focusDioptresAt(0f, closest), TOLERANCE)
        assertEquals(closest, ExposureScale.focusDioptresAt(1f, closest), TOLERANCE)
    }

    @Test
    fun `a fixed focus lens has nowhere to go`() {
        assertEquals(0f, ExposureScale.focusDioptresAt(1f, 0f), TOLERANCE)
        assertEquals(0f, ExposureScale.positionOfFocus(5f, 0f), TOLERANCE)
    }

    @Test
    fun `zero dioptres reads as infinity rather than as a distance`() {
        assertTrue(ExposureScale.isInfinity(0f))
        assertFalse(ExposureScale.isInfinity(0.5f))
        assertEquals(0.1f, ExposureScale.metres(10f), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
