package com.example.hd_camera.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.hd_camera.data.CaptureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Turns a recording into slow motion by stretching the presentation timestamps.
 *
 * Nothing is re-encoded: the compressed frames are copied across and only their timing
 * changes. The stretch is worked out from the clip's *measured* frame rate rather than the
 * rate that was requested, because a camera that quietly recorded at 24 fps would otherwise
 * be slowed to a slideshow. Audio is dropped, since slowed audio without resampling is noise.
 */
object SpeedRemuxer {

    private const val BUFFER_BYTES = 1 shl 20

    data class Result(
        val uri: Uri?,
        /** Frames per second the source actually holds. */
        val sourceFps: Float,
        /** How much longer the result runs; 1 means nothing was worth slowing. */
        val factor: Float
    )

    /**
     * @param targetPlaybackFps the rate the result should play at. Slowing a 60 fps clip to
     * 30 gives half speed; a clip already at 30 is left alone.
     */
    suspend fun slowDown(
        context: Context,
        source: Uri,
        targetPlaybackFps: Float = 30f
    ): Result = withContext(Dispatchers.IO) {
        val measured = measureFps(context, source)
        if (measured <= 0f) return@withContext Result(null, 0f, 1f)

        val factor = (measured / targetPlaybackFps)
        if (factor <= MINIMUM_USEFUL_FACTOR) {
            // Not enough headroom to slow anything down; keep the original as it is.
            return@withContext Result(source, measured, 1f)
        }

        val uri = retime(context, source, factor)
        Result(uri, measured, factor)
    }

    /** Counts samples against the track duration — the only reliable read of the real rate. */
    private fun measureFps(context: Context, source: Uri): Float {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, source, null)
            val track = videoTrackOf(extractor) ?: return 0f
            extractor.selectTrack(track)

            val durationUs = extractor.getTrackFormat(track)
                .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?: 0L

            var frames = 0
            var lastTime = 0L
            while (extractor.sampleTime >= 0) {
                frames++
                lastTime = extractor.sampleTime
                extractor.advance()
            }

            val spanUs = if (durationUs > 0) durationUs else lastTime
            if (frames < 2 || spanUs <= 0) 0f else frames * 1_000_000f / spanUs
        } catch (error: Exception) {
            error.printStackTrace()
            0f
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun retime(context: Context, source: Uri, factor: Float): Uri? {
        val extractor = MediaExtractor()
        val temporary = File.createTempFile("slowmo", ".mp4", context.cacheDir)
        var muxer: MediaMuxer? = null

        return try {
            extractor.setDataSource(context, source, null)
            val track = videoTrackOf(extractor) ?: return null
            extractor.selectTrack(track)

            muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()

            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (extractor.sampleTime * factor).toLong()
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }

            muxer.stop()
            publish(context, temporary)
        } catch (error: Exception) {
            error.printStackTrace()
            null
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            temporary.delete()
        }
    }

    private fun videoTrackOf(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("video/") == true
        }

    /** Copies the finished file into DCIM/HDCamera and returns its uri. */
    private fun publish(context: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, MediaOutput.fileName("SLO") + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, CaptureSettings.RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        resolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    /** Removes the straight-speed original once the slow version is written. */
    fun discard(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Below this there is nothing to gain, so the clip is left alone. */
    private const val MINIMUM_USEFUL_FACTOR = 1.2f
}
