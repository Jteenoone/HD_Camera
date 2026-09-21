package com.example.hd_camera.media

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes a sequence of stills into an H.264 MP4 — the back end of the Time-lapse mode.
 *
 * Frames go in through the codec's flexible YUV input image, which keeps the plane strides
 * correct on both planar and semi-planar encoders.
 */
class TimeLapseEncoder(
    requestedWidth: Int,
    requestedHeight: Int,
    private val frameRate: Int = 30,
    private val bitRate: Int = 12_000_000
) {

    // H.264 wants even dimensions.
    private val width = requestedWidth / 2 * 2
    private val height = requestedHeight / 2 * 2

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var frameIndex = 0L

    private val bufferInfo = MediaCodec.BufferInfo()
    private var scratch: Bitmap? = null

    val frameCount: Int get() = frameIndex.toInt()

    fun start(output: File) {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Adds one captured still as the next frame of the clip. */
    fun encode(source: Bitmap) {
        val codec = codec ?: return
        val frame = fit(source)

        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val image = codec.getInputImage(inputIndex)
            if (image != null) {
                writeYuv(frame, image)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    width * height * 3 / 2,
                    frameIndex * 1_000_000L / frameRate,
                    0
                )
                frameIndex++
            } else {
                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
            }
        }
        drain(endOfStream = false)
    }

    /** Flushes the codec and closes the container. Returns false when nothing was written. */
    fun finish(): Boolean {
        val codec = codec ?: return false
        return try {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex, 0, 0,
                    frameIndex * 1_000_000L / frameRate,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain(endOfStream = true)
            muxerStarted && frameIndex > 0
        } catch (error: Exception) {
            error.printStackTrace()
            false
        } finally {
            release()
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        if (muxerStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        scratch?.recycle()
        scratch = null
    }

    private fun drain(endOfStream: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Scales and centre-crops the still onto the clip's frame size. */
    private fun fit(source: Bitmap): Bitmap {
        if (source.width == width && source.height == height) return source
        val target = scratch ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also { scratch = it }

        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaledWidth = source.width * scale
        val scaledHeight = source.height * scale
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f

        android.graphics.Canvas(target).drawBitmap(
            source,
            null,
            android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        )
        return target
    }

    /** ARGB to YUV 4:2:0, written through the codec's own plane strides. */
    private fun writeYuv(bitmap: Bitmap, image: android.media.Image) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        yBuffer.clear(); uBuffer.clear(); vBuffer.clear()

        for (row in 0 until height) {
            for (column in 0 until width) {
                val pixel = pixels[row * width + column]
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF

                val y = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                yBuffer.put(row * yPlane.rowStride + column * yPlane.pixelStride, y.clampToByte())

                if (row % 2 == 0 && column % 2 == 0) {
                    val u = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                    val v = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                    val offset = (row / 2) * uPlane.rowStride + (column / 2) * uPlane.pixelStride
                    uBuffer.put(offset, u.clampToByte())
                    vBuffer.put((row / 2) * vPlane.rowStride + (column / 2) * vPlane.pixelStride, v.clampToByte())
                }
            }
        }
    }

    private fun Int.clampToByte(): Byte = coerceIn(0, 255).toByte()

    private companion object {
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val TIMEOUT_US = 10_000L
    }
}
