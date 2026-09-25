package com.digitalcamerahd.camera4k.selfiecamera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.digitalcamerahd.camera4k.selfiecamera.data.CaptureFormat
import com.digitalcamerahd.camera4k.selfiecamera.data.CaptureSettings
import com.digitalcamerahd.camera4k.selfiecamera.data.ViewfinderPrefs
import com.digitalcamerahd.camera4k.selfiecamera.filters.PhotoFilter
import com.digitalcamerahd.camera4k.selfiecamera.filters.withColorMatrix
import com.digitalcamerahd.camera4k.selfiecamera.media.MediaOutput
import com.digitalcamerahd.camera4k.selfiecamera.media.MediaOutput.toUprightBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Takes the picture and puts it on disk.
 *
 * Two paths: when the frame has to be altered — a live filter, or the beauty pass — the app
 * decodes it, draws on it and writes the JPEG itself. Otherwise CameraX writes the file
 * directly, which keeps the full EXIF and the RAW sidecar.
 */
object PhotoCapture {

    sealed interface Result {
        data class Saved(val uri: Uri?) : Result
        data class Failed(val error: Throwable) : Result
    }

    suspend fun capture(
        context: Context,
        engine: CameraEngine,
        filter: PhotoFilter = PhotoFilter.NONE,
        strength: Float = 1f,
        smoothing: Float = 0f
    ): Result {
        // A chosen filter or beauty pass has to reach the file, so those always take the
        // processing path even when RAW is on — the DNG is dropped for that shot.
        val needsProcessing = filter != PhotoFilter.NONE || smoothing > 0f

        return if (needsProcessing) {
            captureProcessed(context, engine, filter, strength, smoothing)
        } else {
            captureDirect(context, engine)
        }
    }

    private suspend fun captureDirect(context: Context, engine: CameraEngine): Result =
        suspendCancellableCoroutine { continuation ->
            val location = lastKnownLocation(context)
            val baseName = MediaOutput.fileName("IMG")

            // A RAW + JPEG session reports each file separately, so only the first result
            // may resume the coroutine.
            val finished = AtomicBoolean(false)
            val callback = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (finished.compareAndSet(false, true)) {
                        continuation.resume(Result.Saved(output.savedUri))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (finished.compareAndSet(false, true)) {
                        continuation.resume(Result.Failed(exception))
                    }
                }
            }

            if (engine.rawCaptureActive) {
                engine.takePhoto(
                    MediaOutput.rawPhotoOptions(context, location, baseName),
                    MediaOutput.photoOptions(context, location, baseName),
                    callback
                )
            } else {
                engine.takePhoto(MediaOutput.photoOptions(context, location, baseName), callback)
            }
        }

    private suspend fun captureProcessed(
        context: Context,
        engine: CameraEngine,
        filter: PhotoFilter,
        strength: Float,
        smoothing: Float
    ): Result {
        val captured = suspendCancellableCoroutine<Bitmap?> { continuation ->
            engine.takePhoto(object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try {
                        image.toUprightBitmap()
                    } catch (error: Exception) {
                        null
                    } finally {
                        image.close()
                    }
                    continuation.resume(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resume(null)
                }
            })
        } ?: return Result.Failed(IllegalStateException("Capture returned no frame"))

        return withContext(Dispatchers.IO) {
            var bitmap: Bitmap = captured
            if (smoothing > 0f) {
                bitmap = softenSkin(bitmap, smoothing)
            }
            if (filter != PhotoFilter.NONE && strength > 0f) {
                bitmap = bitmap.withColorMatrix(filter.matrixAt(strength))
            }
            val uri = MediaOutput.writeJpeg(context, bitmap)
            if (uri != null) {
                Result.Saved(uri)
            } else {
                Result.Failed(IllegalStateException("MediaStore rejected the file"))
            }
        }
    }

    /**
     * Beauty without the vendor extension: a scaled blur laid back over the frame, which
     * softens skin while leaving edges readable.
     */
    private fun softenSkin(source: Bitmap, amount: Float): Bitmap {
        val factor = (1f - amount * 0.7f).coerceIn(0.3f, 1f)
        val small = Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true
        )
        val blurred = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(output).drawBitmap(
            blurred,
            0f,
            0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (amount * 170).toInt().coerceIn(0, 255)
            }
        )
        blurred.recycle()
        return output
    }

    /** Geotagging is off by default; when it is on we attach the last fix we are allowed to read. */
    private fun lastKnownLocation(context: Context): Location? {
        if (!ViewfinderPrefs.get(context, ViewfinderPrefs.KEY_GEOTAGGING)) return null
        // "Approximate location" grants the coarse permission only, and a coarse fix is
        // still worth writing into the EXIF.
        val granted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return try {
            manager.getProviders(true)
                .asSequence()
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        } catch (error: SecurityException) {
            null
        }
    }
}
