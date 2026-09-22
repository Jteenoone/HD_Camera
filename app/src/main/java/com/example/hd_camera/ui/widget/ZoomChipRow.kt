package com.example.hd_camera.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/**
 * The row of quick-zoom chips, which also answers to a swipe up.
 *
 * The chips have to keep their own taps and long-presses, so the drag is caught here rather
 * than on each chip: the row lets a touch through to whichever chip it landed on, and only
 * takes it back once the finger has clearly travelled upward. A chip that has already
 * received the tap gets a cancel, which is exactly what a gesture being taken over means.
 */
class ZoomChipRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Raised once per gesture, when the finger has been dragged up off the row. */
    var onSwipeUp: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * SLOP_FACTOR
    private var downY = 0f
    private var claimed = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                claimed = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!claimed && downY - event.y > slop) {
                    claimed = true
                    onSwipeUp?.invoke()
                    // Taking the gesture stops the chip under the finger firing as a tap.
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Once the swipe has been recognised the rest of it belongs here, and does nothing:
        // the wheel it opened is what the finger carries on to.
        return claimed || super.onTouchEvent(event)
    }

    private companion object {
        /** A deliberate drag, not the wobble of a tap on a round chip. */
        const val SLOP_FACTOR = 2
    }
}
