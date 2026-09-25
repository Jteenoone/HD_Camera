package com.digitalcamerahd.camera4k.selfiecamera.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.digitalcamerahd.camera4k.selfiecamera.filters.withColorMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The adjustments behind the Edit screen. Every value is -100..100 with 0 meaning untouched,
 * which is how the pills present them.
 */
data class Adjustments(
    val exposure: Int = 0,
    val contrast: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val saturation: Int = 0,
    val warmth: Int = 0,
    val smoothing: Int = 0
) {

    val isIdentity: Boolean
        get() = exposure == 0 && contrast == 0 && highlights == 0 && shadows == 0 &&
            saturation == 0 && warmth == 0 && smoothing == 0

    /**
     * Highlights and shadows are tonal-range operations; a colour matrix can only approximate
     * them, so they are applied as a gain on the bright end and a lift on the dark end.
     */
    fun toColorMatrix(): ColorMatrix {
        val matrix = ColorMatrix()

        if (saturation != 0) {
            matrix.postConcat(ColorMatrix().apply { setSaturation(1f + saturation / 100f) })
        }

        val gain = 1f + exposure / 150f + highlights / 400f
        val lift = shadows * 0.6f + exposure * 0.15f
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    gain, 0f, 0f, 0f, lift,
                    0f, gain, 0f, 0f, lift,
                    0f, 0f, gain, 0f, lift,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        if (contrast != 0) {
            val scale = 1f + contrast / 100f
            val translate = (1f - scale) * 128f
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, translate,
                        0f, scale, 0f, 0f, translate,
                        0f, 0f, scale, 0f, translate,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        if (warmth != 0) {
            val warm = warmth / 100f
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f + warm * 0.18f, 0f, 0f, 0f, warm * 6f,
                        0f, 1f + warm * 0.03f, 0f, 0f, 0f,
                        0f, 0f, 1f - warm * 0.16f, 0f, -warm * 4f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        return matrix
    }
}

/** Geometry the Crop tool applies. */
data class Geometry(val rotationDegrees: Int = 0, val flipped: Boolean = false)

object ImageEditor {

    /** Decodes at most [maxEdge] pixels on the long side, honouring the EXIF orientation. */
    suspend fun load(context: Context, uri: Uri, maxEdge: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight), maxEdge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@withContext null

            val orientation = resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            applyExifOrientation(decoded, orientation)
        }

    private fun sampleSize(longestEdge: Int, maxEdge: Int): Int {
        var sample = 1
        while (longestEdge / sample > maxEdge) sample *= 2
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Renders the full edit: geometry, tone, smoothing, then any markup strokes. */
    suspend fun render(
        source: Bitmap,
        adjustments: Adjustments,
        geometry: Geometry,
        markup: List<MarkupStroke> = emptyList(),
        crop: RectF? = null
    ): Bitmap = withContext(Dispatchers.Default) {
        var bitmap = applyGeometry(source, geometry)
        if (crop != null) {
            bitmap = cropTo(bitmap, crop)
        }
        if (adjustments.smoothing > 0) {
            bitmap = smooth(bitmap, adjustments.smoothing / 100f)
        }
        if (!adjustments.isIdentity) {
            bitmap = bitmap.withColorMatrix(adjustments.toColorMatrix())
        }
        if (markup.isNotEmpty()) {
            bitmap = drawMarkup(bitmap, markup)
        }
        bitmap
    }

    /** [crop] is expressed in 0..1 of the bitmap, as the crop frame reports it. */
    fun cropTo(source: Bitmap, crop: RectF): Bitmap {
        val left = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (crop.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (crop.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        if (left == 0 && top == 0 && right == source.width && bottom == source.height) {
            return source
        }
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    fun applyGeometry(source: Bitmap, geometry: Geometry): Bitmap {
        if (geometry.rotationDegrees == 0 && !geometry.flipped) return source
        val matrix = Matrix().apply {
            if (geometry.flipped) postScale(-1f, 1f)
            postRotate(geometry.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Beauty smoothing: a downscale/upscale blur blended back over the original, which keeps
     * edges readable while softening skin texture.
     */
    private fun smooth(source: Bitmap, amount: Float): Bitmap {
        val factor = (1f - amount * 0.75f).coerceIn(0.25f, 1f)
        val small = Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true
        )
        val blurred = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (amount * 200).toInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        blurred.recycle()
        return output
    }

    private fun drawMarkup(source: Bitmap, strokes: List<MarkupStroke>): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke ->
            paint.color = stroke.color
            paint.strokeWidth = stroke.width * source.width
            canvas.drawPath(stroke.scaledPath(source.width, source.height), paint)
        }
        return output
    }
}
