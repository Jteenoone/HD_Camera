package com.digitalcamerahd.camera4k.selfiecamera.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Adds the system bar insets on top of whatever padding the layout already declares.
 * The viewfinder image stays edge to edge; only the control bars move out of the way.
 */
fun View.applySystemBarPadding(top: Boolean = false, bottom: Boolean = false) {
    val basePaddingTop = paddingTop
    val basePaddingBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(
            top = if (top) basePaddingTop + bars.top else basePaddingTop,
            bottom = if (bottom) basePaddingBottom + bars.bottom else basePaddingBottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
