package com.example.hd_camera.ui.settings

import android.Manifest
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.hd_camera.R
import com.example.hd_camera.data.CaptureFormat
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.data.LocalePrefs
import com.example.hd_camera.data.PhotoResolution
import com.example.hd_camera.data.VideoProfile
import com.example.hd_camera.data.ViewfinderPrefs
import com.example.hd_camera.databinding.FragmentSettingsBinding
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.language.LanguageFragment
import com.example.hd_camera.ui.navigateBack
import com.example.hd_camera.ui.navigateTo
import com.example.hd_camera.ui.openExternalUrl
import com.google.android.material.materialswitch.MaterialSwitch

/** Screen 10 · Settings. Every row here changes how the next capture is made. */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var binding: FragmentSettingsBinding? = null

    /**
     * Both location permissions go in one request: from Android 12 the user may answer
     * "approximate", which grants the coarse one only, and a geotag from a coarse fix is
     * still a geotag.
     */
    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        ViewfinderPrefs.set(requireContext(), ViewfinderPrefs.KEY_GEOTAGGING, granted)
        binding?.switchGeotagging?.isChecked = granted
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSettingsBinding.bind(view).also { this.binding = it }
        binding.header.applySystemBarPadding(top = true)
        view.applySystemBarPadding(bottom = true)

        binding.btnBack.setOnClickListener { navigateBack() }

        bindSwitch(binding.switchGrid, ViewfinderPrefs.KEY_GRID)
        bindSwitch(binding.switchShutterSound, ViewfinderPrefs.KEY_SHUTTER_SOUND)
        bindSwitch(binding.switchVolumeShutter, ViewfinderPrefs.KEY_VOLUME_SHUTTER)

        // Geotagging needs a location fix, so the switch asks for the permission first.
        binding.switchGeotagging.isChecked =
            ViewfinderPrefs.get(requireContext(), ViewfinderPrefs.KEY_GEOTAGGING)
        binding.switchGeotagging.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestLocation.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                ViewfinderPrefs.set(requireContext(), ViewfinderPrefs.KEY_GEOTAGGING, false)
            }
        }

        binding.rowLanguage.setOnClickListener { navigateTo(LanguageFragment()) }
        binding.rowResolution.setOnClickListener { cycleResolution() }
        binding.rowFormat.setOnClickListener { cycleFormat() }
        binding.rowVideo.setOnClickListener { cycleVideoProfile() }

        // Both documents live on the web; the app links to them rather than shipping a copy.
        binding.rowPrivacy.setOnClickListener { openExternalUrl(R.string.url_privacy_policy) }
        binding.rowTerms.setOnClickListener { openExternalUrl(R.string.url_terms_conditions) }

        refreshValues()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the language picker, this row is the only thing that changed.
        refreshValues()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun bindSwitch(switch: MaterialSwitch, key: String) {
        switch.isChecked = ViewfinderPrefs.get(requireContext(), key)
        switch.setOnCheckedChangeListener { _, isChecked ->
            ViewfinderPrefs.set(requireContext(), key, isChecked)
        }
    }

    private fun cycleResolution() {
        val values = PhotoResolution.entries
        val current = CaptureSettings.photoResolution(requireContext())
        CaptureSettings.setPhotoResolution(
            requireContext(),
            values[(current.ordinal + 1) % values.size]
        )
        refreshValues()
    }

    private fun cycleFormat() {
        val values = CaptureFormat.entries
        val current = CaptureSettings.format(requireContext())
        CaptureSettings.setFormat(requireContext(), values[(current.ordinal + 1) % values.size])
        refreshValues()
    }

    private fun cycleVideoProfile() {
        val values = VideoProfile.entries
        val current = CaptureSettings.videoProfile(requireContext())
        CaptureSettings.setVideoProfile(
            requireContext(),
            values[(current.ordinal + 1) % values.size]
        )
        refreshValues()
    }

    private fun refreshValues() {
        val binding = binding ?: return
        val context = requireContext()

        binding.tvLanguageValue.setText(LocalePrefs.current(context).displayName)

        val resolution = CaptureSettings.photoResolution(context)
        binding.tvResolutionValue.setText(resolution.label)
        binding.tvResolutionSub.text = resolution.target?.let { size ->
            val megapixels = (size.width.toLong() * size.height) / 1_000_000f
            getString(R.string.resolution_sub, Math.round(megapixels), size.width, size.height)
        } ?: getString(R.string.resolution_sub_sensor)

        binding.tvFormatValue.setText(CaptureSettings.format(context).label)
        binding.tvVideoValue.setText(CaptureSettings.videoProfile(context).label)
    }
}
