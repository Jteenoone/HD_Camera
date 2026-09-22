package com.example.hd_camera.ui.camera

import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import com.example.hd_camera.databinding.ItemZoomChipBinding
import com.example.hd_camera.media.MediaRepository
import com.example.hd_camera.ui.OptionsPopup
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
    private var activeModeIndex = MODE_PHOTO
    private var selectedZoom = 1f

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
            applyStartingMode()
            // resolutionInfo is only populated once the use case is attached to the session.
            binding.previewView.postDelayed({
                updateResolutionBadge()
                updateHdrChip()
                bindZoom()
            }, RESOLUTION_SETTLE_MS)
        }

        engine.start(CameraEngine.Mode.PHOTO)

        activeModeIndex = arguments?.getInt(ARG_MODE, MODE_PHOTO) ?: MODE_PHOTO
        bindModeStrip()
        bindTopBar()
        bindShutterRow()
        bindTapToFocus()
    }

    override fun onResume() {
        super.onResume()
        // The Settings switches and the design's showGrid / showZoomChips props drive the
        // same state.
        val binding = binding ?: return
        val context = requireContext()
        binding.gridOverlay.visibility =
            visibility(ViewfinderPrefs.get(context, ViewfinderPrefs.KEY_GRID))
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

        binding.btnFlash.setOnClickListener { view -> showFlashOptions(view) }
        binding.btnTimer.setOnClickListener { view -> showTimerOptions(view) }

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

    /** Flash has four settings, so it drops a list rather than cycling on every tap. */
    private fun showFlashOptions(anchor: View) {
        OptionsPopup.show(
            anchor = anchor,
            options = FLASH_LABELS.map(::getString),
            selectedIndex = flashIndex
        ) { picked ->
            flashIndex = picked
            applyFlash()
        }
    }

    private fun applyFlash() {
        val engine = engine ?: return
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

    private fun showTimerOptions(anchor: View) {
        OptionsPopup.show(
            anchor = anchor,
            options = TIMER_LABELS.map(::getString),
            selectedIndex = timerIndex
        ) { picked ->
            timerIndex = picked
            updateTimerIcon()
        }
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

    /**
     * The steps come from the camera itself. The main lens bottoms out at 1x, so 0.5x only
     * appears when there is an ultra-wide to switch to — a chip that silently did nothing
     * was worse than no chip.
     */
    private fun bindZoom() {
        val binding = binding ?: return
        val engine = engine ?: return
        val row = binding.zoomChips
        val stops = engine.zoomStops()
        row.removeAllViews()

        val inflater = LayoutInflater.from(row.context)
        val gap = (8 * resources.displayMetrics.density).toInt()

        stops.forEachIndexed { index, stop ->
            val chip = ItemZoomChipBinding.inflate(inflater, row, false).root
            chip.text = formatZoom(stop)
            chip.setOnClickListener {
                selectedZoom = stop
                engine.requestZoom(stop)
                styleZoomChips()
            }
            (chip.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (index == 0) 0 else gap
            row.addView(chip)
        }
        styleZoomChips()
    }

    private fun styleZoomChips() {
        val binding = binding ?: return
        val engine = engine ?: return
        val stops = engine.zoomStops()
        for (index in 0 until binding.zoomChips.childCount) {
            val chip = binding.zoomChips.getChildAt(index) as? TextView ?: continue
            val active = stops.getOrNull(index) == selectedZoom
            chip.setBackgroundResource(
                if (active) R.drawable.bg_zoom_chip_active else R.drawable.bg_round_scrim_50
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (active) R.color.dc_bg else R.color.dc_text
                )
            )
        }
    }

    private fun formatZoom(stop: Float): String =
        if (stop < 1f) ".5\u00d7" else stop.toInt().toString() + "\u00d7"

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
        // A second tap during the countdown would otherwise start a second one.
        if (capturing) return
        capturing = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                countDown(TIMER_SECONDS[timerIndex])
                binding.btnShutter.playShutterFeedback()
                playShutterSound()

                when (val result = PhotoCapture.capture(requireContext(), engine)) {
                    is PhotoCapture.Result.Saved -> showLastShot(result.uri)
                    is PhotoCapture.Result.Failed -> {
                        binding.tvCameraStatus.visibility = View.VISIBLE
                        binding.tvCameraStatus.text = getString(R.string.capture_failed)
                        result.error.printStackTrace()
                    }
                }
            } finally {
                // Cancellation — the screen closing mid-countdown — must not leave the
                // shutter dead or the countdown frozen on screen.
                hideCountdown()
                capturing = false
            }
        }
    }

    /**
     * The self-timer used the resolution badge, which is 12sp in the corner of the frame.
     * It counts in the middle of the preview instead, one big digit a second.
     */
    private suspend fun countDown(seconds: Int) {
        if (seconds <= 0) return
        val binding = binding ?: return
        val countdown = binding.tvCountdown
        countdown.visibility = View.VISIBLE
        for (remaining in seconds downTo 1) {
            countdown.text = getString(R.string.countdown_value, remaining)
            // Purely decorative: the animation runs alongside the wait, never before it.
            countdown.alpha = 1f
            countdown.scaleX = 1.25f
            countdown.scaleY = 1.25f
            countdown.animate().cancel()
            countdown.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(0.85f)
                .setDuration(COUNTDOWN_TICK_MS)
                .start()
            delay(1_000)
        }
        hideCountdown()
    }

    private fun hideCountdown() {
        val countdown = binding?.tvCountdown ?: return
        countdown.animate().cancel()
        countdown.visibility = View.GONE
        countdown.alpha = 1f
        countdown.scaleX = 1f
        countdown.scaleY = 1f
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

    /**
     * Shows the shot that was just saved. CameraX hands back the URI directly, so the
     * thumbnail does not have to wait for MediaStore to publish the row; [loadLastShot] is
     * only the fallback for the paths that report no URI.
     */
    private fun showLastShot(uri: Uri?) {
        val binding = binding ?: return
        if (uri != null) {
            binding.btnLastShot.load(uri)
        } else {
            loadLastShot()
        }
    }

    // ── Mode strip ─────────────────────────────────────────────────────────

    private fun bindModeStrip() {
        val binding = binding ?: return
        binding.modeRow.bindCaptureModes(
            modes = listOf(
                CaptureMode(R.string.mode_night) {
                    applyExtension(ExtensionMode.NIGHT, MODE_NIGHT)
                },
                CaptureMode(R.string.mode_portrait) {
                    applyExtension(ExtensionMode.BOKEH, MODE_PORTRAIT)
                },
                CaptureMode(R.string.mode_beauty) { navigateTo(FiltersFragment()) },
                CaptureMode(R.string.mode_photo) {
                    applyExtension(ExtensionMode.NONE, MODE_PHOTO)
                },
                CaptureMode(R.string.mode_video) { navigateTo(VideoFragment()) },
                CaptureMode(R.string.mode_pro) { navigateTo(ProFragment()) }
            ),
            activeIndex = activeModeIndex,
            gapDp = 15,
            textSizeSp = 12f,
            letterSpacing = 0.08f
        )
    }

    /** Honours the mode another screen asked for once the camera is actually open. */
    private fun applyStartingMode() {
        val extension = extensionForStrip(activeModeIndex) ?: return
        if (extension == ExtensionMode.NONE) return
        applyExtension(extension, activeModeIndex)
    }

    private fun extensionForStrip(index: Int): Int? = when (index) {
        MODE_NIGHT -> ExtensionMode.NIGHT
        MODE_PORTRAIT -> ExtensionMode.BOKEH
        MODE_PHOTO -> ExtensionMode.NONE
        else -> null
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

    companion object {
        /** Positions in the shared capture-mode strip. */
        const val MODE_NIGHT = 0
        const val MODE_PORTRAIT = 1
        const val MODE_PHOTO = 3

        private const val ARG_MODE = "initial_mode"

        /** Opens the viewfinder already switched to [mode]. */
        fun of(mode: Int): PhotoFragment = PhotoFragment().apply {
            arguments = Bundle().apply { putInt(ARG_MODE, mode) }
        }

        const val TORCH = -1
        const val RESOLUTION_SETTLE_MS = 400L
        const val COUNTDOWN_TICK_MS = 260L

        val FLASH_MODES = intArrayOf(
            ImageCapture.FLASH_MODE_OFF,
            ImageCapture.FLASH_MODE_AUTO,
            ImageCapture.FLASH_MODE_ON,
            TORCH
        )
        val FLASH_LABELS = listOf(
            R.string.flash_off,
            R.string.flash_auto,
            R.string.flash_on,
            R.string.flash_torch
        )
        val TIMER_SECONDS = intArrayOf(0, 3, 10)
        val TIMER_LABELS = listOf(
            R.string.timer_off,
            R.string.timer_3s,
            R.string.timer_10s
        )
    }
}
