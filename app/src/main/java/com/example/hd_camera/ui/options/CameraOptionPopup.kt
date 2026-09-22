package com.example.hd_camera.ui.options

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.annotation.StringRes
import androidx.core.graphics.drawable.toDrawable
import com.example.hd_camera.databinding.ViewCameraOptionPopupBinding

/**
 * The chooser that drops out from under a viewfinder button, for settings with only a
 * handful of entries: flash, the self-timer, the aspect ratio.
 *
 * Anything longer belongs in [CameraOptionBottomSheet], which can scroll and does not have
 * to fit under the button it came from.
 */
object CameraOptionPopup {

    fun show(
        anchor: View,
        @StringRes titleRes: Int,
        options: List<CameraOption>,
        onPick: (CameraOption) -> Unit
    ) {
        val inflater = LayoutInflater.from(anchor.context)
        val binding = ViewCameraOptionPopupBinding.inflate(inflater)

        // Inflating without a parent leaves the root without layout params, and a
        // PopupWindow then measures it as nothing at all and shows an empty window.
        binding.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        binding.tvOptionsTitle.setText(titleRes)

        val window = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = ELEVATION_DP * anchor.resources.displayMetrics.density
            isOutsideTouchable = true
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }

        options.forEach { option ->
            val row = CameraOptionRow.inflate(inflater, binding.optionList, option) { picked ->
                window.dismiss()
                onPick(picked)
            }
            binding.optionList.addView(row)
        }

        val offset = (OFFSET_DP * anchor.resources.displayMetrics.density).toInt()
        window.showAsDropDown(anchor, 0, offset, Gravity.START)
    }

    private const val ELEVATION_DP = 12f
    private const val OFFSET_DP = 4f
}
