package com.digitalcamerahd.camera4k.selfiecamera.camera

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Focus peaking and zebra stripes, both read off the luma plane the histogram already
 * walks. One pass over a sampled grid produces both, so turning them on costs a comparison
 * per sampled pixel rather than a second trip through the frame.
 *
 * The frame arrives in sensor orientation, which on a portrait phone is a quarter turn away
 * from what is on screen, and the front camera's preview is mirrored on top of that. The
 * mask is written straight into display orientation so the overlay can draw it as it is —
 * an aid that is rotated ninety degrees from the picture is worse than none.
 */
class FocusAid {

    var peakingEnabled: Boolean = false
    var zebraEnabled: Boolean = false

    val isActive: Boolean get() = peakingEnabled || zebraEnabled

    /**
     * Two buffers, used alternately. The analysis thread fills one and hands it to the main
     * thread to copy into a bitmap; by the time it comes round again the other one has been
     * read, so neither is being written and read at once.
     */
    private var buffers: Array<IntArray> = emptyArray()
    private var bufferIndex = 0
    private var bufferWidth = 0
    private var bufferHeight = 0

    /**
     * Fills a mask in display orientation and hands it to [onMask], which is given the
     * pixels and the width and height they form. Nothing is emitted when both aids are off.
     */
    fun analyze(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        rotationDegrees: Int,
        mirrored: Boolean,
        onMask: (IntArray, Int, Int) -> Unit
    ) {
        if (!isActive) return
        val sampledWidth = width / SAMPLE_STEP
        val sampledHeight = height / SAMPLE_STEP
        if (sampledWidth <= 1 || sampledHeight <= 1) return

        val quarterTurns = ((rotationDegrees % 360) + 360) % 360 / 90
        val swapsAxes = quarterTurns == 1 || quarterTurns == 3
        val outWidth = if (swapsAxes) sampledHeight else sampledWidth
        val outHeight = if (swapsAxes) sampledWidth else sampledHeight

        val mask = bufferFor(outWidth, outHeight)
        mask.fill(0)

        val limit = luma.limit()
        var sy = 0
        while (sy < sampledHeight) {
            val row = sy * SAMPLE_STEP * rowStride
            val rowBelow = (sy + 1) * SAMPLE_STEP * rowStride
            var sx = 0
            while (sx < sampledWidth) {
                val index = row + sx * SAMPLE_STEP
                if (index >= limit) {
                    sx++
                    continue
                }
                val value = luma.get(index).toInt() and 0xFF

                var colour = 0
                if (zebraEnabled && value >= ZEBRA_THRESHOLD) {
                    // Diagonal stripes, the way a broadcast monitor draws them, so blown
                    // highlights are unmistakable against a plain white subject.
                    colour = if (((sx + sy) / STRIPE_WIDTH) % 2 == 0) ZEBRA_ON else ZEBRA_OFF
                }
                if (peakingEnabled && colour == 0) {
                    val right = sx + 1
                    val down = sy + 1
                    val nextX = row + right * SAMPLE_STEP
                    val nextY = rowBelow + sx * SAMPLE_STEP
                    if (right < sampledWidth && down < sampledHeight &&
                        nextX < limit && nextY < limit
                    ) {
                        val gradient = abs((luma.get(nextX).toInt() and 0xFF) - value) +
                            abs((luma.get(nextY).toInt() and 0xFF) - value)
                        if (gradient >= PEAK_THRESHOLD) colour = PEAK_COLOUR
                    }
                }

                if (colour != 0) {
                    var dx: Int
                    var dy: Int
                    when (quarterTurns) {
                        1 -> {
                            dx = sampledHeight - 1 - sy
                            dy = sx
                        }
                        2 -> {
                            dx = sampledWidth - 1 - sx
                            dy = sampledHeight - 1 - sy
                        }
                        3 -> {
                            dx = sy
                            dy = sampledWidth - 1 - sx
                        }
                        else -> {
                            dx = sx
                            dy = sy
                        }
                    }
                    if (mirrored) dx = outWidth - 1 - dx
                    val target = dy * outWidth + dx
                    if (target in mask.indices) mask[target] = colour
                }
                sx++
            }
            sy++
        }

        onMask(mask, outWidth, outHeight)
        bufferIndex = (bufferIndex + 1) % buffers.size
    }

    private fun bufferFor(width: Int, height: Int): IntArray {
        if (buffers.isEmpty() || bufferWidth != width || bufferHeight != height) {
            bufferWidth = width
            bufferHeight = height
            buffers = Array(BUFFER_COUNT) { IntArray(width * height) }
            bufferIndex = 0
        }
        return buffers[bufferIndex]
    }

    private companion object {
        /** Every eighth pixel: fine enough to show an edge, coarse enough to stay cheap. */
        const val SAMPLE_STEP = 8
        const val BUFFER_COUNT = 2

        /** Luma at or above this is on its way to being clipped. */
        const val ZEBRA_THRESHOLD = 248

        /** How sharp a change has to be before it counts as an edge in focus. */
        const val PEAK_THRESHOLD = 40

        /** Sampled cells per stripe. */
        const val STRIPE_WIDTH = 3

        const val ZEBRA_ON = 0xCCFFFFFF.toInt()
        const val ZEBRA_OFF = 0xAA000000.toInt()

        /** The accent, so peaking reads as an app overlay rather than part of the scene. */
        const val PEAK_COLOUR = 0xFF9A7CFF.toInt()
    }
}
