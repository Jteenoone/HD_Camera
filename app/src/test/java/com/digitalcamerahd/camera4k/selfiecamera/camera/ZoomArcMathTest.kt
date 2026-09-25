package com.digitalcamerahd.camera4k.selfiecamera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * The zoom dial's arithmetic, including the cases a phone makes awkward to reach: a camera
 * that cannot zoom at all, a turn past the end of the range, and a ratio that arrives as
 * NaN because a camera was closing while the dial was still being drawn.
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

    // ── Dial offset ────────────────────────────────────────────────────────

    @Test
    fun `the ratio the dial is turned to sits under the pointer`() {
        assertEquals(0f, ZoomArcMath.dialOffset(1.2f, 1.2f, DEGREES_PER_LN), TOLERANCE)
    }

    @Test
    fun `wider ratios sit left of the pointer and longer ones right of it`() {
        assertTrue(ZoomArcMath.dialOffset(0.5f, 1f, DEGREES_PER_LN) < 0f)
        assertTrue(ZoomArcMath.dialOffset(3f, 1f, DEGREES_PER_LN) > 0f)
    }

    @Test
    fun `every doubling takes the same turn of the dial`() {
        val oneToTwo = ZoomArcMath.dialOffset(2f, 1f, DEGREES_PER_LN)
        val fourToEight = ZoomArcMath.dialOffset(8f, 4f, DEGREES_PER_LN)
        assertEquals(oneToTwo, fourToEight, TOLERANCE)
    }

    @Test
    fun `an offset from a broken ratio is zero rather than NaN`() {
        assertEquals(0f, ZoomArcMath.dialOffset(Float.NaN, 1f, DEGREES_PER_LN), TOLERANCE)
        assertEquals(0f, ZoomArcMath.dialOffset(2f, 0f, DEGREES_PER_LN), TOLERANCE)
    }

    // ── Turning the dial ───────────────────────────────────────────────────

    @Test
    fun `turning by an offset lands on the ratio that was there`() {
        val offset = ZoomArcMath.dialOffset(3f, 1f, DEGREES_PER_LN)
        // The finger drags the scale the other way round to bring 3x up to the pointer.
        assertEquals(
            3f,
            ZoomArcMath.zoomAfterTurn(1f, -offset, DEGREES_PER_LN, WIDE),
            TOLERANCE
        )
    }

    @Test
    fun `a turn past either end is held at that end`() {
        assertEquals(10f, ZoomArcMath.zoomAfterTurn(1f, -720f, DEGREES_PER_LN, WIDE), TOLERANCE)
        assertEquals(0.5f, ZoomArcMath.zoomAfterTurn(1f, 720f, DEGREES_PER_LN, WIDE), TOLERANCE)
    }

    @Test
    fun `a turn that is not a number leaves the zoom where it was`() {
        assertEquals(
            2f,
            ZoomArcMath.zoomAfterTurn(2f, Float.NaN, DEGREES_PER_LN, WIDE),
            TOLERANCE
        )
    }

    // ── Ticks ──────────────────────────────────────────────────────────────

    @Test
    fun `ticks start and end on the range`() {
        val ticks = ZoomArcMath.ticks(WIDE)
        assertEquals(0.5f, ticks.first(), TOLERANCE)
        assertEquals(10f, ticks.last(), TOLERANCE)
    }

    @Test
    fun `ticks are tenths below 3x and coarser above`() {
        val ticks = ZoomArcMath.ticks(WIDE)
        assertTrue(ticks.any { abs(it - 1.2f) < TOLERANCE })
        assertTrue(ticks.none { abs(it - 3.1f) < TOLERANCE })
        assertTrue(ticks.any { abs(it - 3.25f) < TOLERANCE })
    }

    @Test
    fun `a camera that cannot zoom gets no ticks`() {
        assertTrue(ZoomArcMath.ticks(1f..1f).isEmpty())
        assertTrue(ZoomArcMath.ticks(Float.NaN..Float.NaN).isEmpty())
    }

    // ── Dial labels ────────────────────────────────────────────────────────

    @Test
    fun `dial labels follow the reader's decimal separator`() {
        assertEquals("0,5", ZoomArcMath.dialLabel(0.5f, Locale.forLanguageTag("vi")))
        assertEquals("0.5", ZoomArcMath.dialLabel(0.5f, Locale.US))
        assertEquals("1,2", ZoomArcMath.dialLabel(1.24f, Locale.forLanguageTag("vi")))
        assertEquals("3", ZoomArcMath.dialLabel(3f, Locale.US))
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
        const val DEGREES_PER_LN = 25f
    }
}
