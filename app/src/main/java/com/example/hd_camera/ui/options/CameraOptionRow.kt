package com.example.hd_camera.ui.options

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.hd_camera.databinding.ItemCameraOptionBinding

/**
 * Draws a [CameraOption] into the shared row. Both choosers go through here, so a setting
 * looks and reads the same whether it dropped out of a viewfinder button or came up from
 * the bottom of the screen.
 */
object CameraOptionRow {

    fun inflate(
        inflater: LayoutInflater,
        parent: ViewGroup,
        option: CameraOption,
        onPick: (CameraOption) -> Unit
    ): View {
        val binding = ItemCameraOptionBinding.inflate(inflater, parent, false)
        binding.tvOptionTitle.text = option.title

        val detail = option.detail
        binding.tvOptionDetail.text = detail
        binding.tvOptionDetail.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE

        binding.ivOptionCheck.visibility =
            if (option.selected) View.VISIBLE else View.INVISIBLE

        // isSelected and isEnabled are what a screen reader announces; the alpha is for
        // everyone else. An option the camera cannot deliver stays on screen with its
        // reason rather than disappearing, so the list does not change shape per device.
        binding.root.isSelected = option.selected
        binding.root.isEnabled = option.enabled
        binding.root.alpha = if (option.enabled) 1f else DISABLED_ALPHA
        binding.root.isClickable = option.enabled
        if (option.enabled) binding.root.setOnClickListener { onPick(option) }

        return binding.root
    }

    /** Matches the disabled chips on the viewfinder. */
    private const val DISABLED_ALPHA = 0.4f
}
