package com.example.hd_camera.ui.camera

import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionMode
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.hd_camera.R
import com.example.hd_camera.camera.CameraEngine
import com.example.hd_camera.camera.CaptureModeResolver
import com.example.hd_camera.camera.CaptureModeSession
import com.example.hd_camera.camera.ModeDelivery
import com.example.hd_camera.camera.PhotoCapture
import com.example.hd_camera.camera.ZoomMath
import com.example.hd_camera.data.CaptureFormat
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.data.ViewfinderPrefs
import com.example.hd_camera.databinding.FragmentPhotoBinding
import com.example.hd_camera.databinding.ItemZoomChipBinding
import com.example.hd_camera.media.MediaRepository
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.gallery.GalleryFragment
import com.example.hd_camera.ui.navigateTo
import com.example.hd_camera.ui.options.CameraOption
import com.example.hd_camera.ui.options.CameraOptionPopup
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

    /** Which mode the camera really has, and which request is in flight. */
    private val modeSession = CaptureModeSession(ExtensionMode.NONE)

    /**
     * The mode another screen asked for is honoured once, on the first session. Doing it on
     * every ready callback made a successful bind ask for the previous mode all over again
     * and tear the new session straight back down.
     */
    private var startingModeApplied = false

    /** The zoom as the user asked for it: 0.5x means the ultra-wide, not a sensor ratio. */
    private var zoomRatio = 1f
    private var zoomStops: List<Float> = emptyList()

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
            if (!startingModeApplied) {
                startingModeApplied = true
                applyStartingMode()
            }
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
        bindViewfinderGestures()
    }

    override fun onResume() {
        super.onResume()
        // The Settings switches and the design's showGrid / showZoomChips props drive the
        // same state.
        val binding = binding ?: return
        val context = requireContext()
        binding.gridOverlay.visibility =
            visibility(ViewfinderPrefs.get(context, ViewfinderPrefs.KEY_GRID))
        applyZoomChipVisibility()
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

        binding.btnRatio.setOnClickListener { view -> showAspectRatioOptions(view) }

        binding.btnHdr.setOnClickListener {
            // HDR is an extension like the others, so it takes the same guarded path.
            applyExtension(
                if (hdrOn) ExtensionMode.NONE else ExtensionMode.HDR,
                activeModeIndex
            )
        }

        binding.btnSettings.setOnClickListener { navigateTo(SettingsFragment()) }
        updateFlashIcon()
        updateTimerIcon()
    }

    /**
     * Flash has four settings, so it drops the whole list rather than advancing one step
     * per tap. A camera with no flash still offers Off — the setting it is already in —
     * and says why the other three are out.
     */
    private fun showFlashOptions(anchor: View) {
        CameraOptionPopup.show(
            anchor = anchor,
            titleRes = R.string.cd_flash,
            options = FLASH_LABELS.mapIndexed { index, label ->
                val supported = flashAvailable() ||
                    FLASH_MODES[index] == ImageCapture.FLASH_MODE_OFF
                CameraOption(
                    id = index.toString(),
                    title = getString(label),
                    selected = index == flashIndex,
                    enabled = supported,
                    disabledReason = getString(R.string.flash_unsupported).takeIf { !supported }
                )
            }
        ) { picked ->
            flashIndex = picked.id.toInt()
            applyFlash()
        }
    }

    /**
     * Before the session is up the query answers "no flash", which is not the same as a
     * camera without one, so an unopened camera is given the benefit of the doubt.
     */
    private fun flashAvailable(): Boolean = engine?.let { !it.isReady || it.hasFlash() } ?: true

    /** Both stops on this device: 4:3 uses the whole sensor, 16:9 crops it. */
    private fun showAspectRatioOptions(anchor: View) {
        CameraOptionPopup.show(
            anchor = anchor,
            titleRes = R.string.aspect_ratio,
            options = listOf(
                CameraOption(
                    id = RATIO_4_3,
                    title = getString(R.string.ratio_4_3),
                    selected = !wideRatio
                ),
                CameraOption(
                    id = RATIO_16_9,
                    title = getString(R.string.ratio_16_9),
                    selected = wideRatio
                )
            )
        ) { picked -> applyAspectRatio(picked.id == RATIO_16_9) }
    }

    private fun applyAspectRatio(wide: Boolean) {
        val binding = binding ?: return
        wideRatio = wide
        binding.btnRatio.setText(if (wide) R.string.ratio_16_9 else R.string.ratio_4_3)
        engine?.setAspectRatio(
            if (wide) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        )
        binding.previewView.postDelayed({ updateResolutionBadge() }, RESOLUTION_SETTLE_MS)
    }

    private fun applyFlash() {
        val engine = engine ?: return
        val mode = FLASH_MODES[flashIndex]
        engine.setTorch(mode == TORCH)
        if (mode != TORCH) engine.setFlashMode(mode)
        updateFlashIcon()
    }

    /**
     * The front camera usually has no flash. Coming back from one that did, the setting
     * would otherwise sit on Torch with nothing to light.
     */
    private fun syncFlashToCamera() {
        if (flashAvailable() || flashIndex == 0) return
        flashIndex = 0
        applyFlash()
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
        CameraOptionPopup.show(
            anchor = anchor,
            titleRes = R.string.cd_timer,
            options = TIMER_LABELS.mapIndexed { index, label ->
                CameraOption(
                    id = index.toString(),
                    title = getString(label),
                    selected = index == timerIndex
                )
            }
        ) { picked ->
            timerIndex = picked.id.toInt()
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
        binding.btnHdr.alpha = if (available) 1f else DISABLED_ALPHA
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
        val stops = engine.zoomStops().also { zoomStops = it }
        row.removeAllViews()

        val inflater = LayoutInflater.from(row.context)
        val gap = (8 * resources.displayMetrics.density).toInt()

        stops.forEachIndexed { index, stop ->
            val chip = ItemZoomChipBinding.inflate(inflater, row, false).root
            chip.text = ZoomMath.label(stop)
            chip.setOnClickListener { applyZoom(engine.requestZoom(stop)) }
            chip.setOnLongClickListener {
                showZoomWheel()
                true
            }
            (chip.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (index == 0) 0 else gap
            row.addView(chip)
        }
        row.onSwipeUp = { showZoomWheel() }
        bindZoomArc()
        applyZoomChipVisibility()
        styleZoomChips()
    }

    /**
     * The wheel reads the camera through the engine and asks it for a ratio; it decides
     * nothing about lenses itself. Every way of zooming ends up on the same [zoomRatio].
     */
    private fun bindZoomArc() {
        val arc = binding?.zoomArc ?: return
        arc.onZoomStart = { arc.removeCallbacks(hideZoomWheel) }
        arc.onZoomChanged = { requested -> applyWheelZoom(requested) }
        arc.onZoomEnd = { scheduleZoomWheelHide() }
        syncZoomArc()
    }

    /** Keeps the arc showing what the camera can do and where it currently is. */
    private fun syncZoomArc() {
        val arc = binding?.zoomArc ?: return
        val engine = engine ?: return
        arc.range = engine.fullZoomRange()
        arc.reachable = engine.availableZoomRange()
        arc.stops = zoomStops
        arc.zoom = zoomRatio
    }

    private fun applyWheelZoom(requested: Float) {
        val engine = engine ?: return
        zoomRatio = engine.requestZoom(requested)
        binding?.zoomArc?.zoom = zoomRatio
        showZoomReadout(zoomRatio)
        styleZoomChips()
    }

    /** Opened by holding a chip, or by swiping up off the row. */
    private fun showZoomWheel() {
        val arc = binding?.zoomArc ?: return
        if (zoomStops.size < 2) return
        arc.removeCallbacks(hideZoomWheel)
        syncZoomArc()
        if (arc.visibility == View.VISIBLE) return
        arc.alpha = 0f
        arc.visibility = View.VISIBLE
        arc.animate().cancel()
        arc.animate().alpha(1f).setDuration(WHEEL_FADE_MS).start()
    }

    /** Left up for a moment after the finger goes, so the value can be read. */
    private fun scheduleZoomWheelHide() {
        val arc = binding?.zoomArc ?: return
        arc.removeCallbacks(hideZoomWheel)
        arc.postDelayed(hideZoomWheel, WHEEL_LINGER_MS)
    }

    private val hideZoomWheel = Runnable {
        val arc = binding?.zoomArc ?: return@Runnable
        arc.animate().cancel()
        arc.animate()
            .alpha(0f)
            .setDuration(WHEEL_FADE_MS)
            .withEndAction { binding?.zoomArc?.visibility = View.GONE }
            .start()
    }

    /**
     * The row answers to the Settings switch and to the camera both: a single step is not a
     * choice, so a camera that cannot zoom at all gets no chips rather than a dead 1× one.
     */
    private fun applyZoomChipVisibility() {
        val binding = binding ?: return
        val wanted = ViewfinderPrefs.get(requireContext(), ViewfinderPrefs.KEY_ZOOM_CHIPS)
        binding.zoomChips.visibility = visibility(wanted && zoomStops.size > 1)
    }

    private fun styleZoomChips() {
        val binding = binding ?: return
        val engine = engine ?: return
        val range = engine.availableZoomRange()
        for (index in 0 until binding.zoomChips.childCount) {
            val chip = binding.zoomChips.getChildAt(index) as? TextView ?: continue
            val stop = zoomStops.getOrNull(index) ?: continue
            val active = ZoomMath.matches(zoomRatio, stop)
            val reachable = ZoomMath.reachable(stop, range)
            // On the black panel a translucent scrim reads as nothing at all, so an
            // unselected chip gets a solid surface of its own.
            chip.setBackgroundResource(
                if (active) R.drawable.bg_zoom_chip_active else R.drawable.bg_zoom_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (active) R.color.dc_bg else R.color.dc_text
                )
            )
            chip.isEnabled = reachable
            chip.alpha = if (reachable) 1f else DISABLED_ALPHA
        }
        binding.zoomArc.reachable = range
    }

    /** Puts the ratio the camera settled on into the readout and onto the chips. */
    private fun applyZoom(ratio: Float) {
        zoomRatio = ratio
        binding?.zoomArc?.zoom = ratio
        showZoomReadout(ratio)
        fadeZoomReadout()
        styleZoomChips()
    }

    private fun pinchZoom(factor: Float) {
        val engine = engine ?: return
        val target = ZoomMath.pinch(zoomRatio, factor, engine.availableZoomRange())
        zoomRatio = engine.requestZoom(target)
        // A pinch moves the wheel too, so the two never disagree about where the zoom is.
        binding?.zoomArc?.zoom = zoomRatio
        showZoomReadout(zoomRatio)
        styleZoomChips()
    }

    private fun showZoomReadout(ratio: Float) {
        val binding = binding ?: return
        val readout = binding.tvZoomRatio
        readout.removeCallbacks(hideZoomReadout)
        readout.text = ZoomMath.label(ratio)
        // The wheel carries the value in its own middle while it is up, and the same
        // number twice, an inch apart, reads as a fault rather than as emphasis.
        readout.visibility = if (binding.zoomArc.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    /** Leaves the last value up just long enough to read, then takes it away again. */
    private fun fadeZoomReadout() {
        val readout = binding?.tvZoomRatio ?: return
        readout.removeCallbacks(hideZoomReadout)
        readout.postDelayed(hideZoomReadout, ZOOM_READOUT_MS)
    }

    private val hideZoomReadout = Runnable { binding?.tvZoomRatio?.visibility = View.GONE }

    /**
     * Pinch zooms, a single tap focuses. Telling them apart matters: the finger that ends a
     * pinch used to arrive as a tap and send the camera off to focus on the frame's middle.
     */
    private fun bindViewfinderGestures() {
        val binding = binding ?: return
        ViewfinderGestures(
            view = binding.previewView,
            // The camera is the authority on where the zoom actually is by now.
            onZoomBegin = { zoomRatio = engine?.currentZoomRatio() ?: zoomRatio },
            onZoom = { factor -> pinchZoom(factor) },
            onZoomEnd = { fadeZoomReadout() },
            onTap = { x, y ->
                engine?.focusAt(x, y)
                showFocusIndicatorAt(x, y)
            }
        )
    }

    /**
     * [x] and [y] are where the finger landed on the preview. The reticle is centred in the
     * viewfinder, which is the preview's own box, so the offset from the middle is all it
     * takes to put the box under the fingertip.
     */
    private fun showFocusIndicatorAt(x: Float, y: Float) {
        val binding = binding ?: return
        val indicator = binding.focusIndicator
        val frame = binding.viewfinder
        indicator.translationX = x - frame.width / 2f
        indicator.translationY = y - frame.height / 2f
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
                syncFlashToCamera()
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
            gapDp = MODE_STRIP_GAP_DP,
            textSizeSp = MODE_STRIP_TEXT_SP,
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

    /**
     * Asks the camera for a mode and only marks it active once the session is really on it.
     * A mode this device cannot deliver leaves the strip, and the camera, exactly where
     * they were — the preview is the one thing that must never be the price of a mode.
     */
    private fun applyExtension(mode: Int, stripIndex: Int) {
        val engine = engine ?: return

        // Ask before binding: a mode nothing can deliver never gets to unbind anything.
        val delivery = CaptureModeResolver.deliveryFor(
            requested = mode,
            extensionAvailable = engine.isExtensionAvailable(mode),
            sceneAvailable = engine.sceneModeAvailable(mode)
        )
        if (delivery == ModeDelivery.UNSUPPORTED) {
            showStatus(unsupportedMessage(mode))
            return
        }

        val token = modeSession.request(mode)
        val delivered = engine.setExtensionMode(mode)
        if (!delivered) {
            modeSession.failed(token)
            showStatus(unsupportedMessage(mode))
            // The engine has put a working session back; the strip never moved.
            updateHdrChip()
            return
        }
        // A newer request overtook this one while it was binding; that one owns the screen.
        if (!modeSession.succeeded(token)) return

        hdrOn = mode == ExtensionMode.HDR
        activeModeIndex = stripIndex
        bindModeStrip()
        updateHdrChip()
        warnIfRawDropped()
    }

    /**
     * A vendor pipeline cannot write a DNG, so RAW quietly stops applying in Night or
     * Portrait. Saying so beats letting the user find out on the computer.
     */
    private fun warnIfRawDropped() {
        val engine = engine ?: return
        if (engine.modeDelivery != ModeDelivery.VENDOR_EXTENSION) return
        if (CaptureSettings.format(requireContext()) != CaptureFormat.JPEG_RAW) return
        showStatus(R.string.raw_unavailable_in_mode)
    }

    @StringRes
    private fun unsupportedMessage(mode: Int): Int = when (mode) {
        ExtensionMode.NIGHT -> R.string.night_unsupported
        ExtensionMode.BOKEH -> R.string.portrait_unsupported
        ExtensionMode.HDR -> R.string.hdr_unsupported
        else -> R.string.mode_unavailable
    }

    /** One place for the transient line over the preview, so none of them can stick. */
    private fun showStatus(@StringRes messageId: Int) {
        val status = binding?.tvCameraStatus ?: return
        status.visibility = View.VISIBLE
        status.setText(messageId)
        status.removeCallbacks(hideStatus)
        status.postDelayed(hideStatus, STATUS_MS)
    }

    private val hideStatus = Runnable { binding?.tvCameraStatus?.visibility = View.GONE }

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

        /** Option ids for the aspect-ratio chooser. */
        const val RATIO_4_3 = "4:3"
        const val RATIO_16_9 = "16:9"

        const val RESOLUTION_SETTLE_MS = 400L
        const val COUNTDOWN_TICK_MS = 260L

        /** How long a transient line stays over the preview. */
        const val STATUS_MS = 1_800L

        /** How long the pinch readout stays up once the fingers have left. */
        const val ZOOM_READOUT_MS = 900L

        /** Long enough to read as a movement, short enough not to hold up the shot. */
        const val WHEEL_FADE_MS = 180L

        /** How long the wheel stays after the finger leaves it. */
        const val WHEEL_LINGER_MS = 1_200L

        /** The strip sits on its own panel now, so it is no longer squeezed for room. */
        const val MODE_STRIP_GAP_DP = 18
        const val MODE_STRIP_TEXT_SP = 13f

        /** What a control that the camera cannot honour right now looks like. */
        const val DISABLED_ALPHA = 0.4f

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
