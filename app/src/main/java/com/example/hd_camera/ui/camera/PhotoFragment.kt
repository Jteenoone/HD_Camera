package com.example.hd_camera.ui.camera

import android.media.MediaActionSound
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.hd_camera.R
import com.example.hd_camera.camera.CameraEngine
import com.example.hd_camera.camera.PhotoCapture
import com.example.hd_camera.data.ViewfinderPrefs
import com.example.hd_camera.databinding.FragmentPhotoBinding
import com.example.hd_camera.media.MediaRepository
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.gallery.GalleryFragment
import com.example.hd_camera.ui.navigateTo
import com.example.hd_camera.ui.settings.SettingsFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screen 05 · the Photo viewfinder, running a live CameraX session. */
class PhotoFragment : Fragment(R.layout.fragment_photo), ShutterKeyHandler {

    private var binding: FragmentPhotoBinding? = null
    private var engine: CameraEngine? = null
    private var shutterSound: MediaActionSound? = null

    private var flashIndex = 0
    private var timerIndex = 0
    private var wideRatio = false
    private var hdrOn = false
    private var capturing = false
    private var activeModeIndex = 3

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPhotoBinding.bind(view).also { this.binding = it }
        binding.topBar.applySystemBarPadding(top = true)
        binding.bottomBar.applySystemBarPadding(bottom = true)

        val engine = CameraEngine(requireContext(), viewLifecycleOwner, binding.previewView)
            .also { this.engine = it }

        engine.onCameraError = { error ->
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.camera_unavailable)
            error.printStackTrace()
        }
        engine.onCameraReady = {
            binding.tvCameraStatus.visibility = View.GONE
            // resolutionInfo is only populated once the use case is attached to the session.
            binding.previewView.postDelayed({
                updateResolutionBadge()
                updateHdrChip()
            }, RESOLUTION_SETTLE_MS)
        }

        engine.start(CameraEngine.Mode.PHOTO)

        bindModeStrip()
        bindTopBar()
        bindZoom()
        bindShutterRow()
        bindTapToFocus()
    }

    override fun onResume() {
        super.onResume()
        // The Settings switches and the design's showGrid / showWatermark / showZoomChips
        // props drive the same state.
        val binding = binding ?: return
        val context = requireContext()
        binding.gridOverlay.visibility =
            visibility(ViewfinderPrefs.get(context, ViewfinderPrefs.KEY_GRID))
        binding.tvWatermark.visibility =
            visibility(ViewfinderPrefs.get(context, ViewfinderPrefs.KEY_WATERMARK))
        binding.zoomChips.visibility =
            visibility(ViewfinderPrefs.get(context, ViewfinderPrefs.KEY_ZOOM_CHIPS))
        loadLastShot()
    }

    override fun onDestroyView() {
        engine?.release()
        engine = null
        shutterSound?.release()
        shutterSound = null
        binding = null
        super.onDestroyView()
    }

    // ── Top bar ────────────────────────────────────────────────────────────

    private fun bindTopBar() {
        val binding = binding ?: return

        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnTimer.setOnClickListener { cycleTimer() }

        binding.btnRatio.setOnClickListener {
            wideRatio = !wideRatio
            binding.btnRatio.setText(if (wideRatio) R.string.ratio_16_9 else R.string.ratio_4_3)
            engine?.setAspectRatio(
                if (wideRatio) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
            )
            binding.previewView.postDelayed({ updateResolutionBadge() }, RESOLUTION_SETTLE_MS)
        }

        binding.btnHdr.setOnClickListener {
            val engine = engine ?: return@setOnClickListener
            hdrOn = !hdrOn
            engine.setExtensionMode(if (hdrOn) ExtensionMode.HDR else ExtensionMode.NONE)
            updateHdrChip()
        }

        binding.btnSettings.setOnClickListener { navigateTo(SettingsFragment()) }
        updateFlashIcon()
        updateTimerIcon()
    }

    private fun cycleFlash() {
        val engine = engine ?: return
        flashIndex = (flashIndex + 1) % FLASH_MODES.size
        val mode = FLASH_MODES[flashIndex]
        engine.setTorch(mode == TORCH)
        if (mode != TORCH) engine.setFlashMode(mode)
        updateFlashIcon()
    }

    private fun updateFlashIcon() {
        val binding = binding ?: return
        val tint = when (FLASH_MODES[flashIndex]) {
            ImageCapture.FLASH_MODE_OFF -> R.color.dc_text_faint
            ImageCapture.FLASH_MODE_AUTO -> R.color.dc_text
            else -> R.color.dc_amber
        }
        binding.btnFlash.setColorFilter(ContextCompat.getColor(requireContext(), tint))
        binding.btnFlash.contentDescription = getString(FLASH_LABELS[flashIndex])
    }

    private fun cycleTimer() {
        timerIndex = (timerIndex + 1) % TIMER_SECONDS.size
        updateTimerIcon()
    }

    private fun updateTimerIcon() {
        val binding = binding ?: return
        val seconds = TIMER_SECONDS[timerIndex]
        binding.btnTimer.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                if (seconds == 0) R.color.dc_text else R.color.dc_accent
            )
        )
    }

    private fun updateHdrChip() {
        val binding = binding ?: return
        val available = engine?.isModeSupported(ExtensionMode.HDR) == true
        binding.btnHdr.alpha = if (available) 1f else 0.4f
        binding.btnHdr.setBackgroundResource(
            if (hdrOn && available) R.drawable.bg_vf_icon_accent else R.drawable.bg_vf_icon
        )
        binding.btnHdr.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (hdrOn && available) R.color.dc_on_accent else R.color.dc_text
            )
        )
    }

    private fun updateResolutionBadge() {
        val binding = binding ?: return
        val size = engine?.imageCapture?.resolutionInfo?.resolution ?: return
        val megapixels = (size.width.toLong() * size.height) / 1_000_000f
        binding.tvResolutionBadge.text =
            getString(R.string.megapixels, Math.round(megapixels))
    }

    // ── Zoom, focus ────────────────────────────────────────────────────────

    private fun bindZoom() {
        val binding = binding ?: return
        val chips = listOf(
            binding.btnZoomHalf to 0.5f,
            binding.btnZoom1x to 1f,
            binding.btnZoom3x to 3f
        )
        chips.forEach { (chip, ratio) ->
            chip.setOnClickListener {
                engine?.setZoomRatio(ratio)
                chips.forEach { (other, otherRatio) ->
                    val active = otherRatio == ratio
                    other.setBackgroundResource(
                        if (active) R.drawable.bg_zoom_chip_active else R.drawable.bg_round_scrim_50
                    )
                    other.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (active) R.color.dc_bg else R.color.dc_text
                        )
                    )
                }
            }
        }
    }

    private fun bindTapToFocus() {
        val binding = binding ?: return
        binding.previewView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                engine?.focusAt(event.x, event.y)
                showFocusIndicatorAt(event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    private fun showFocusIndicatorAt(x: Float, y: Float) {
        val binding = binding ?: return
        val indicator = binding.focusIndicator
        val parent = indicator.parent as? View ?: return
        indicator.translationX = x - parent.width / 2f
        indicator.translationY = y - parent.height / 2f - indicator.height / 2f
        indicator.alpha = 1f
        indicator.animate().cancel()
        indicator.animate().alpha(0.35f).setStartDelay(1_200).setDuration(400).start()
    }

    // ── Shutter row ────────────────────────────────────────────────────────

    private fun bindShutterRow() {
        val binding = binding ?: return
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnFlip.setOnClickListener {
            engine?.switchLens()
            binding.previewView.postDelayed({
                updateResolutionBadge()
                updateHdrChip()
            }, RESOLUTION_SETTLE_MS)
        }
        binding.btnLastShot.setOnClickListener { navigateTo(GalleryFragment()) }
    }

    private fun takePhoto() {
        val binding = binding ?: return
        val engine = engine ?: return
        if (capturing) return
        capturing = true

        viewLifecycleOwner.lifecycleScope.launch {
            countDown(TIMER_SECONDS[timerIndex])
            binding.btnShutter.playShutterFeedback()
            playShutterSound()

            when (val result = PhotoCapture.capture(requireContext(), engine)) {
                is PhotoCapture.Result.Saved -> loadLastShot()
                is PhotoCapture.Result.Failed -> {
                    binding.tvCameraStatus.visibility = View.VISIBLE
                    binding.tvCameraStatus.text = getString(R.string.capture_failed)
                    result.error.printStackTrace()
                }
            }
            capturing = false
        }
    }

    /** The self-timer counts down in the badge that normally shows the resolution. */
    private suspend fun countDown(seconds: Int) {
        if (seconds <= 0) return
        val binding = binding ?: return
        for (remaining in seconds downTo 1) {
            binding.tvResolutionBadge.text = remaining.toString()
            delay(1_000)
        }
        updateResolutionBadge()
    }

    private fun playShutterSound() {
        if (!ViewfinderPrefs.get(requireContext(), ViewfinderPrefs.KEY_SHUTTER_SOUND)) return
        val sound = shutterSound ?: MediaActionSound().also { shutterSound = it }
        sound.play(MediaActionSound.SHUTTER_CLICK)
    }

    private fun loadLastShot() {
        val binding = binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = MediaRepository.latest(requireContext())
            if (latest != null) {
                binding.btnLastShot.load(latest.uri)
            } else {
                binding.btnLastShot.setImageDrawable(null)
            }
        }
    }

    // ── Mode strip ─────────────────────────────────────────────────────────

    private fun bindModeStrip() {
        val binding = binding ?: return
        binding.modeRow.bindCaptureModes(
            modes = listOf(
                CaptureMode(R.string.mode_night) { applyExtension(ExtensionMode.NIGHT, 0) },
                CaptureMode(R.string.mode_portrait) { applyExtension(ExtensionMode.BOKEH, 1) },
                CaptureMode(R.string.mode_beauty) { navigateTo(FiltersFragment()) },
                CaptureMode(R.string.mode_photo) { applyExtension(ExtensionMode.NONE, 3) },
                CaptureMode(R.string.mode_video) { navigateTo(VideoFragment()) },
                CaptureMode(R.string.mode_pro) { navigateTo(ProFragment()) }
            ),
            activeIndex = activeModeIndex,
            gapDp = 15,
            textSizeSp = 12f,
            letterSpacing = 0.08f
        )
    }

    private fun applyExtension(mode: Int, stripIndex: Int) {
        val engine = engine ?: return
        val binding = binding ?: return
        if (!engine.isModeSupported(mode)) {
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.mode_unavailable)
            binding.tvCameraStatus.postDelayed({
                binding.tvCameraStatus.visibility = View.GONE
            }, 1_800)
            return
        }
        hdrOn = mode == ExtensionMode.HDR
        engine.setExtensionMode(mode)
        activeModeIndex = stripIndex
        bindModeStrip()
        updateHdrChip()
    }

    override fun onShutterKey(): Boolean {
        takePhoto()
        return true
    }

    private fun visibility(visible: Boolean): Int = if (visible) View.VISIBLE else View.GONE

    private companion object {
        const val TORCH = -1
        const val RESOLUTION_SETTLE_MS = 400L

        val FLASH_MODES = intArrayOf(
            ImageCapture.FLASH_MODE_OFF,
            ImageCapture.FLASH_MODE_AUTO,
            ImageCapture.FLASH_MODE_ON,
            TORCH
        )
        val FLASH_LABELS = intArrayOf(
            R.string.flash_off,
            R.string.flash_auto,
            R.string.flash_on,
            R.string.flash_torch
        )
        val TIMER_SECONDS = intArrayOf(0, 3, 10)
    }
}
