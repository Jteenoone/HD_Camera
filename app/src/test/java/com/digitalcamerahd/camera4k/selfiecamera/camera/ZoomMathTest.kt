package com.digitalcamerahd.camera4k.selfiecamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom control's arithmetic. These are the cases a phone makes awkward to reach by
 * hand: a camera with no zoom at all, one that only reaches 2x, and a pinch that runs off
 * either end of the range.
 */
class ZoomMathTest {

    @Test
    fun `clamp holds a request inside the camera's range`() {
        assertEquals(0.5f, ZoomMath.clamp(0.1f, 0.5f..10f), TOLERANCE)
        assertEquals(10f, ZoomMath.clamp(42f, 0.5f..10f), TOLERANCE)
        assertEquals(2.5f, ZoomMath.clamp(2.5f, 0.5f..10f), TOLERANCE)
    }

    @Test
    fun `a pinch scales the current ratio`() {
        assertEquals(2f, ZoomMath.pinch(1f, 2f, 1f..10f), TOLERANCE)
        assertEquals(0.5f, ZoomMath.pinch(1f, 0.5f, 0.5f..10f), TOLERANCE)
    }

    @Test
    fun `a pinch cannot leave the range in either direction`() {
        // Pinching in on a camera that stops at 1x stays at 1x rather than going negative.
        assertEquals(1f, ZoomMath.pinch(1f, 0.25f, 1f..10f), TOLERANCE)
        assertEquals(10f, ZoomMath.pinch(8f, 4f, 1f..10f), TOLERANCE)
    }

    @Test
    fun `a camera that cannot zoom offers a single step`() {
        assertEquals(listOf(1f), ZoomMath.stops(1f..1f))
    }

    @Test
    fun `steps past the camera's ceiling are left out`() {
        assertEquals(listOf(1f, 2f), ZoomMath.stops(1f..2f))
        assertEquals(listOf(1f, 2f, 3f), ZoomMath.stops(1f..4.9f))
    }

    @Test
    fun `an ultra-wide adds a step below 1x`() {
        assertEquals(listOf(0.5f, 1f, 2f, 3f, 5f), ZoomMath.stops(0.5f..10f))
    }

    @Test
    fun `the widest step is the lens's own figure, not a rounded 0_5`() {
        // A 0.6x ultra-wide gets a .6× chip: a .5× one would ask for a ratio it cannot reach.
        assertEquals(listOf(0.6f, 1f, 2f), ZoomMath.stops(0.6f..2f))
    }

    @Test
    fun `labels drop the noise around the figure`() {
        assertEquals(".5×", ZoomMath.label(0.5f))
        assertEquals(".6×", ZoomMath.label(0.6f))
        assertEquals("1×", ZoomMath.label(1f))
        assertEquals("1×", ZoomMath.label(0.99f))
        assertEquals("2.4×", ZoomMath.label(2.43f))
        assertEquals("10×", ZoomMath.label(10f))
    }

    @Test
    fun `a chip is selected by the ratio it stands for, give or take rounding`() {
        assertTrue(ZoomMath.matches(2.01f, 2f))
        assertFalse(ZoomMath.matches(2.4f, 2f))
    }

    @Test
    fun `a step outside the range of the moment is not reachable`() {
        // While a clip is recording the session stays on its lens, so 0.5x is out of reach.
        assertFalse(ZoomMath.reachable(0.5f, 1f..10f))
        assertTrue(ZoomMath.reachable(0.5f, 0.5f..10f))
        assertFalse(ZoomMath.reachable(5f, 1f..3f))
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}
