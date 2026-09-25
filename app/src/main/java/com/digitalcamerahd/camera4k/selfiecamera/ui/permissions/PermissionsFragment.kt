package com.digitalcamerahd.camera4k.selfiecamera.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.digitalcamerahd.camera4k.selfiecamera.MainActivity
import com.digitalcamerahd.camera4k.selfiecamera.R
import com.digitalcamerahd.camera4k.selfiecamera.data.ViewfinderPrefs
import com.digitalcamerahd.camera4k.selfiecamera.databinding.FragmentPermissionsBinding
import com.digitalcamerahd.camera4k.selfiecamera.ui.applySystemBarPadding
import com.digitalcamerahd.camera4k.selfiecamera.ui.home.HomeFragment
import com.digitalcamerahd.camera4k.selfiecamera.ui.navigateToRoot
import com.digitalcamerahd.camera4k.selfiecamera.ui.openExternalUrl

/**
 * Screen 04 · the permission hand-off before the viewfinder opens.
 *
 * Onboarding asks for the camera and nothing else. The microphone is a recording-time
 * decision, so the Video screen asks for it when it is actually needed; and from Android 10
 * the app reaches DCIM/HDCamera through scoped storage, which needs no permission at all
 * for the files it wrote itself.
 */
class PermissionsFragment : Fragment(R.layout.fragment_permissions) {

    private var binding: FragmentPermissionsBinding? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStatuses()
        // Whatever the answer, the user gets to the viewfinder; it shows its own message
        // when the camera cannot be opened.
        finishOnboarding()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPermissionsBinding.bind(view).also { this.binding = it }
        binding.permissionsRoot.applySystemBarPadding(top = true, bottom = true)

        // Legacy storage is the only extra permission, and only below Android 10.
        binding.rowStorage.visibility = if (legacyStorage()) View.VISIBLE else View.GONE

        binding.btnAllow.setOnClickListener {
            if (allGranted()) finishOnboarding() else requestPermissions.launch(required())
        }
        binding.btnNotNow.setOnClickListener { finishOnboarding() }

        binding.linkPrivacy.setOnClickListener { openExternalUrl(R.string.url_privacy_policy) }
        binding.linkTerms.setOnClickListener { openExternalUrl(R.string.url_terms_conditions) }
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
        setStatus(binding.tvStatusCamera, isGranted(Manifest.permission.CAMERA))
        if (legacyStorage()) {
            setStatus(binding.tvStatusStorage, storageGranted())
        }
    }

    private fun setStatus(view: TextView, granted: Boolean) {
        view.setText(if (granted) R.string.perm_granted else R.string.perm_required)
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (granted) R.color.dc_accent else R.color.dc_text_faint
            )
        )
    }

    /**
     * Onboarding ends on the dashboard, not in a viewfinder. Someone who chose "Not now"
     * here is not dropped straight into a camera that cannot open; Home asks again at the
     * moment a camera is actually wanted.
     */
    private fun finishOnboarding() {
        ViewfinderPrefs.set(requireContext(), MainActivity.KEY_ONBOARDED, true)
        navigateToRoot(HomeFragment())
    }

    /** Android 9 and below cannot write to DCIM without the storage permissions. */
    private fun legacyStorage(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    private fun storageGranted(): Boolean =
        storagePermissions().all(::isGranted)

    private fun allGranted(): Boolean = required().all(::isGranted)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun storagePermissions(): Array<String> = if (legacyStorage()) {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    } else {
        emptyArray()
    }

    private fun required(): Array<String> =
        arrayOf(Manifest.permission.CAMERA) + storagePermissions()
}
