package com.example.hd_camera.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hd_camera.R
import com.example.hd_camera.camera.CameraEngine
import com.example.hd_camera.camera.TimeLapseRecorder
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.data.VideoProfile
import com.example.hd_camera.databinding.FragmentVideoBinding
import com.example.hd_camera.media.SpeedRemuxer
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.navigateBack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.log10

/**
 * Screen 08 · Video. Three capture modes share the screen: straight recording, slow motion
 * (recorded fast, then re-timed) and time-lapse (a still a second, encoded back into a clip).
 */
class VideoFragment : Fragment(R.layout.fragment_video), ShutterKeyHandler {

    private enum class Mode { NORMAL, SLOW_MO, TIME_LAPSE }

    private var binding: FragmentVideoBinding? = null
    private var engine: CameraEngine? = null
    private var mode = Mode.NORMAL

    private var timeLapse: TimeLapseRecorder? = null
    private var timeLapseJob: Job? = null
    private var busy = false

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
            }, 400L)
        }
        timeLapse = TimeLapseRecorder(requireContext())
        applyMode(Mode.NORMAL, restart = true)

        binding.tvRecTime.text = formatDuration(0)
        setRecordingChrome(recording = false)

        bindModeStrip()

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnQuality.setOnClickListener { cycleProfile() }
        binding.btnEis.setOnClickListener { toggleStabilization() }
        binding.btnFlip.setOnClickListener { engine.switchLens() }
    }

    // ── Modes ────────────────────────

    private fun bindModeStrip() {
        val binding = binding ?: return
        binding.modeRow.bindCaptureModes(
            modes = listOf(
                CaptureMode(R.string.mode_photos) { navigateBack() },
                CaptureMode(R.string.mode_video) { applyMode(Mode.NORMAL) },
                CaptureMode(R.string.mode_slow_mo) { applyMode(Mode.SLOW_MO) },
                CaptureMode(R.string.mode_time_lapse) { applyMode(Mode.TIME_LAPSE) }
            ),
            activeIndex = when (mode) {
                Mode.NORMAL -> 1
                Mode.SLOW_MO -> 2
                Mode.TIME_LAPSE -> 3
            },
            gapDp = 22,
            textSizeSp = 13f,
            letterSpacing = 0.1f,
            dot = R.drawable.bg_mode_dot_record
        )
    }

    private fun applyMode(next: Mode, restart: Boolean = false) {
        if (busy) return
        if (mode == next && !restart) return
        stopEverything()
        mode = next

        val engine = engine ?: return
        // Only time-lapse wants frames handed to it.
        engine.frameAnalyzer = null
        engine.analysisUsesRgba = false
        engine.allowRawCapture = true

        when (next) {
            Mode.NORMAL -> engine.start(CameraEngine.Mode.VIDEO)

            Mode.SLOW_MO -> {
                // Shoot as fast as the camera will hold; how far the clip can be slowed is
                // then decided from the rate it actually managed.
                CaptureSettings.setVideoProfile(requireContext(), VideoProfile.FHD_60)
                engine.start(CameraEngine.Mode.VIDEO)
            }

            Mode.TIME_LAPSE -> {
                // The clip is built from preview frames, so no still pipeline is needed.
                engine.analysisUsesRgba = true
                engine.allowRawCapture = false
                engine.frameAnalyzer = { image -> timeLapse?.offer(image.toBitmap()) }
                engine.start(CameraEngine.Mode.PHOTO)
            }
        }
        binding?.tvRecTime?.text = formatDuration(0)
        updateQualityChip()
        bindModeStrip()
    }

    private fun stopEverything() {
        timeLapseJob?.cancel()
        timeLapseJob = null
        timeLapse?.cancel()
        engine?.stopRecording()
        setRecordingChrome(recording = false)
    }

    override fun onDestroyView() {
        timeLapseJob?.cancel()
        timeLapse?.cancel()
        engine?.release()
        engine = null
        binding = null
        super.onDestroyView()
    }

    // ── Recording ──────────────────────────────────────────────────────────

    private fun toggleRecording() {
        if (busy) return
        if (mode == Mode.TIME_LAPSE) {
            if (timeLapseJob != null) stopTimeLapse() else startTimeLapse()
            return
        }

        val engine = engine ?: return
        if (engine.isRecording) {
            engine.stopRecording()
            return
        }
        val started = engine.startRecording(
            withAudio = micGranted() && mode == Mode.NORMAL
        ) { event ->
            onRecordEvent(event)
        }
        if (!started) {
            val binding = binding ?: return
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.recording_failed)
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        val binding = binding ?: return
        when (event) {
            is VideoRecordEvent.Start -> setRecordingChrome(recording = true)

            is VideoRecordEvent.Status -> {
                binding.tvRecTime.text =
                    formatDuration(event.recordingStats.recordedDurationNanos / 1_000_000_000L)
                updateAudioMeter(event.recordingStats.audioStats.audioAmplitude)
            }

            is VideoRecordEvent.Finalize -> {
                setRecordingChrome(recording = false)
                binding.tvRecTime.text = formatDuration(
                    event.recordingStats.recordedDurationNanos / 1_000_000_000L
                )
                if (event.hasError()) {
                    binding.tvCameraStatus.visibility = View.VISIBLE
                    binding.tvCameraStatus.text = getString(R.string.recording_failed)
                } else if (mode == Mode.SLOW_MO) {
                    retimeToSlowMotion(event.outputResults.outputUri)
                }
            }

            else -> Unit
        }
    }

    /**
     * Stretches the finished clip and drops the straight-speed original. How far it can be
     * stretched depends on the rate the camera really held, which is measured from the file.
     */
    private fun retimeToSlowMotion(source: Uri) {
        val binding = binding ?: return
        busy = true
        binding.tvCameraStatus.visibility = View.VISIBLE
        binding.tvCameraStatus.setText(R.string.processing)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = SpeedRemuxer.slowDown(requireContext(), source, PLAYBACK_FPS)
            when {
                result.uri != null && result.factor > 1f -> {
                    SpeedRemuxer.discard(requireContext(), source)
                    binding.tvCameraStatus.text = getString(
                        R.string.slow_motion_saved_at,
                        result.factor,
                        result.sourceFps.toInt()
                    )
                }

                result.uri != null -> binding.tvCameraStatus.text = getString(
                    R.string.slow_motion_too_slow,
                    result.sourceFps.toInt()
                )

                else -> binding.tvCameraStatus.setText(R.string.recording_failed)
            }
            busy = false
            binding.tvCameraStatus.postDelayed({
                binding.tvCameraStatus.visibility = View.GONE
            }, 2_600)
        }
    }

    // ── Time-lapse ───────────────────

    private fun startTimeLapse() {
        val recorder = timeLapse ?: return
        recorder.start()
        setRecordingChrome(recording = true)

        timeLapseJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && recorder.running) {
                recorder.captureFrame()
                binding?.tvRecTime?.text = getString(R.string.frames, recorder.frameCount)
                delay(TimeLapseRecorder.DEFAULT_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopTimeLapse() {
        val recorder = timeLapse ?: return
        val binding = binding ?: return
        timeLapseJob?.cancel()
        timeLapseJob = null
        setRecordingChrome(recording = false)

        busy = true
        binding.tvCameraStatus.visibility = View.VISIBLE
        binding.tvCameraStatus.setText(R.string.processing)

        viewLifecycleOwner.lifecycleScope.launch {
            val uri = recorder.finish()
            binding.tvCameraStatus.setText(
                if (uri != null) R.string.time_lapse_saved else R.string.recording_failed
            )
            busy = false
            binding.tvCameraStatus.postDelayed({
                binding.tvCameraStatus.visibility = View.GONE
            }, 1_800)
        }
    }

    private fun setRecordingChrome(recording: Boolean) {
        val binding = binding ?: return
        binding.recBadge.alpha = if (recording) 1f else 0.45f
        binding.recIndicator.visibility = if (recording) View.VISIBLE else View.INVISIBLE
        // Square while recording, circle when idle — the usual record button behaviour.
        binding.recordInner.setBackgroundResource(
            if (recording) R.drawable.bg_record_square else R.drawable.bg_record_circle
        )
        val size = resources.getDimensionPixelSize(
            if (recording) R.dimen.record_inner_recording else R.dimen.record_inner_idle
        )
        binding.recordInner.layoutParams = binding.recordInner.layoutParams.apply {
            width = size
            height = size
        }
    }

    /** Amplitude arrives as 0..1; the meter is drawn on a log scale like a real VU. */
    private fun updateAudioMeter(amplitude: Double) {
        val binding = binding ?: return
        val meter = binding.audioMeter
        val level = if (amplitude <= 0.0) {
            0f
        } else {
            ((20.0 * log10(amplitude) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        }
        val lit = (level * meter.childCount).toInt()
        for (index in 0 until meter.childCount) {
            val bar = meter.getChildAt(index)
            bar.setBackgroundResource(
                if (index < lit) R.drawable.bg_audio_bar_on else R.drawable.bg_audio_bar_off
            )
        }
    }

    private fun micGranted(): Boolean = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // ── Quality, stabilization, slow motion ────────────────────────────────

    private fun cycleProfile() {
        if (mode != Mode.NORMAL) return
        val profiles = VideoProfile.entries
        val current = CaptureSettings.videoProfile(requireContext())
        val next = profiles[(current.ordinal + 1) % profiles.size]
        CaptureSettings.setVideoProfile(requireContext(), next)
        engine?.start(CameraEngine.Mode.VIDEO)
        updateQualityChip()
    }

    /** Shows what the recorder actually resolved, not just what was asked for. */
    private fun updateQualityChip() {
        val binding = binding ?: return
        if (mode == Mode.TIME_LAPSE) {
            binding.btnQuality.setText(R.string.time_lapse_rate)
            return
        }
        val profile = CaptureSettings.videoProfile(requireContext())
        val resolution = engine?.videoResolution()
        val range = engine?.appliedFrameRateRange
        val label = when {
            resolution == null -> profile.label
            range != null -> shortName(resolution.height) + " · " + range.upper + "fps"
            else -> shortName(resolution.height)
        }
        binding.btnQuality.text = if (mode == Mode.SLOW_MO) label + " · ½×" else label
    }

    private fun shortName(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        else -> height.toString() + "p"
    }

    private fun toggleStabilization() {
        val engine = engine ?: return
        val binding = binding ?: return
        if (!engine.isStabilizationSupported()) {
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.eis_unsupported)
            binding.tvCameraStatus.postDelayed({
                binding.tvCameraStatus.visibility = View.GONE
            }, 1_800)
            return
        }
        engine.setStabilizationEnabled(!engine.stabilizationEnabled)
        updateStabilizationChip()
    }

    private fun updateStabilizationChip() {
        val binding = binding ?: return
        val engine = engine ?: return
        val on = engine.stabilizationEnabled
        binding.btnEis.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (on) R.color.dc_accent else R.color.dc_text_faint
            )
        )
        binding.btnEis.alpha = if (engine.isStabilizationSupported()) 1f else 0.4f
    }

    override fun onShutterKey(): Boolean {
        toggleRecording()
        return true
    }

    private fun formatDuration(seconds: Long): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    private companion object {
        /** Slow-motion clips are retimed to play at this rate. */
        const val PLAYBACK_FPS = 30f
    }
}
