package com.example.hd_camera.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.hd_camera.R
import com.example.hd_camera.databinding.ItemOptionBinding
import com.example.hd_camera.databinding.ViewOptionPopupBinding

/**
 * The small chooser that drops out from under a viewfinder button — flash and the self-timer
 * both offer a handful of settings, which reads better as a list than as a button you have
 * to tap repeatedly to cycle.
 */
object OptionsPopup {

    fun show(
        anchor: View,
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
    ) {
        val inflater = LayoutInflater.from(anchor.context)
        val binding = ViewOptionPopupBinding.inflate(inflater)

        // Inflating without a parent leaves the root without layout params, and a PopupWindow
        // then measures it as nothing at all and shows an empty window.
        binding.root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val window = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f * anchor.resources.displayMetrics.density
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        options.forEachIndexed { index, label ->
            val row = ItemOptionBinding.inflate(inflater, binding.optionList, false)
            val isSelected = index == selectedIndex
            row.root.text = label
            row.root.setTextColor(
                ContextCompat.getColor(
                    anchor.context,
                    if (isSelected) R.color.dc_accent else R.color.dc_text
                )
            )
            row.root.typeface = ResourcesCompat.getFont(
                anchor.context,
                if (isSelected) R.font.ibm_plex_sans_semibold else R.font.ibm_plex_sans_regular
            )
            row.root.setOnClickListener {
                window.dismiss()
                onPick(index)
            }
            binding.optionList.addView(row.root)
        }

        val offset = (4 * anchor.resources.displayMetrics.density).toInt()
        window.showAsDropDown(anchor, 0, offset, Gravity.START)
    }
}
