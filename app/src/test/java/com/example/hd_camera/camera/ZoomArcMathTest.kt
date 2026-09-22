package com.example.hd_camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom wheel's arithmetic, including the cases a phone makes awkward to reach: a camera
 * that cannot zoom at all, a thumb dragged off the end of the arc, and a ratio that arrives
 * as NaN because a camera was closing while the wheel was still being drawn.
 */
class ZoomArcMathTest {

    // ── Progress to zoom ───────────────────────────────────────────────────

    @Test
    fun `the ends of the arc are the ends of the range`() {
        assertEquals(0.5f, ZoomArcMath.progressToZoom(0f, WIDE), TOLERANCE)
        assertEquals(10f, ZoomArcMath.progressToZoom(1f, WIDE), TOLERANCE)
    }

    @Test
    fun `a doubling takes the same arc wherever it happens`() {
        // The point of the log scale: 1x to 2x is as easy to land on as 4x to 8x.
        val oneToTwo = ZoomArcMath.zoomToProgress(2f, WIDE) - ZoomArcMath.zoomToProgress(1f, WIDE)
        val fourToEight =
            ZoomArcMath.zoomToProgress(8f, WIDE) - ZoomArcMath.zoomToProgress(4f, WIDE)
        assertEquals(oneToTwo, fourToEight, TOLERANCE)
    }

    @Test
    fun `the useful middle of the range gets real room on the arc`() {
        // Linearly, 1x to 2x would be a ninth of this arc. It has to be worth more.
        val span = ZoomArcMath.zoomToProgress(2f, WIDE) - ZoomArcMath.zoomToProgress(1f, WIDE)
        assertTrue("1x to 2x got only " + span + " of the arc", span > 0.2f)
    }

    @Test
    fun `progress outside the arc is held at the nearer end`() {
        assertEquals(0.5f, ZoomArcMath.progressToZoom(-2f, WIDE), TOLERANCE)
        assertEquals(10f, ZoomArcMath.progressToZoom(4f, WIDE), TOLERANCE)
    }

    // ── Round trips ────────────────────────────────────────────────────────

    @Test
    fun `a zoom survives the trip to the arc and back`() {
        listOf(0.5f, 0.8f, 1f, 1.7f, 3f, 7.2f, 10f).forEach { zoom ->
            val back = ZoomArcMath.progressToZoom(ZoomArcMath.zoomToProgress(zoom, WIDE), WIDE)
            assertEquals("round trip for " + zoom, zoom, back, zoom * 0.01f)
        }
    }

    @Test
    fun `a zoom outside the range comes back clamped, not wrapped`() {
        assertEquals(0f, ZoomArcMath.zoomToProgress(0.1f, WIDE), TOLERANCE)
        assertEquals(1f, ZoomArcMath.zoomToProgress(99f, WIDE), TOLERANCE)
    }

    // ── Ranges a device might actually report ──────────────────────────────

    @Test
    fun `a camera that cannot zoom does not divide by zero`() {
        val fixed = 1f..1f
        assertEquals(1f, ZoomArcMath.progressToZoom(0f, fixed), TOLERANCE)
        assertEquals(1f, ZoomArcMath.progressToZoom(1f, fixed), TOLERANCE)
        assertEquals(0f, ZoomArcMath.zoomToProgress(1f, fixed), TOLERANCE)
    }

    @Test
    fun `an ultra-wide that starts at 0_6 still spans its own range`() {
        val range = 0.6f..2f
        assertEquals(0.6f, ZoomArcMath.progressToZoom(0f, range), TOLERANCE)
        assertEquals(2f, ZoomArcMath.progressToZoom(1f, range), TOLERANCE)
        assertTrue(ZoomArcMath.zoomToProgress(1f, range) > 0f)
        assertTrue(ZoomArcMath.zoomToProgress(1f, range) < 1f)
    }

    @Test
    fun `a main lens from 1x to 10x puts 1x at the very start`() {
        val range = 1f..10f
        assertEquals(0f, ZoomArcMath.zoomToProgress(1f, range), TOLERANCE)
        assertEquals(1f, ZoomArcMath.zoomToProgress(10f, range), TOLERANCE)
    }

    @Test
    fun `a range that makes no sense falls back to 1x rather than crashing`() {
        assertEquals(1f, ZoomArcMath.progressToZoom(0.5f, 0f..10f), TOLERANCE)
        assertEquals(1f, ZoomArcMath.progressToZoom(0.5f, -1f..10f), TOLERANCE)
        assertEquals(0f, ZoomArcMath.zoomToProgress(2f, 0f..10f), TOLERANCE)
    }

    @Test
    fun `NaN and infinity are absorbed`() {
        assertEquals(0.5f, ZoomArcMath.progressToZoom(Float.NaN, WIDE), TOLERANCE)
        assertEquals(0f, ZoomArcMath.zoomToProgress(Float.NaN, WIDE), TOLERANCE)
        assertEquals(1f, ZoomArcMath.progressToZoom(Float.POSITIVE_INFINITY, 1f..1f), TOLERANCE)
        assertEquals(
            1f,
            ZoomArcMath.progressToZoom(0.5f, Float.NaN..Float.NaN),
            TOLERANCE
        )
    }

    // ── Touch angle to progress ────────────────────────────────────────────

    @Test
    fun `the start of the sweep is the start of the arc`() {
        assertEquals(0f, ZoomArcMath.angleToProgress(180f, 180f, 180f), TOLERANCE)
    }

    @Test
    fun `the top of a half circle is its middle`() {
        assertEquals(0.5f, ZoomArcMath.angleToProgress(270f, 180f, 180f), TOLERANCE)
    }

    @Test
    fun `the end of the sweep is the end of the arc`() {
        assertEquals(1f, ZoomArcMath.angleToProgress(360f, 180f, 180f), TOLERANCE)
        // The same point, named the other way round.
        assertEquals(1f, ZoomArcMath.angleToProgress(0f, 180f, 180f), TOLERANCE)
    }

    @Test
    fun `a finger below the arc is held at whichever end it is nearer`() {
        // Just past the long end, in the dead half of the circle.
        assertEquals(1f, ZoomArcMath.angleToProgress(20f, 180f, 180f), TOLERANCE)
        // Just short of the wide end, on the other side of the same dead half.
        assertEquals(0f, ZoomArcMath.angleToProgress(160f, 180f, 180f), TOLERANCE)
    }

    @Test
    fun `a sweep with no width does not divide by zero`() {
        assertEquals(0f, ZoomArcMath.angleToProgress(270f, 180f, 0f), TOLERANCE)
    }

    @Test
    fun `an angle that is not a number is absorbed`() {
        assertEquals(0f, ZoomArcMath.angleToProgress(Float.NaN, 180f, 180f), TOLERANCE)
    }

    // ── Spoken value ───────────────────────────────────────────────────────

    @Test
    fun `the spoken value keeps the leading zero a reader needs`() {
        // The on-screen label is ".5x"; a screen reader would say "point five".
        assertEquals("0.5", ZoomArcMath.spoken(0.5f))
        assertEquals("1", ZoomArcMath.spoken(1f))
        assertEquals("1.7", ZoomArcMath.spoken(1.72f))
        assertEquals("10", ZoomArcMath.spoken(10f))
    }

    private companion object {
        /** An ultra-wide to a long tele: the range a flagship reports. */
        val WIDE = 0.5f..10f
        const val TOLERANCE = 0.001f
    }
}
