package com.example.hd_camera.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.video.AudioStats
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.request.videoFrameMillis
import com.example.hd_camera.R
import com.example.hd_camera.camera.CameraEngine
import com.example.hd_camera.camera.PhotoCapture
import com.example.hd_camera.camera.ZoomMath
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.data.VideoProfile
import com.example.hd_camera.data.ViewfinderPrefs
import com.example.hd_camera.databinding.FragmentVideoBinding
import com.example.hd_camera.databinding.ItemZoomChipBinding
import com.example.hd_camera.media.MediaRepository
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.gallery.GalleryFragment
import com.example.hd_camera.ui.navigateTo
import com.example.hd_camera.ui.navigateToRoot
import com.example.hd_camera.ui.options.CameraOption
import com.example.hd_camera.ui.options.CameraOptionBottomSheet
import com.example.hd_camera.ui.options.CaptureOptions
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.log10

/** Screen 08 · Video recording. */
class VideoFragment :
    Fragment(R.layout.fragment_video),
    ShutterKeyHandler,
    CameraOptionBottomSheet.Host {

    private var binding: FragmentVideoBinding? = null
    private var engine: CameraEngine? = null

    /** Guards the snapshot button against a double tap landing two captures in flight. */
    private var snapshotInFlight = false

    /** The zoom as the user asked for it: 0.5x means the ultra-wide, not a sensor ratio. */
    private var zoomRatio = 1f
    private var zoomStops: List<Float> = emptyList()

    /**
     * Recording silently without the microphone is the sort of thing you only notice once
     * the clip is on a computer, so the first attempt asks for the permission.
     */
    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startRecordingNow() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentVideoBinding.bind(view).also { this.binding = it }
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
            binding.previewView.postDelayed({
                updateStabilizationChip()
                updateQualityChip()
                bindZoom()
                applyRecordingChrome()
            }, CHIP_SETTLE_MS)
        }
        engine.start(CameraEngine.Mode.VIDEO)

        binding.tvRecTime.text = formatDuration(0)
        applyRecordingChrome()
        updateAudioMeter(amplitude = 0.0, active = false)

        bindModeStrip()

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnQuality.setOnClickListener { ifIdle { showQualityOptions() } }
        binding.btnEis.setOnClickListener { ifIdle { toggleStabilization() } }
        binding.btnFlip.setOnClickListener { ifIdle { engine.switchLens() } }
        binding.btnLastShot.setOnClickListener { navigateTo(GalleryFragment()) }
        binding.btnPauseResume.setOnClickListener { togglePause() }
        binding.btnSnapshot.setOnClickListener { takeSnapshot() }
        bindViewfinderGestures()
    }

    override fun onResume() {
        super.onResume()
        applyZoomChipVisibility()
        // Only meaningful when nothing is being recorded: while it is, that slot is Pause.
        if (engine?.recordingState == CameraEngine.RecordingState.IDLE) loadLastShot()
    }

    override fun onDestroyView() {
        engine?.release()
        engine = null
        binding = null
        super.onDestroyView()
    }

    override fun onShutterKey(): Boolean {
        toggleRecording()
        return true
    }

    /** The same capture-mode strip the rest of the app uses, with Video marked active. */
    private fun bindModeStrip() {
        val binding = binding ?: return
        binding.modeRow.bindCaptureModes(
            modes = listOf(
                CaptureMode(R.string.mode_night) {
                    ifIdle { openPhotoMode(PhotoFragment.MODE_NIGHT) }
                },
                CaptureMode(R.string.mode_portrait) {
                    ifIdle { openPhotoMode(PhotoFragment.MODE_PORTRAIT) }
                },
                CaptureMode(R.string.mode_beauty) { ifIdle { navigateTo(FiltersFragment()) } },
                CaptureMode(R.string.mode_photo) {
                    ifIdle { openPhotoMode(PhotoFragment.MODE_PHOTO) }
                },
                CaptureMode(R.string.mode_video) { /* already here */ },
                CaptureMode(R.string.mode_pro) { ifIdle { navigateTo(ProFragment()) } }
            ),
            activeIndex = 4,
            gapDp = 15,
            textSizeSp = 12f,
            letterSpacing = 0.08f,
            dot = R.drawable.bg_mode_dot_record
        )
    }

    private fun openPhotoMode(mode: Int) {
        navigateToRoot(PhotoFragment.of(mode))
    }

    /**
     * Anything that would rebind the camera — a new lens, quality, EIS, or leaving for
     * another mode — would tear the recorder's surface out from under it, so it waits.
     */
    private inline fun ifIdle(action: () -> Unit) {
        if (engine?.recordingState != CameraEngine.RecordingState.IDLE) {
            showStatus(R.string.locked_while_recording)
            return
        }
        action()
    }

    // ── Recording ──────────────────────────────────────────────────────────

    private fun toggleRecording() {
        val engine = engine ?: return
        when (engine.recordingState) {
            CameraEngine.RecordingState.RECORDING,
            CameraEngine.RecordingState.PAUSED -> {
                engine.stopRecording()
                applyRecordingChrome()
            }
            // A tap arriving while the last clip is still being written does nothing.
            CameraEngine.RecordingState.FINALIZING -> Unit
            CameraEngine.RecordingState.IDLE -> {
                if (!micGranted()) {
                    requestMic.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                startRecordingNow()
            }
        }
    }

    private fun startRecordingNow() {
        val engine = engine ?: return
        if (engine.recordingState != CameraEngine.RecordingState.IDLE) return

        val started = engine.startRecording(withAudio = micGranted()) { event ->
            onRecordEvent(event)
        }
        val binding = binding ?: return
        if (!started) {
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.recording_failed)
            return
        }
        applyRecordingChrome()
        if (!micGranted()) {
            // Recording carries on, but say plainly that it will be silent.
            showStatus(R.string.recording_without_audio, AUDIO_NOTICE_MS)
        }
    }

    private fun togglePause() {
        val engine = engine ?: return
        when (engine.recordingState) {
            CameraEngine.RecordingState.RECORDING -> engine.pauseRecording()
            CameraEngine.RecordingState.PAUSED -> engine.resumeRecording()
            else -> Unit
        }
        // The Pause / Resume events settle the real state; this keeps the tap responsive.
        applyRecordingChrome()
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        // Events keep arriving while the recorder closes the file, which can outlive the view.
        val binding = binding ?: return
        when (event) {
            is VideoRecordEvent.Start,
            is VideoRecordEvent.Pause,
            is VideoRecordEvent.Resume -> applyRecordingChrome()

            is VideoRecordEvent.Status -> {
                binding.tvRecTime.text =
                    formatDuration(event.recordingStats.recordedDurationNanos / 1_000_000_000L)
                val audio = event.recordingStats.audioStats
                updateAudioMeter(
                    amplitude = audio.audioAmplitude,
                    active = audio.audioState == AudioStats.AUDIO_STATE_ACTIVE
                )
            }

            is VideoRecordEvent.Finalize -> {
                applyRecordingChrome()
                updateAudioMeter(amplitude = 0.0, active = false)
                binding.tvRecTime.text = formatDuration(
                    event.recordingStats.recordedDurationNanos / 1_000_000_000L
                )
                if (event.hasError()) {
                    // A half-written file is not worth putting in the thumbnail.
                    binding.tvCameraStatus.visibility = View.VISIBLE
                    binding.tvCameraStatus.text = getString(R.string.recording_failed)
                } else {
                    showLastShot(event.outputResults.outputUri, isVideo = true)
                }
            }

            else -> Unit
        }
    }

    // ── Snapshot while recording ───────────────────────────────────────────

    /**
     * Still capture runs on its own use case, bound alongside the recorder, so the clip is
     * never interrupted. Cameras that cannot hold all three use cases leave the button off.
     */
    private fun takeSnapshot() {
        val engine = engine ?: return
        val binding = binding ?: return
        if (!engine.snapshotSupported) {
            showStatus(R.string.snapshot_unsupported)
            return
        }
        if (snapshotInFlight) return
        snapshotInFlight = true
        binding.btnSnapshot.playShutterFeedback()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = PhotoCapture.capture(requireContext(), engine)
                if (result is PhotoCapture.Result.Failed) {
                    showStatus(R.string.capture_failed)
                    result.error.printStackTrace()
                }
            } finally {
                snapshotInFlight = false
            }
        }
    }

    // ── Zoom ───────────────────────────────────────────────────────────────

    /**
     * The same quick-zoom row the Photo screen has, built from what this camera can reach.
     * Zooming is one of the few things that does not rebind the session, so unlike the
     * quality and EIS chips these keep working while a clip is being recorded.
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
            (chip.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (index == 0) 0 else gap
            row.addView(chip)
        }
        applyZoomChipVisibility()
        styleZoomChips()
    }

    /**
     * The row answers to the Settings switch and to the camera both: a single step is not a
     * choice, so a camera that cannot zoom at all gets no chips rather than a dead 1× one.
     */
    private fun applyZoomChipVisibility() {
        val binding = binding ?: return
        val wanted = ViewfinderPrefs.get(requireContext(), ViewfinderPrefs.KEY_ZOOM_CHIPS)
        binding.zoomChips.visibility =
            if (wanted && zoomStops.size > 1) View.VISIBLE else View.GONE
    }

    /**
     * A step that would need the ultra-wide is out of reach while the recorder holds the
     * session, so it reads as unavailable rather than doing nothing when tapped.
     */
    private fun styleZoomChips() {
        val binding = binding ?: return
        val engine = engine ?: return
        val range = engine.availableZoomRange()
        for (index in 0 until binding.zoomChips.childCount) {
            val chip = binding.zoomChips.getChildAt(index) as? TextView ?: continue
            val stop = zoomStops.getOrNull(index) ?: continue
            val active = ZoomMath.matches(zoomRatio, stop)
            val reachable = ZoomMath.reachable(stop, range)
            // This screen has no black panel behind it, so its chips keep the scrim
            // they need to stay readable over a live picture.
            chip.setBackgroundResource(
                if (active) R.drawable.bg_zoom_chip_active else R.drawable.bg_round_scrim_50
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
    }

    /** Puts the ratio the camera settled on into the readout and onto the chips. */
    private fun applyZoom(ratio: Float) {
        zoomRatio = ratio
        showZoomReadout(ratio)
        fadeZoomReadout()
        styleZoomChips()
    }

    private fun pinchZoom(factor: Float) {
        val engine = engine ?: return
        val target = ZoomMath.pinch(zoomRatio, factor, engine.availableZoomRange())
        zoomRatio = engine.requestZoom(target)
        showZoomReadout(zoomRatio)
        styleZoomChips()
    }

    private fun showZoomReadout(ratio: Float) {
        val readout = binding?.tvZoomRatio ?: return
        readout.removeCallbacks(hideZoomReadout)
        readout.text = ZoomMath.label(ratio)
        readout.visibility = View.VISIBLE
    }

    /** Leaves the last value up just long enough to read, then takes it away again. */
    private fun fadeZoomReadout() {
        val readout = binding?.tvZoomRatio ?: return
        readout.removeCallbacks(hideZoomReadout)
        readout.postDelayed(hideZoomReadout, ZOOM_READOUT_MS)
    }

    private val hideZoomReadout = Runnable { binding?.tvZoomRatio?.visibility = View.GONE }

    /**
     * Pinch only. Tap on this screen is left alone: focus and exposure gestures are the
     * next piece of work, and a half-wired tap would be worse than none.
     */
    private fun bindViewfinderGestures() {
        val binding = binding ?: return
        ViewfinderGestures(
            view = binding.previewView,
            // The camera is the authority on where the zoom actually is by now.
            onZoomBegin = { zoomRatio = engine?.currentZoomRatio() ?: zoomRatio },
            onZoom = { factor -> pinchZoom(factor) },
            onZoomEnd = { fadeZoomReadout() }
        )
    }

    // ── Thumbnail ──────────────────────────────────────────────────────────

    private fun loadLastShot() {
        val binding = binding ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val latest = MediaRepository.latest(requireContext())
            if (latest != null) {
                showLastShot(latest.uri, latest.isVideo)
            } else {
                binding.btnLastShot.setImageDrawable(null)
            }
        }
    }

    /**
     * Coil decodes a real frame for clips — the very first one is often still black while
     * exposure settles, so the thumbnail comes from just under a second in.
     */
    private fun showLastShot(uri: Uri?, isVideo: Boolean) {
        val binding = binding ?: return
        if (uri == null || uri == Uri.EMPTY) {
            loadLastShot()
            return
        }
        binding.btnLastShot.load(uri) {
            crossfade(true)
            if (isVideo) videoFrameMillis(VIDEO_THUMB_MS)
        }
    }

    // ── Chrome ─────────────────────────────────────────────────────────────

    /**
     * Idle shows thumbnail · record · flip. Recording swaps the outer two for pause and a
     * snapshot button, and turns the shutter into a stop button.
     */
    private fun applyRecordingChrome() {
        val binding = binding ?: return
        val state = engine?.recordingState ?: CameraEngine.RecordingState.IDLE
        val idle = state == CameraEngine.RecordingState.IDLE
        val paused = state == CameraEngine.RecordingState.PAUSED
        val finalizing = state == CameraEngine.RecordingState.FINALIZING
        val live = !idle && !finalizing

        binding.recBadge.alpha = if (idle) 0.45f else 1f
        binding.recIndicator.visibility = when {
            paused -> View.INVISIBLE
            live -> View.VISIBLE
            else -> View.INVISIBLE
        }
        binding.recordInner.setBackgroundResource(
            if (idle) R.drawable.bg_record_circle else R.drawable.bg_record_square
        )
        val size = resources.getDimensionPixelSize(
            if (idle) R.dimen.record_inner_idle else R.dimen.record_inner_recording
        )
        binding.recordInner.layoutParams = binding.recordInner.layoutParams.apply {
            width = size
            height = size
        }
        binding.btnRecord.contentDescription =
            getString(if (idle) R.string.cd_record else R.string.cd_stop)
        binding.btnRecord.isEnabled = !finalizing
        binding.btnRecord.alpha = if (finalizing) 0.5f else 1f

        binding.btnLastShot.visibility = if (idle) View.VISIBLE else View.GONE
        binding.btnPauseResume.visibility = if (live) View.VISIBLE else View.GONE
        binding.btnPauseResume.setImageResource(
            if (paused) R.drawable.ic_play else R.drawable.ic_pause
        )
        binding.btnPauseResume.contentDescription =
            getString(if (paused) R.string.cd_resume else R.string.cd_pause)

        binding.btnFlip.visibility = if (idle) View.VISIBLE else View.GONE
        val canSnapshot = engine?.snapshotSupported == true
        binding.btnSnapshot.visibility = if (live) View.VISIBLE else View.GONE
        binding.btnSnapshot.alpha = if (canSnapshot) 1f else DISABLED_ALPHA
        binding.btnSnapshot.isEnabled = canSnapshot

        // The chips stay on screen but read as unavailable while the recorder owns the camera.
        val chipAlpha = if (idle) 1f else DISABLED_ALPHA
        binding.btnQuality.alpha = chipAlpha
        binding.modeRow.alpha = chipAlpha
        updateStabilizationChip()
        // A lens change is off the table while the recorder owns the session.
        styleZoomChips()
    }

    /**
     * Amplitude arrives as 0..1 and is drawn on a log scale like a real VU meter. When the
     * recording carries no audio the whole meter is dimmed rather than left showing a frozen
     * pattern.
     */
    private fun updateAudioMeter(amplitude: Double, active: Boolean) {
        val binding = binding ?: return
        val meter = binding.audioMeter
        binding.micIcon.alpha = if (active) 1f else 0.35f

        val level = when {
            !active -> 0f
            amplitude <= 0.0 -> 0f
            else -> ((20.0 * log10(amplitude) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        }
        // Round up so any sound at all lights the first bar.
        val lit = ceil(level * meter.childCount).toInt()
        for (index in 0 until meter.childCount) {
            meter.getChildAt(index).setBackgroundResource(
                if (index < lit) R.drawable.bg_audio_bar_on else R.drawable.bg_audio_bar_off
            )
        }
    }

    private fun micGranted(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Resolution and frame rate come as one list, showing every profile the app knows and
     * saying which of them this camera cannot deliver. Picking one rebinds the session, so
     * the chip is only live while the recorder is idle.
     */
    private fun showQualityOptions() {
        CameraOptionBottomSheet.show(this, CaptureOptions.KEY_VIDEO_PROFILE, R.string.video)
    }

    override fun cameraOptionsFor(requestKey: String): List<CameraOption> =
        if (requestKey == CaptureOptions.KEY_VIDEO_PROFILE) {
            CaptureOptions.videoProfiles(requireContext(), engine)
        } else {
            emptyList()
        }

    override fun onCameraOptionPicked(requestKey: String, optionId: String) {
        if (requestKey != CaptureOptions.KEY_VIDEO_PROFILE) return
        // The sheet can outlive the moment it opened in: a clip started behind it must not
        // have the session pulled out from under it.
        if (engine?.recordingState != CameraEngine.RecordingState.IDLE) {
            showStatus(R.string.locked_while_recording)
            return
        }
        CaptureSettings.setVideoProfile(requireContext(), VideoProfile.of(optionId))
        engine?.start(CameraEngine.Mode.VIDEO)
        updateQualityChip()
    }

    /** Shows what the recorder actually resolved, not just what was asked for. */
    private fun updateQualityChip() {
        val binding = binding ?: return
        val profile = CaptureSettings.videoProfile(requireContext())
        val resolution = engine?.videoResolution()
        val range = engine?.appliedFrameRateRange
        binding.btnQuality.text = when {
            resolution == null -> getString(profile.label)
            range != null -> getString(
                R.string.video_quality_chip,
                shortName(resolution.height),
                range.upper
            )
            else -> shortName(resolution.height)
        }
    }

    /** Resolution shorthand; these are technical names and stay the same in every locale. */
    private fun shortName(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        else -> height.toString() + "p"
    }

    private fun toggleStabilization() {
        val engine = engine ?: return
        if (!engine.isStabilizationSupported()) {
            showStatus(R.string.eis_unsupported)
            return
        }
        engine.setStabilizationEnabled(!engine.stabilizationEnabled)
        updateStabilizationChip()
    }

    private fun updateStabilizationChip() {
        val binding = binding ?: return
        val engine = engine ?: return
        binding.btnEis.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (engine.stabilizationEnabled) R.color.dc_accent else R.color.dc_text_80
            )
        )
        val available = engine.isStabilizationSupported() &&
            engine.recordingState == CameraEngine.RecordingState.IDLE
        binding.btnEis.alpha = if (available) 1f else DISABLED_ALPHA
    }

    /** One place for the transient line over the preview, so none of them can stick. */
    private fun showStatus(messageId: Int, durationMs: Long = STATUS_MS) {
        val binding = binding ?: return
        val status = binding.tvCameraStatus
        status.visibility = View.VISIBLE
        status.setText(messageId)
        status.removeCallbacks(hideStatus)
        status.postDelayed(hideStatus, durationMs)
    }

    private val hideStatus = Runnable { binding?.tvCameraStatus?.visibility = View.GONE }

    private fun formatDuration(seconds: Long): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    private companion object {
        const val CHIP_SETTLE_MS = 400L
        const val STATUS_MS = 1_800L
        const val AUDIO_NOTICE_MS = 2_200L

        /** How long the pinch readout stays up once the fingers have left. */
        const val ZOOM_READOUT_MS = 900L

        /** What a control that the camera cannot honour right now looks like. */
        const val DISABLED_ALPHA = 0.4f

        /** Far enough in that auto-exposure has settled, near enough to be the same shot. */
        const val VIDEO_THUMB_MS = 600L
    }
}
