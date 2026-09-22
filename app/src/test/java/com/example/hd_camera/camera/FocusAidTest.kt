package com.example.hd_camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The focus aids, fed synthetic frames.
 *
 * The part worth pinning down is the orientation. Preview frames arrive in sensor
 * orientation, a quarter turn from what a portrait phone shows, and the front camera is
 * mirrored on top of that — an aid drawn ninety degrees away from the picture is worse than
 * no aid, and it is exactly the sort of thing that looks fine until it is on a real phone.
 */
class FocusAidTest {

    // ── Nothing on, nothing emitted ────────────────────────────────────────

    @Test
    fun `with both aids off no mask is produced`() {
        val aid = FocusAid()
        assertFalse(aid.isActive)
        var emitted = false
        aid.analyze(flatFrame(WIDTH, HEIGHT, 128), WIDTH, HEIGHT, WIDTH, 0, false) { _, _, _ ->
            emitted = true
        }
        assertFalse(emitted)
    }

    // ── Zebra ──────────────────────────────────────────────────────────────

    @Test
    fun `a blown out frame is covered in bars of two tones`() {
        val aid = FocusAid().apply { zebraEnabled = true }
        val mask = maskOf(aid, flatFrame(WIDTH, HEIGHT, 255))
        val tones = mask.pixels.toSet()
        // Alternating light and dark bars, the way a broadcast monitor draws them, rather
        // than one flat wash that would hide the very detail being checked.
        assertEquals(2, tones.size)
        assertFalse("a clipped frame should be covered", tones.contains(0))
    }

    @Test
    fun `a frame well under clipping gets no zebra`() {
        val aid = FocusAid().apply { zebraEnabled = true }
        val mask = maskOf(aid, flatFrame(WIDTH, HEIGHT, 200))
        assertEquals(0, mask.pixels.count { it != 0 })
    }

    // ── Peaking ────────────────────────────────────────────────────────────

    @Test
    fun `a flat frame has no edges to mark`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val mask = maskOf(aid, flatFrame(WIDTH, HEIGHT, 128))
        assertEquals(0, mask.pixels.count { it != 0 })
    }

    @Test
    fun `peaking marks the edge and leaves the flat areas alone`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val mask = maskOf(aid, verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 2))
        val marked = mask.pixels.count { it != 0 }
        assertTrue("the edge was not found", marked > 0)
        // One edge in an otherwise flat frame: a thin line, not half the picture.
        assertTrue("far too much was marked", marked < mask.pixels.size / 4)
    }

    // ── Orientation ────────────────────────────────────────────────────────

    @Test
    fun `an unrotated frame keeps its shape`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val mask = maskOf(aid, verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 2), rotation = 0)
        assertEquals(WIDTH / SAMPLE_STEP, mask.width)
        assertEquals(HEIGHT / SAMPLE_STEP, mask.height)
    }

    @Test
    fun `a quarter turn swaps the mask's sides`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val mask = maskOf(aid, verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 2), rotation = 90)
        assertEquals(HEIGHT / SAMPLE_STEP, mask.width)
        assertEquals(WIDTH / SAMPLE_STEP, mask.height)
    }

    @Test
    fun `a vertical edge becomes a horizontal one after a quarter turn`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val upright = maskOf(aid, verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 2), rotation = 0)
        // Upright: every marked pixel shares a column.
        assertEquals(1, upright.markedColumns().size)
        assertTrue(upright.markedRows().size > 1)

        val turned = maskOf(aid, verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 2), rotation = 90)
        // Turned: the same line now shares a row instead.
        assertEquals(1, turned.markedRows().size)
        assertTrue(turned.markedColumns().size > 1)
    }

    @Test
    fun `a quarter turn turns the edge's column into its row`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val frame = verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 4)
        val upright = maskOf(aid, frame, rotation = 0)
        val turned = maskOf(aid, frame, rotation = 90)
        // A quarter turn clockwise sends the pixel at (x, y) to (height - 1 - y, x), so a
        // line standing at column n comes back lying at row n.
        assertEquals(upright.markedColumns().single(), turned.markedRows().single())
    }

    @Test
    fun `mirroring flips the mask across its middle`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val frame = verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 4)
        val plain = maskOf(aid, frame, rotation = 0, mirrored = false)
        val flipped = maskOf(aid, frame, rotation = 0, mirrored = true)

        val plainColumn = plain.markedColumns().single()
        val flippedColumn = flipped.markedColumns().single()
        assertEquals(plain.width - 1 - plainColumn, flippedColumn)
    }

    @Test
    fun `half a turn moves the edge to the opposite side and keeps the shape`() {
        val aid = FocusAid().apply { peakingEnabled = true }
        val frame = verticalEdgeFrame(WIDTH, HEIGHT, at = WIDTH / 4)
        val plain = maskOf(aid, frame, rotation = 0)
        val turned = maskOf(aid, frame, rotation = 180)
        assertEquals(plain.width, turned.width)
        assertEquals(plain.height, turned.height)
        assertEquals(
            plain.width - 1 - plain.markedColumns().single(),
            turned.markedColumns().single()
        )
    }

    // ── Buffers ────────────────────────────────────────────────────────────

    @Test
    fun `consecutive frames are handed different buffers`() {
        val aid = FocusAid().apply { zebraEnabled = true }
        val frame = flatFrame(WIDTH, HEIGHT, 255)
        var first: IntArray? = null
        var second: IntArray? = null
        aid.analyze(frame, WIDTH, HEIGHT, WIDTH, 0, false) { pixels, _, _ -> first = pixels }
        frame.rewind()
        aid.analyze(frame, WIDTH, HEIGHT, WIDTH, 0, false) { pixels, _, _ -> second = pixels }
        assertNull(
            "the analysis thread reused the buffer the last frame was still being read from",
            first?.takeIf { it === second }
        )
    }

    @Test
    fun `a frame too small to sample is skipped rather than crashing`() {
        val aid = FocusAid().apply { peakingEnabled = true; zebraEnabled = true }
        var emitted = false
        aid.analyze(flatFrame(4, 4, 255), 4, 4, 4, 0, false) { _, _, _ -> emitted = true }
        assertFalse(emitted)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private class Mask(val pixels: IntArray, val width: Int, val height: Int) {
        fun markedColumns(): Set<Int> = pixels.indices
            .filter { pixels[it] != 0 }
            .map { it % width }
            .toSet()

        fun markedRows(): Set<Int> = pixels.indices
            .filter { pixels[it] != 0 }
            .map { it / width }
            .toSet()
    }

    private fun maskOf(
        aid: FocusAid,
        frame: ByteBuffer,
        rotation: Int = 0,
        mirrored: Boolean = false
    ): Mask {
        frame.rewind()
        var result: Mask? = null
        aid.analyze(frame, WIDTH, HEIGHT, WIDTH, rotation, mirrored) { pixels, w, h ->
            result = Mask(pixels.copyOf(), w, h)
        }
        return requireNonNull(result)
    }

    private fun <T : Any> requireNonNull(value: T?): T =
        value ?: throw AssertionError("no mask was produced")

    private fun flatFrame(width: Int, height: Int, luma: Int): ByteBuffer =
        ByteBuffer.allocate(width * height).apply {
            repeat(width * height) { put(luma.toByte()) }
            rewind()
        }

    /** Black on the left of [at], white on the right: one hard vertical edge. */
    private fun verticalEdgeFrame(width: Int, height: Int, at: Int): ByteBuffer =
        ByteBuffer.allocate(width * height).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    put((if (x < at) 0 else 200).toByte())
                }
            }
            rewind()
        }

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 120

        /** Has to match FocusAid's own sampling, which the mask size depends on. */
        const val SAMPLE_STEP = 8
    }
}
