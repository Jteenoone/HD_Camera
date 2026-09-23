package com.example.hd_camera.ui.settings

import android.Manifest
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.hd_camera.BuildConfig
import com.example.hd_camera.R
import com.example.hd_camera.data.CaptureFormat
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.data.LocalePrefs
import com.example.hd_camera.data.PhotoResolution
import com.example.hd_camera.data.ThemePrefs
import com.example.hd_camera.data.VideoProfile
import com.example.hd_camera.data.ViewfinderPrefs
import com.example.hd_camera.databinding.FragmentSettingsBinding
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.language.LanguageFragment
import com.example.hd_camera.ui.navigateBack
import com.example.hd_camera.ui.navigateHome
import com.example.hd_camera.ui.navigateTo
import com.example.hd_camera.ui.openExternalUrl
import com.example.hd_camera.ui.options.CameraOption
import com.example.hd_camera.ui.options.CameraOptionBottomSheet
import com.example.hd_camera.ui.options.CaptureOptions
import com.google.android.material.materialswitch.MaterialSwitch

/** Screen 10 · Settings. Every row here changes how the next capture is made. */
class SettingsFragment :
    Fragment(R.layout.fragment_settings),
    CameraOptionBottomSheet.Host {

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
        binding.settingsScroll.applySystemBarPadding(bottom = true)

        binding.btnBack.setOnClickListener { navigateBack() }

        bindSwitch(binding.switchGrid, ViewfinderPrefs.KEY_GRID)
        bindSwitch(binding.switchShutterSound, ViewfinderPrefs.KEY_SHUTTER_SOUND)
        bindSwitch(binding.switchVolumeShutter, ViewfinderPrefs.KEY_VOLUME_SHUTTER)

        // AppCompat recreates the activity with the new theme; this screen comes back on top.
        binding.switchDarkMode.isChecked = ThemePrefs.isDark(requireContext())
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            ThemePrefs.setDark(requireContext(), isChecked)
        }

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
        binding.rowResolution.setOnClickListener {
            CameraOptionBottomSheet.show(
                this,
                CaptureOptions.KEY_PHOTO_RESOLUTION,
                R.string.photo_resolution
            )
        }
        binding.rowFormat.setOnClickListener {
            CameraOptionBottomSheet.show(
                this,
                CaptureOptions.KEY_CAPTURE_FORMAT,
                R.string.format
            )
        }
        binding.rowVideo.setOnClickListener {
            CameraOptionBottomSheet.show(
                this,
                CaptureOptions.KEY_VIDEO_PROFILE,
                R.string.video
            )
        }

        // Both documents live on the web; the app links to them rather than shipping a copy.
        binding.rowPrivacy.setOnClickListener { openExternalUrl(R.string.url_privacy_policy) }
        binding.rowTerms.setOnClickListener { openExternalUrl(R.string.url_terms_conditions) }

        // The one row that never changes while the screen is open.
        binding.tvVersionValue.text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)

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

    /**
     * This screen has no camera open, so the lists it offers come from the sensor's own
     * characteristics where that is possible — RAW — and are otherwise complete. The
     * viewfinder, which does have a session, narrows the video profiles further.
     */
    override fun cameraOptionsFor(requestKey: String): List<CameraOption> = when (requestKey) {
        CaptureOptions.KEY_PHOTO_RESOLUTION -> CaptureOptions.photoResolutions(requireContext())
        CaptureOptions.KEY_CAPTURE_FORMAT -> CaptureOptions.captureFormats(requireContext())
        CaptureOptions.KEY_VIDEO_PROFILE ->
            CaptureOptions.videoProfiles(requireContext(), engine = null)
        else -> emptyList()
    }

    override fun onCameraOptionPicked(requestKey: String, optionId: String) {
        val context = requireContext()
        when (requestKey) {
            CaptureOptions.KEY_PHOTO_RESOLUTION ->
                CaptureSettings.setPhotoResolution(context, PhotoResolution.of(optionId))
            CaptureOptions.KEY_CAPTURE_FORMAT ->
                CaptureSettings.setFormat(context, CaptureFormat.of(optionId))
            CaptureOptions.KEY_VIDEO_PROFILE ->
                CaptureSettings.setVideoProfile(context, VideoProfile.of(optionId))
        }
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
