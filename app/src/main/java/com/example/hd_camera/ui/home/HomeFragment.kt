package com.example.hd_camera.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.example.hd_camera.R
import com.example.hd_camera.databinding.FragmentHomeBinding
import com.example.hd_camera.databinding.ItemHomeActionBinding
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.camera.PhotoFragment
import com.example.hd_camera.ui.camera.ProFragment
import com.example.hd_camera.ui.camera.VideoFragment
import com.example.hd_camera.ui.edit.EditFragment
import com.example.hd_camera.ui.gallery.GalleryFragment
import com.example.hd_camera.ui.navigateTo
import com.example.hd_camera.ui.settings.SettingsFragment

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
        view.applySystemBarPadding(bottom = true)

        binding.btnSettings.setOnClickListener { navigateTo(SettingsFragment()) }
        binding.cardHero.setOnClickListener { openCamera { PhotoFragment() } }
        binding.btnOpenCamera.setOnClickListener { openCamera { PhotoFragment() } }
        binding.cardGallery.setOnClickListener { navigateTo(GalleryFragment()) }

        bindAction(
            binding.actionPro,
            R.string.action_pro_camera,
            R.drawable.ic_tune
        ) { openCamera { ProFragment() } }

        bindAction(
            binding.actionVideo,
            R.string.action_record_video,
            R.drawable.ic_video
        ) { openCamera { VideoFragment() } }

        bindAction(
            binding.actionEdit,
            R.string.action_edit_photo,
            R.drawable.ic_markup
        ) { pickPhotoToEdit() }

        // Collage has no screen behind it yet. Rather than a button that goes nowhere, it
        // says so and does not respond — the row still reads, so the plan is visible.
        bindAction(
            binding.actionCollage,
            R.string.action_collage,
            R.drawable.ic_collage,
            note = R.string.coming_soon,
            onClick = null
        )
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

    private fun bindAction(
        row: ItemHomeActionBinding,
        @StringRes title: Int,
        @DrawableRes icon: Int,
        @StringRes note: Int? = null,
        onClick: (() -> Unit)?
    ) {
        row.actionTitle.setText(title)
        row.actionIcon.setImageResource(icon)
        if (note != null) {
            row.actionNote.setText(note)
            row.actionNote.visibility = View.VISIBLE
        } else {
            row.actionNote.visibility = View.GONE
        }

        val enabled = onClick != null
        row.root.isEnabled = enabled
        row.root.isClickable = enabled
        row.root.alpha = if (enabled) 1f else DISABLED_ALPHA
        if (enabled) {
            row.root.setOnClickListener { onClick?.invoke() }
        } else {
            row.root.setOnClickListener(null)
        }
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
