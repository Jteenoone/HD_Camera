package com.example.hd_camera.edit

import android.graphics.Path
import android.graphics.PointF

/**
 * One freehand stroke from the Markup tool. Points are stored in 0..1 of the image so the
 * stroke survives the preview being a different size from the file that gets written.
 */
data class MarkupStroke(
    val points: List<PointF>,
    val color: Int,
    /** Stroke width as a fraction of the image width. */
    val width: Float
) {

    fun scaledPath(imageWidth: Int, imageHeight: Int): Path {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = point.x * imageWidth
            val y = point.y * imageHeight
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }
}
