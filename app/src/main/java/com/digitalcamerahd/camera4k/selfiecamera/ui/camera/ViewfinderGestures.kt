package com.digitalcamerahd.camera4k.selfiecamera.ui.camera

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Pinch and tap on a viewfinder.
 *
 * The two have to be told apart: the last finger of a pinch lifting arrives as a tap, which
 * sent the camera off to focus on wherever the gesture happened to end. A pinch therefore
 * claims the whole touch sequence, and only a sequence that never became one is a tap.
 */
class ViewfinderGestures(
    view: View,
    private val onZoomBegin: () -> Unit = {},
    private val onZoom: (Float) -> Unit,
    private val onZoomEnd: () -> Unit = {},
    private val onTap: ((Float, Float) -> Unit)? = null
) {

    /** True from the moment a pinch starts until every finger has left the screen. */
    private var pinching = false

    private val scaleDetector = ScaleGestureDetector(
        view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                onZoomBegin()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onZoom(detector.scaleFactor)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onZoomEnd()
            }
        }
    ).apply {
        // Quick scale is a double tap followed by a drag, which is close enough to a
        // double tap on the preview to steal taps meant for focus.
        isQuickScaleEnabled = false
    }

    private val tapDetector = GestureDetector(
        view.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                val tap = onTap ?: return false
                tap(event.x, event.y)
                return true
            }
        }
    )

    init {
        attachTo(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTo(view: View) {
        view.setOnTouchListener { touched, event ->
            // A finger going down on an empty screen starts a fresh gesture: whatever the
            // last one turned into, this one is a tap until a second finger says otherwise.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
            scaleDetector.onTouchEvent(event)
            if (!pinching) {
                tapDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) touched.performClick()
            }
            true
        }
    }
}
