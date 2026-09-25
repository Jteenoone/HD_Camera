package com.digitalcamerahd.camera4k.selfiecamera.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.video.MediaStoreOutputOptions
import com.digitalcamerahd.camera4k.selfiecamera.data.CaptureSettings
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the app writes lands in DCIM/ProCam4K through MediaStore. */
object MediaOutput {

    private const val NAME_PATTERN = "yyyyMMdd_HHmmss"

    fun fileName(prefix: String): String =
        prefix + "_" + SimpleDateFormat(NAME_PATTERN, Locale.US).format(Date())

    /** Direct capture path: CameraX writes the JPEG itself, EXIF and all. */
    fun photoOptions(
        context: Context,
        location: Location?,
        baseName: String = fileName("IMG")
    ): ImageCapture.OutputFileOptions =
        imageOptions(context, location, baseName + ".jpg", "image/jpeg")

    /**
     * The DNG half of a RAW + JPEG capture. CameraX insists on a second set of options
     * when the use case is configured for both, and refuses the capture without it.
     */
    fun rawPhotoOptions(
        context: Context,
        location: Location?,
        baseName: String
    ): ImageCapture.OutputFileOptions =
        imageOptions(context, location, baseName + ".dng", "image/x-adobe-dng")

    private fun imageOptions(
        context: Context,
        location: Location?,
        displayName: String,
        mimeType: String
    ): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, CaptureSettings.RELATIVE_PATH)
            }
        }
        val metadata = ImageCapture.Metadata().apply {
            this.location = location
        }
        return ImageCapture.OutputFileOptions
            .Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
            .setMetadata(metadata)
            .build()
    }

    fun videoOptions(context: Context): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName("VID") + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, CaptureSettings.RELATIVE_PATH)
            }
        }
        return MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }

    /**
     * Processed capture path: used when a filter or the beauty pass has to be burnt in,
     * which means the bitmap passes through the app before it is written.
     */
    fun writeJpeg(
        context: Context,
        bitmap: Bitmap,
        displayName: String = fileName("IMG") + ".jpg",
        quality: Int = 95
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, CaptureSettings.RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { stream: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    /** Decodes a captured frame and rotates it upright. */
    fun ImageProxy.toUprightBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return decoded
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }
}
