package com.digitalcamerahd.camera4k.selfiecamera.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.digitalcamerahd.camera4k.selfiecamera.R
import com.digitalcamerahd.camera4k.selfiecamera.databinding.FragmentHomeBinding
import com.digitalcamerahd.camera4k.selfiecamera.ui.applySystemBarPadding
import com.digitalcamerahd.camera4k.selfiecamera.ui.camera.PhotoFragment
import com.digitalcamerahd.camera4k.selfiecamera.ui.edit.EditFragment
import com.digitalcamerahd.camera4k.selfiecamera.ui.gallery.GalleryFragment
import com.digitalcamerahd.camera4k.selfiecamera.ui.navigateTo
import com.digitalcamerahd.camera4k.selfiecamera.ui.settings.SettingsFragment

/**
 * Home · where the app opens once onboarding is done.
 *
 * It holds no camera. Opening one is a navigation decision, and the camera permission is
 * asked for at that moment rather than on the way in — someone who only wants the gallery
 * or the editor is never asked for a camera they are not going to use.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null

    /** Where to go once the camera permission comes back granted. */
    private var pendingDestination: (() -> Fragment)? = null

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val destination = pendingDestination
        pendingDestination = null
        if (granted && destination != null) {
            navigateTo(destination())
        } else {
            showPermissionNotice()
        }
    }

    /**
     * The system photo picker. It hands back one image without the app holding any media
     * permission at all, and a cancelled pick simply returns nothing.
     */
    private val pickPhoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) navigateTo(EditFragment.of(uri)) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHomeBinding.bind(view).also { this.binding = it }
        binding.header.applySystemBarPadding(top = true)
        binding.navRow.applySystemBarPadding(bottom = true)

        binding.btnSettings.setOnClickListener { navigateTo(SettingsFragment()) }
        binding.cardHero.setOnClickListener { openCamera { PhotoFragment() } }
        binding.btnOpenCamera.setOnClickListener { openCamera { PhotoFragment() } }
        binding.cardEdit.setOnClickListener { pickPhotoToEdit() }
        binding.cardGallery.setOnClickListener { navigateTo(GalleryFragment()) }

        // Collage has no screen behind it yet. Rather than a card that goes nowhere, it
        // says so and does not respond — it still reads, so the plan is visible.
        binding.cardCollage.alpha = DISABLED_ALPHA

        // This is the Home tab, so tapping it again just returns to the top.
        binding.navHome.setOnClickListener {
            binding.homeScroll.smoothScrollTo(0, 0)
        }
        binding.btnNavCamera.setOnClickListener { openCamera { PhotoFragment() } }
        binding.navMyCreative.setOnClickListener { navigateTo(GalleryFragment()) }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings the permission may have been granted.
        if (cameraGranted()) binding?.tvPermissionNotice?.visibility = View.GONE
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // ── Camera permission ──────────────────────────────────────────────────

    /**
     * Every camera screen goes through here. The permission is requested on the first
     * attempt; a refusal leaves a line on the dashboard explaining what is missing rather
     * than dropping the user into a viewfinder that cannot open.
     */
    private fun openCamera(destination: () -> Fragment) {
        if (cameraGranted()) {
            binding?.tvPermissionNotice?.visibility = View.GONE
            navigateTo(destination())
            return
        }
        pendingDestination = destination
        requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun cameraGranted(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Once the user has answered "don't ask again" the system dialog stops appearing, so
     * the notice has to offer the only route left: the app's own settings page.
     */
    private fun showPermissionNotice() {
        val notice = binding?.tvPermissionNotice ?: return
        val permanentlyDenied =
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

        notice.visibility = View.VISIBLE
        if (permanentlyDenied) {
            notice.text = getString(R.string.camera_permission_in_settings)
            notice.setOnClickListener { openAppSettings() }
            notice.isClickable = true
        } else {
            notice.setText(R.string.camera_permission_needed)
            notice.setOnClickListener(null)
            notice.isClickable = false
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ("package:" + requireContext().packageName).toUri()
        )
        // A device with nothing to answer this is not worth a crash.
        runCatching { startActivity(intent) }
    }

    // ── Editor ─────────────────────────────────────────────────────────────

    private fun pickPhotoToEdit() {
        pickPhoto.launch(
            PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                .build()
        )
    }

    private companion object {
        /** The same weight the viewfinder uses for a control it cannot honour. */
        const val DISABLED_ALPHA = 0.4f
    }
}
