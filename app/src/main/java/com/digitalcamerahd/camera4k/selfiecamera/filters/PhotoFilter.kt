package com.digitalcamerahd.camera4k.selfiecamera.filters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import com.digitalcamerahd.camera4k.selfiecamera.R

/**
 * The five live filters of screen 07. Each one is a colour matrix, so the same definition
 * drives the preview (through a RenderEffect) and the saved file (through a Paint filter).
 */
enum class PhotoFilter(@StringRes val label: Int, @DrawableRes val preview: Int) {

    NONE(R.string.filter_none, R.drawable.original) {
        override fun matrix(): ColorMatrix = ColorMatrix()
    },

    VIVID(R.string.filter_vivid, R.drawable.vivid) {
        override fun matrix(): ColorMatrix = ColorMatrix().apply {
            setSaturation(1.55f)
            postConcat(contrast(1.12f))
        }
    },

    FADE(R.string.filter_fade, R.drawable.fade) {
        override fun matrix(): ColorMatrix = ColorMatrix().apply {
            setSaturation(0.78f)
            // Lift the blacks the way a faded film stock does.
            postConcat(ColorMatrix(floatArrayOf(
                0.92f, 0f, 0f, 0f, 18f,
                0f, 0.92f, 0f, 0f, 18f,
                0f, 0f, 0.92f, 0f, 20f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
    },

    BW(R.string.filter_bw, R.drawable.bw) {
        override fun matrix(): ColorMatrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(contrast(1.08f))
        }
    },

    WARM(R.string.filter_warm, R.drawable.warm) {
        override fun matrix(): ColorMatrix = ColorMatrix(floatArrayOf(
            1.10f, 0f, 0f, 0f, 6f,
            0f, 1.02f, 0f, 0f, 2f,
            0f, 0f, 0.88f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    };

    /** The filter at full strength. */
    protected abstract fun matrix(): ColorMatrix

    /** The filter blended with the identity matrix, so 0 is off and 1 is the full look. */
    fun matrixAt(strength: Float): ColorMatrix {
        val amount = strength.coerceIn(0f, 1f)
        if (this == NONE || amount == 0f) return ColorMatrix()
        val target = matrix().array
        val identity = ColorMatrix().array
        val blended = FloatArray(20) { i ->
            identity[i] + (target[i] - identity[i]) * amount
        }
        return ColorMatrix(blended)
    }

    companion object {

        private fun contrast(scale: Float): ColorMatrix {
            val translate = (1f - scale) * 128f
            return ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
        }
    }
}

/** Live preview filtering. Available from Android 12; older devices preview unfiltered. */
@RequiresApi(Build.VERSION_CODES.S)
fun ColorMatrix.asRenderEffect(): RenderEffect =
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(this))

val supportsLivePreviewFilter: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Applies [matrix] to a copy of this bitmap — used when writing the captured file. */
fun Bitmap.withColorMatrix(matrix: ColorMatrix): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matrix)
    }
    Canvas(output).drawBitmap(this, 0f, 0f, paint)
    return output
}
