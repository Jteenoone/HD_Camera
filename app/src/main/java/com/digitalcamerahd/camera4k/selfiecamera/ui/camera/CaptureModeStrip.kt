package com.digitalcamerahd.camera4k.selfiecamera.ui.camera

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.digitalcamerahd.camera4k.selfiecamera.R
import com.digitalcamerahd.camera4k.selfiecamera.databinding.ItemCaptureModeBinding

/** One entry of the capture-mode strip shared by the Photo and Video screens. */
data class CaptureMode(
    @StringRes val label: Int,
    val onClick: (() -> Unit)? = null
)

/**
 * Fills [this] with [modes], marking [activeIndex] the way the design does:
 * white semibold label with a coloured dot underneath.
 */
fun LinearLayout.bindCaptureModes(
    modes: List<CaptureMode>,
    activeIndex: Int,
    gapDp: Int,
    textSizeSp: Float,
    letterSpacing: Float,
    @DrawableRes dot: Int = R.drawable.bg_mode_dot_accent,
    onActiveChanged: ((Int) -> Unit)? = null
) {
    removeAllViews()
    val inflater = LayoutInflater.from(context)
    val gap = (gapDp * resources.displayMetrics.density).toInt()

    modes.forEachIndexed { index, mode ->
        val item = ItemCaptureModeBinding.inflate(inflater, this, false)
        item.modeLabel.setText(mode.label)
        item.modeLabel.textSize = textSizeSp
        item.modeLabel.letterSpacing = letterSpacing
        item.modeDot.setBackgroundResource(dot)

        // The unselected labels used to be faint grey in a regular weight, which the scrim
        // swallowed on a bright scene. Medium at 80% white still reads as "not the current
        // mode" next to the white semibold one, but stays legible.
        val isActive = index == activeIndex
        item.modeLabel.typeface = ResourcesCompat.getFont(
            context,
            if (isActive) R.font.ibm_plex_sans_semibold else R.font.ibm_plex_sans_medium
        )
        item.modeLabel.setTextColor(
            ContextCompat.getColor(
                context,
                if (isActive) R.color.dc_text else R.color.dc_text_80
            )
        )
        item.modeDot.visibility = if (isActive) View.VISIBLE else View.INVISIBLE

        item.root.setOnClickListener {
            val click = mode.onClick
            if (click != null) {
                click()
            } else if (!isActive) {
                bindCaptureModes(
                    modes, index, gapDp, textSizeSp, letterSpacing, dot, onActiveChanged
                )
                onActiveChanged?.invoke(index)
            }
        }

        (item.root.layoutParams as LinearLayout.LayoutParams).marginStart =
            if (index == 0) 0 else gap
        addView(item.root)
    }
}
