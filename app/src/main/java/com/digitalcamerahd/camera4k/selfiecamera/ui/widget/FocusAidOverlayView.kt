package com.digitalcamerahd.camera4k.selfiecamera.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.createBitmap

/**
 * Draws the focus-peaking and zebra mask over the Pro viewfinder.
 *
 * [FocusAid][com.digitalcamerahd.camera4k.selfiecamera.camera.FocusAid] hands over a small mask already in
 * display orientation; this stretches it across the preview. Nearest-neighbour on purpose:
 * smoothing turns crisp peaking dots into a lilac haze and softens the zebra edges that are
 * the whole point of the stripes.
 */
class FocusAidOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply { isFilterBitmap = false }
    private val destination = Rect()
    private var mask: Bitmap? = null

    /** [pixels] is ARGB, [width] by [height], and is copied before this returns. */
    fun submit(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        val target = mask?.takeIf { it.width == width && it.height == height }
            ?: createBitmap(width, height).also { mask = it }
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        invalidate()
    }

    /** Takes the overlay away when both aids are switched off. */
    fun clear() {
        if (mask == null) return
        mask = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = mask ?: return
        destination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}
