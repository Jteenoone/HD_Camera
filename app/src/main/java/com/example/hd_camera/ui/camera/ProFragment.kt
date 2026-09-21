package com.example.hd_camera.ui.camera

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hd_camera.R
import com.example.hd_camera.camera.CameraEngine
import com.example.hd_camera.camera.ManualControls
import com.example.hd_camera.camera.PhotoCapture
import com.example.hd_camera.camera.SensorCapabilities
import com.example.hd_camera.camera.WhiteBalance
import com.example.hd_camera.data.CaptureFormat
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.databinding.FragmentProBinding
import com.example.hd_camera.ui.applySystemBarPadding
import com.example.hd_camera.ui.navigateBack
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Screen 06 · Pro. The dial edits whichever parameter chip is selected and the values go
 * to the sensor through Camera2 interop; the histogram is computed from the preview stream.
 */
class ProFragment : Fragment(R.layout.fragment_pro), ShutterKeyHandler {

    private enum class Parameter { ISO, SHUTTER, WB, EV, FOCUS }

    private var binding: FragmentProBinding? = null
    private var engine: CameraEngine? = null
    private var capabilities: SensorCapabilities? = null

    private var selected = Parameter.ISO
    private var controls = ManualControls()
    private var evIndex = 0
    private var capturing = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentProBinding.bind(view).also { this.binding = it }
        binding.topBar.applySystemBarPadding(top = true)
        binding.bottomBar.applySystemBarPadding(bottom = true)

        val engine = CameraEngine(requireContext(), viewLifecycleOwner, binding.previewView)
            .also { this.engine = it }

        engine.frameAnalyzer = { image -> publishHistogram(image) }
        engine.onCameraError = { error ->
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.camera_unavailable)
            error.printStackTrace()
        }
        engine.onCameraReady = {
            binding.tvCameraStatus.visibility = View.GONE
            capabilities = engine.sensorCapabilities()
            bindIsoLegend()
            applyControls()
            updateChips()
            updateRawBadge()
        }
        engine.start(CameraEngine.Mode.PHOTO)

        bindParameterChips()
        bindDial()

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnFlip.setOnClickListener {
            engine.switchLens()
            capabilities = null
        }
        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnRaw.setOnClickListener { toggleRaw() }
        updateRawBadge()
    }

    override fun onDestroyView() {
        engine?.release()
        engine = null
        binding = null
        super.onDestroyView()
    }

    // ── Parameter chips ────────────────────────────────────────────────────

    private fun bindParameterChips() {
        val binding = binding ?: return
        val chips = mapOf(
            Parameter.ISO to binding.chipIso,
            Parameter.SHUTTER to binding.chipShutter,
            Parameter.WB to binding.chipWb,
            Parameter.EV to binding.chipEv,
            Parameter.FOCUS to binding.chipFocus
        )
        chips.forEach { (parameter, chip) ->
            chip.setOnClickListener {
                if (selected == parameter) {
                    // A second tap on the selected chip returns it to auto.
                    resetToAuto(parameter)
                } else {
                    selected = parameter
                }
                updateChips()
                applyControls()
            }
        }
        updateChips()
    }

    private fun resetToAuto(parameter: Parameter) {
        controls = when (parameter) {
            Parameter.ISO -> controls.copy(iso = null)
            Parameter.SHUTTER -> controls.copy(exposureTimeNanos = null)
            Parameter.WB -> controls.copy(whiteBalance = WhiteBalance.AUTO)
            Parameter.FOCUS -> controls.copy(manualFocusDistance = null)
            Parameter.EV -> {
                evIndex = 0
                engine?.setExposureCompensation(0)
                controls
            }
        }
    }

    private fun updateChips() {
        val binding = binding ?: return
        val entries = listOf(
            Triple(Parameter.ISO, binding.chipIso, binding.tvIsoChipValue),
            Triple(Parameter.SHUTTER, binding.chipShutter, binding.tvShutterValue),
            Triple(Parameter.WB, binding.chipWb, binding.tvWbValue),
            Triple(Parameter.EV, binding.chipEv, binding.tvEvValue),
            Triple(Parameter.FOCUS, binding.chipFocus, binding.tvFocusValue)
        )
        entries.forEach { (parameter, chip, value) ->
            val active = parameter == selected
            chip.setBackgroundResource(
                if (active) R.drawable.bg_pro_chip_active else R.drawable.bg_pro_chip
            )
            tintChip(chip, active)
            value.text = displayValue(parameter)
        }

        binding.tvIsoValue.text = displayValue(selected)
        binding.tvParameterName.text = getString(parameterLabel(selected))
    }

    private fun tintChip(chip: LinearLayout, active: Boolean) {
        val labelColour = ContextCompat.getColor(
            requireContext(),
            if (active) R.color.dc_on_accent else R.color.dc_text_dim
        )
        val valueColour = ContextCompat.getColor(
            requireContext(),
            if (active) R.color.dc_on_accent else R.color.dc_text
        )
        (chip.getChildAt(0) as? TextView)?.setTextColor(labelColour)
        (chip.getChildAt(1) as? TextView)?.setTextColor(valueColour)
    }

    private fun parameterLabel(parameter: Parameter): Int = when (parameter) {
        Parameter.ISO -> R.string.iso
        Parameter.SHUTTER -> R.string.shutter
        Parameter.WB -> R.string.wb
        Parameter.EV -> R.string.ev
        Parameter.FOCUS -> R.string.focus
    }

    private fun displayValue(parameter: Parameter): String = when (parameter) {
        Parameter.ISO -> controls.iso?.toString() ?: AUTO
        Parameter.SHUTTER -> controls.exposureTimeNanos?.let(::formatShutter) ?: AUTO
        Parameter.WB -> controls.whiteBalance.label
        Parameter.EV -> formatEv()
        Parameter.FOCUS -> controls.manualFocusDistance?.let { distance ->
            if (distance <= 0f) "INF" else String.format("%.2fm", 1f / distance)
        } ?: "AF"
    }

    // ── The dial ───────────────────────────────────────────────────────────

    private fun bindDial() {
        val binding = binding ?: return
        binding.isoDial.position = 0.5f
        binding.isoDial.onPositionChanged = { position ->
            when (selected) {
                Parameter.ISO -> controls = controls.copy(iso = isoAt(position))
                Parameter.SHUTTER ->
                    controls = controls.copy(exposureTimeNanos = shutterAt(position))
                Parameter.WB -> {
                    val values = WhiteBalance.entries
                    val index = (position * (values.size - 1)).roundToInt()
                    controls = controls.copy(whiteBalance = values[index])
                }
                Parameter.EV -> {
                    val range = engine?.exposureCompensationRange()
                    if (range != null) {
                        val span = range.upper - range.lower
                        evIndex = range.lower + (position * span).roundToInt()
                        engine?.setExposureCompensation(evIndex)
                    }
                }
                Parameter.FOCUS -> {
                    val maxDiopters = capabilities?.minFocusDistance ?: 0f
                    controls = controls.copy(manualFocusDistance = position * maxDiopters)
                }
            }
            applyControls()
            updateChips()
        }
    }

    private fun applyControls() {
        val engine = engine ?: return
        if (controls.isFullyAuto) engine.clearManualControls() else engine.applyManualControls(controls)
    }

    private fun bindIsoLegend() {
        val binding = binding ?: return
        val range = capabilities?.isoRange ?: return
        binding.tvIsoMin.text = range.lower.toString()
        binding.tvIsoMid.text = isoAt(0.5f).toString()
        binding.tvIsoMax.text = range.upper.toString()
        if (capabilities?.supportsManualExposure == false) {
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.manual_unsupported)
        }
    }

    /** ISO runs on a log scale between the sensor's own limits. */
    private fun isoAt(position: Float): Int {
        val range = capabilities?.isoRange ?: return DEFAULT_ISO
        val low = range.lower.coerceAtLeast(1)
        val high = range.upper.coerceAtLeast(low + 1)
        val value = low * (high.toDouble() / low).pow(position.toDouble())
        return value.roundToInt().coerceIn(low, high)
    }

    private fun shutterAt(position: Float): Long {
        val range = capabilities?.exposureTimeRange ?: return 8_000_000L
        val low = range.lower.coerceAtLeast(1L)
        val high = range.upper.coerceAtLeast(low + 1L)
        val value = low * (high.toDouble() / low).pow(position.toDouble())
        return value.roundToLong().coerceIn(low, high)
    }

    private fun formatShutter(nanos: Long): String {
        val seconds = nanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format("%.1fs", seconds)
        } else {
            "1/" + (1.0 / seconds).roundToInt()
        }
    }

    private fun formatEv(): String {
        val step = engine?.exposureCompensationStep() ?: 0.0
        val value = evIndex * step
        return String.format("%+.1f", value)
    }

    // ── RAW ────────────────────────────────────────────────────────────────

    private fun toggleRaw() {
        val engine = engine ?: return
        val binding = binding ?: return
        if (!engine.rawSupported()) {
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.raw_unsupported)
            binding.tvCameraStatus.postDelayed({
                binding.tvCameraStatus.visibility = View.GONE
            }, 1_800)
            return
        }
        val next = if (CaptureSettings.format(requireContext()) == CaptureFormat.JPEG_RAW) {
            CaptureFormat.JPEG
        } else {
            CaptureFormat.JPEG_RAW
        }
        CaptureSettings.setFormat(requireContext(), next)
        engine.start(CameraEngine.Mode.PHOTO)
        updateRawBadge()
    }

    private fun updateRawBadge() {
        val binding = binding ?: return
        // The badge must show what this camera can really do, not just what is configured.
        val supported = engine?.rawSupported() == true
        val rawOn = supported && CaptureSettings.format(requireContext()) == CaptureFormat.JPEG_RAW
        binding.btnRaw.alpha = if (supported) 1f else 0.4f
        binding.btnRaw.setBackgroundResource(
            if (rawOn) R.drawable.bg_pro_chip_active else R.drawable.bg_square_button_62
        )
        binding.btnRaw.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (rawOn) R.color.dc_on_accent else R.color.dc_text
            )
        )
        binding.tvProBadge.setText(if (rawOn) R.string.pro_raw else R.string.pro_only)
    }

    // ── Capture and histogram ──────────────────────────────────────────────

    private fun takePhoto() {
        val engine = engine ?: return
        val binding = binding ?: return
        if (capturing) return
        capturing = true
        binding.btnShutter.playShutterFeedback()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = PhotoCapture.capture(requireContext(), engine)
            if (result is PhotoCapture.Result.Failed) {
                binding.tvCameraStatus.visibility = View.VISIBLE
                binding.tvCameraStatus.text = getString(R.string.capture_failed)
                result.error.printStackTrace()
            }
            capturing = false
        }
    }

    override fun onShutterKey(): Boolean {
        takePhoto()
        return true
    }

    /** Luma histogram, sampled from the Y plane of the preview frame. */
    private fun publishHistogram(image: ImageProxy) {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val counts = IntArray(HISTOGRAM_BINS)
        var total = 0

        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val index = y * rowStride + x
                if (index < buffer.limit()) {
                    val luma = buffer.get(index).toInt() and 0xFF
                    counts[luma * HISTOGRAM_BINS / 256]++
                    total++
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        if (total == 0) return

        val peak = counts.max().coerceAtLeast(1)
        val normalised = FloatArray(HISTOGRAM_BINS) { counts[it].toFloat() / peak }
        binding?.histogram?.post { binding?.histogram?.submit(normalised) }
    }

    private companion object {
        const val AUTO = "AUTO"
        const val DEFAULT_ISO = 400
        const val HISTOGRAM_BINS = 32
        const val SAMPLE_STEP = 8
    }
}
