package com.example.hd_camera.ui.options

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.example.hd_camera.R
import com.example.hd_camera.databinding.SheetCameraOptionsBinding
import com.example.hd_camera.ui.AlwaysDark
import com.example.hd_camera.ui.darkInflater
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The chooser for settings with more entries than fit under a button: the photo resolution,
 * the file format, the video profile.
 *
 * The options are not carried in the arguments. The screen that opened the sheet is asked
 * for them every time the sheet builds itself, so a sheet that survives a rotation comes
 * back showing what the camera can do *now* rather than what it could when it opened — and
 * nothing has to be made Parcelable to get there.
 */
class CameraOptionBottomSheet : BottomSheetDialogFragment() {

    /** Dark over a viewfinder, like everything else there; over Settings it follows the theme. */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return if (parentFragment is AlwaysDark) darkInflater(inflater) else inflater
    }

    /** Implemented by the fragment that shows the sheet. */
    interface Host {
        fun cameraOptionsFor(requestKey: String): List<CameraOption>
        fun onCameraOptionPicked(requestKey: String, optionId: String)
    }

    private var binding: SheetCameraOptionsBinding? = null

    private val requestKey: String
        get() = arguments?.getString(ARG_REQUEST_KEY).orEmpty()

    private val host: Host?
        get() = parentFragment as? Host

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SheetCameraOptionsBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return
        val titleRes = arguments?.getInt(ARG_TITLE, 0) ?: 0
        if (titleRes != 0) binding.tvSheetTitle.setText(titleRes)

        val host = host
        if (host == null) {
            // Nothing can be offered without the screen that owns the setting.
            dismissAllowingStateLoss()
            return
        }

        val inflater = LayoutInflater.from(binding.root.context)
        host.cameraOptionsFor(requestKey).forEach { option ->
            val row = CameraOptionRow.inflate(inflater, binding.optionList, option) { picked ->
                host.onCameraOptionPicked(requestKey, picked.id)
                dismiss()
            }
            binding.optionList.addView(row)
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_HD_BottomSheet

    companion object {
        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_TITLE = "title"

        /**
         * [host] has to implement [Host]; the sheet goes into its child fragment manager so
         * it can find its way back to it after a rotation.
         */
        fun show(host: Fragment, requestKey: String, @StringRes titleRes: Int) {
            if (host.childFragmentManager.findFragmentByTag(requestKey) != null) return
            CameraOptionBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_REQUEST_KEY, requestKey)
                    putInt(ARG_TITLE, titleRes)
                }
            }.show(host.childFragmentManager, requestKey)
        }
    }
}
