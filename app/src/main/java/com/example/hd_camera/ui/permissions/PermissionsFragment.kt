package com.example.hd_camera.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.hd_camera.MainActivity
import com.example.hd_camera.R
import com.example.hd_camera.data.ViewfinderPrefs
import com.example.hd_camera.databinding.FragmentPermissionsBinding
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.camera.PhotoFragment
import com.example.hd_camera.ui.navigateToRoot

/** Screen 04 · the permission hand-off before the viewfinder opens. */
class PermissionsFragment : Fragment(R.layout.fragment_permissions) {

    private var binding: FragmentPermissionsBinding? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStatuses()
        if (captureGranted() && mediaGranted()) openCamera()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPermissionsBinding.bind(view).also { this.binding = it }
        binding.permissionsRoot.applySystemBarPadding(top = true, bottom = true)

        binding.btnAllow.setOnClickListener {
            if (captureGranted() && mediaGranted()) {
                openCamera()
            } else {
                requestPermissions.launch(requiredPermissions())
            }
        }
        binding.btnNotNow.setOnClickListener { openCamera() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun refreshStatuses() {
        val binding = binding ?: return
        setStatus(binding.tvStatusCameraMic, captureGranted())
        setStatus(binding.tvStatusPhotos, mediaGranted())
    }

    private fun setStatus(view: android.widget.TextView, granted: Boolean) {
        view.setText(if (granted) R.string.perm_granted else R.string.perm_required)
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (granted) R.color.dc_accent else R.color.dc_text_faint
            )
        )
    }

    private fun openCamera() {
        ViewfinderPrefs.set(requireContext(), MainActivity.KEY_ONBOARDED, true)
        navigateToRoot(PhotoFragment())
    }

    private fun captureGranted(): Boolean =
        isGranted(Manifest.permission.CAMERA) && isGranted(Manifest.permission.RECORD_AUDIO)

    private fun mediaGranted(): Boolean = mediaPermissions().all(::isGranted)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun requiredPermissions(): Array<String> =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) + mediaPermissions()
}
