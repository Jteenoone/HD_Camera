package com.digitalcamerahd.camera4k.selfiecamera.ui.camera

import android.view.View

/** The shutter has no destination in the design, so it answers with a press animation. */
fun View.playShutterFeedback() {
    animate().cancel()
    scaleX = 1f
    scaleY = 1f
    animate()
        .scaleX(0.88f)
        .scaleY(0.88f)
        .setDuration(80)
        .withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        .start()
}
