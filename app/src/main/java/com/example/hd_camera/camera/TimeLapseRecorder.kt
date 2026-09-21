package com.example.hd_camera.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.media.MediaOutput
import com.example.hd_camera.media.TimeLapseEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Time-lapse: one preview frame a second, encoded back to back at 30 fps, so an hour of
 * shooting plays back in two minutes.
 *
 * Frames come from the analysis stream rather than from full captures. A full capture on a
 * modern sensor takes about as long as the interval itself and, with RAW turned on, hands
 * back a DNG that cannot be decoded into a frame at all — which is why nothing was recorded
 * before.
 */
class TimeLapseRecorder(
    private val context: Context
) {

    private var encoder: TimeLapseEncoder? = null
    private var file: File? = null

    private val lock = Any()
    private var latest: Bitmap? = null

    var running: Boolean = false
        private set

    val frameCount: Int get() = encoder?.frameCount ?: 0

    fun start() {
        if (running) return
        running = true
        encoder = null
        file = null
    }

    /** Called from the analysis thread for every preview frame; only the newest is kept. */
    fun offer(frame: Bitmap) {
        synchronized(lock) {
            if (!running) {
                frame.recycle()
                return
            }
            latest?.recycle()
            latest = frame
        }
    }

    private fun takeLatest(): Bitmap? = synchronized(lock) { latest?.also { latest = null } }

    /** Encodes whatever the preview last produced. The first frame fixes the clip size. */
    suspend fun captureFrame(): Boolean {
        if (!running) return false
        val bitmap = takeLatest() ?: return false

        return withContext(Dispatchers.Default) {
            try {
                if (encoder == null) {
                    val longEdge = FRAME_LONG_EDGE
                    val shortEdge = FRAME_SHORT_EDGE
                    val portrait = bitmap.height >= bitmap.width
                    val target = File.createTempFile("timelapse", ".mp4", context.cacheDir)
                    encoder = TimeLapseEncoder(
                        requestedWidth = if (portrait) shortEdge else longEdge,
                        requestedHeight = if (portrait) longEdge else shortEdge,
                        frameRate = PLAYBACK_FPS
                    ).also { it.start(target) }
                    file = target
                }
                encoder?.encode(bitmap)
                true
            } catch (error: Exception) {
                error.printStackTrace()
                false
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Closes the clip and publishes it. Returns null when no frame was captured. */
    suspend fun finish(): Uri? = withContext(Dispatchers.IO) {
        running = false
        val encoder = encoder ?: return@withContext null
        val file = file ?: return@withContext null

        val wrote = encoder.finish()
        this@TimeLapseRecorder.encoder = null
        this@TimeLapseRecorder.file = null

        val uri = if (wrote) publish(file) else null
        file.delete()
        uri
    }

    fun cancel() {
        running = false
        synchronized(lock) {
            latest?.recycle()
            latest = null
        }
        encoder?.release()
        encoder = null
        file?.delete()
        file = null
    }

    private fun publish(source: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, MediaOutput.fileName("TL") + ".mp4")
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
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            MediaScannerConnection.scanFile(context, arrayOf(source.absolutePath), null, null)
        }
        return uri
    }

    companion object {
        /** One frame a second, played back at 30 fps. */
        const val DEFAULT_INTERVAL_MILLIS = 1_000L
        private const val PLAYBACK_FPS = 30
        private const val FRAME_LONG_EDGE = 1280
        private const val FRAME_SHORT_EDGE = 720
    }
}
