package com.digitalcamerahd.camera4k.selfiecamera.ui.camera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.digitalcamerahd.camera4k.selfiecamera.R
import com.digitalcamerahd.camera4k.selfiecamera.camera.CameraEngine
import com.digitalcamerahd.camera4k.selfiecamera.camera.ExposureScale
import com.digitalcamerahd.camera4k.selfiecamera.camera.FocusAid
import com.digitalcamerahd.camera4k.selfiecamera.camera.ManualControls
import com.digitalcamerahd.camera4k.selfiecamera.camera.PhotoCapture
import com.digitalcamerahd.camera4k.selfiecamera.camera.SensorCapabilities
import com.digitalcamerahd.camera4k.selfiecamera.camera.WhiteBalance
import com.digitalcamerahd.camera4k.selfiecamera.data.CaptureFormat
import com.digitalcamerahd.camera4k.selfiecamera.data.CaptureSettings
import com.digitalcamerahd.camera4k.selfiecamera.databinding.FragmentProBinding
import com.digitalcamerahd.camera4k.selfiecamera.ui.AlwaysDark
import com.digitalcamerahd.camera4k.selfiecamera.ui.applySystemBarPadding
import com.digitalcamerahd.camera4k.selfiecamera.ui.darkInflater
import com.digitalcamerahd.camera4k.selfiecamera.ui.navigateBack
import com.digitalcamerahd.camera4k.selfiecamera.ui.themedContext
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Screen 06 · Pro.
 *
 * One dial edits whichever parameter is selected, and the values reach the sensor through
 * Camera2 interop. Every limit on this screen — the ends of the ISO scale, how long the
 * shutter will stay open, which white-balance presets exist, how far the lens focuses —
 * comes from the camera's own characteristics. A parameter the camera does not offer is
 * shown greyed with the reason rather than left as a control that does nothing.
 */
class ProFragment : Fragment(R.layout.fragment_pro), ShutterKeyHandler, AlwaysDark {

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        darkInflater(super.onGetLayoutInflater(savedInstanceState))

    private enum class Parameter { ISO, SHUTTER, WB, EV, FOCUS }

    private var binding: FragmentProBinding? = null
    private var engine: CameraEngine? = null
    private var capabilities: SensorCapabilities? = null

    private var selected = Parameter.ISO
    private var controls = ManualControls()
    private var evIndex = 0
    private var capturing = false

    /**
     * Which parameters the user has actually set. ISO and shutter cannot be half-manual,
     * so setting one seeds the other; this is how the seeded one is told apart from a
     * value that was chosen, and so knows to go back to automatic with its partner.
     */
    private val userSet = mutableSetOf<Parameter>()

    /** Peaking and zebra, computed from the same preview frames as the histogram. */
    private val focusAid = FocusAid()

    /** Only the presets this camera accepts; always at least Auto. */
    private var whiteBalances: List<WhiteBalance> = listOf(WhiteBalance.AUTO)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentProBinding.bind(view).also { this.binding = it }
        binding.topBar.applySystemBarPadding(top = true)
        binding.bottomBar.applySystemBarPadding(bottom = true)

        val engine = CameraEngine(requireContext(), viewLifecycleOwner, binding.previewView)
            .also { this.engine = it }

        engine.frameAnalyzer = { image -> analyseFrame(image) }
        engine.onCameraError = { error ->
            binding.tvCameraStatus.visibility = View.VISIBLE
            binding.tvCameraStatus.text = getString(R.string.camera_unavailable)
            error.printStackTrace()
        }
        engine.onCameraReady = {
            binding.tvCameraStatus.visibility = View.GONE
            readCapabilities()
            // Exposure state is not always populated the instant the session reports
            // ready, and a chip greyed out on a camera that does offer the parameter
            // would stay greyed until something else happened to repaint it.
            binding.previewView.postDelayed({ readCapabilities() }, CAPABILITY_SETTLE_MS)
        }
        engine.start(CameraEngine.Mode.PHOTO)

        bindParameterChips()
        bindDial()

        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnFlip.setOnClickListener {
            // The lens about to be bound has its own limits, so everything on this screen
            // is unknown again until the new session reports them.
            capabilities = null
            engine.switchLens()
        }
        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnRaw.setOnClickListener { toggleRaw() }
        binding.btnAuto.setOnClickListener { resetToAuto(selected) }
        binding.btnResetAll.setOnClickListener { resetAll() }
        binding.btnLock.setOnClickListener { toggleExposureLock() }
        binding.btnPeaking.setOnClickListener {
            focusAid.peakingEnabled = !focusAid.peakingEnabled
            onFocusAidChanged()
        }
        binding.btnZebra.setOnClickListener {
            focusAid.zebraEnabled = !focusAid.zebraEnabled
            onFocusAidChanged()
        }

        updateRawBadge()
        updateToggles()
    }

    override fun onDestroyView() {
        engine?.release()
        engine = null
        binding = null
        super.onDestroyView()
    }

    // ── Capabilities ───────────────────────────────────────────────────────

    private fun readCapabilities() {
        val engine = engine ?: return
        val caps = engine.sensorCapabilities().also { capabilities = it }
        whiteBalances = WhiteBalance.availableIn(caps.awbModes)

        // A parameter this lens does not offer must not be left as the selected one, or
        // the dial would be editing something the camera will ignore.
        if (!isSupported(selected)) {
            selected = Parameter.entries.firstOrNull { isSupported(it) } ?: Parameter.EV
        }
        if (!caps.supportsManualExposure) {
            showStatus(R.string.manual_unsupported)
        }

        syncDialToSelection()
        applyControls()
        updateChips()
        updateDialLegend()
        updateRawBadge()
        updateToggles()
    }

    /** Before a session is up nothing is known, so nothing is ruled out. */
    private fun isSupported(parameter: Parameter): Boolean {
        val caps = capabilities ?: return true
        return when (parameter) {
            Parameter.ISO, Parameter.SHUTTER -> caps.supportsManualExposure
            // One preset is not a choice; a camera with only Auto has no WB control.
            Parameter.WB -> whiteBalances.size > 1
            Parameter.EV -> engine?.supportsExposureCompensation() ?: true
            Parameter.FOCUS -> caps.supportsManualFocus
        }
    }

    @StringRes
    private fun unsupportedReason(parameter: Parameter): Int = when (parameter) {
        Parameter.ISO, Parameter.SHUTTER -> R.string.manual_unsupported
        Parameter.WB -> R.string.wb_presets_unsupported
        Parameter.EV -> R.string.ev_unsupported
        Parameter.FOCUS -> R.string.manual_focus_unsupported
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
                if (!isSupported(parameter)) {
                    showStatus(unsupportedReason(parameter))
                    return@setOnClickListener
                }
                selected = parameter
                syncDialToSelection()
                updateChips()
                updateDialLegend()
                updateToggles()
            }
        }
        updateChips()
    }

    /** Puts one parameter back on automatic, leaving the others as they are. */
    private fun resetToAuto(parameter: Parameter) {
        if (!isSupported(parameter)) {
            showStatus(unsupportedReason(parameter))
            return
        }
        userSet -= parameter
        controls = when (parameter) {
            // The half that was only seeded goes back to automatic with the half that was
            // chosen; a shutter the user set themselves stays where they put it.
            Parameter.ISO -> if (Parameter.SHUTTER in userSet) {
                controls.copy(iso = engine?.lastSensorIso)
            } else {
                controls.copy(iso = null, exposureTimeNanos = null)
            }

            Parameter.SHUTTER -> if (Parameter.ISO in userSet) {
                controls.copy(exposureTimeNanos = engine?.lastSensorExposureNanos)
            } else {
                controls.copy(iso = null, exposureTimeNanos = null)
            }

            Parameter.WB -> controls.copy(whiteBalance = WhiteBalance.AUTO)
            Parameter.FOCUS -> controls.copy(manualFocusDistance = null)
            Parameter.EV -> {
                evIndex = 0
                engine?.setExposureCompensation(0)
                controls
            }
        }
        applyControls()
        syncDialToSelection()
        updateChips()
        updateDialLegend()
    }

    /** Everything back to automatic, including the lock. */
    private fun resetAll() {
        controls = ManualControls()
        userSet.clear()
        evIndex = 0
        engine?.setExposureCompensation(0)
        engine?.lockFocusAndMetering(false)
        applyControls()
        syncDialToSelection()
        updateChips()
        updateDialLegend()
        updateToggles()
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
            val supported = isSupported(parameter)
            val active = parameter == selected && supported
            chip.setBackgroundResource(
                if (active) R.drawable.bg_pro_chip_active else R.drawable.bg_pro_chip
            )
            tintChip(chip, active)
            chip.alpha = if (supported) 1f else DISABLED_ALPHA
            chip.isSelected = active
            value.text = displayValue(parameter)
        }

        binding.tvIsoValue.text = displayValue(selected)
        binding.tvParameterName.text = getString(parameterLabel(selected))
    }

    private fun tintChip(chip: LinearLayout, active: Boolean) {
        val labelColour = ContextCompat.getColor(
            themedContext,
            if (active) R.color.dc_on_accent else R.color.dc_text_dim
        )
        val valueColour = ContextCompat.getColor(
            themedContext,
            if (active) R.color.dc_on_accent else R.color.dc_text
        )
        (chip.getChildAt(0) as? TextView)?.setTextColor(labelColour)
        (chip.getChildAt(1) as? TextView)?.setTextColor(valueColour)
    }

    @StringRes
    private fun parameterLabel(parameter: Parameter): Int = when (parameter) {
        Parameter.ISO -> R.string.iso
        Parameter.SHUTTER -> R.string.shutter
        Parameter.WB -> R.string.wb
        Parameter.EV -> R.string.ev
        Parameter.FOCUS -> R.string.focus
    }

    private fun displayValue(parameter: Parameter): String = when (parameter) {
        Parameter.ISO -> controls.iso?.toString() ?: getString(R.string.wb_auto)
        Parameter.SHUTTER ->
            controls.exposureTimeNanos?.let(::formatShutter) ?: getString(R.string.wb_auto)
        Parameter.WB -> getString(controls.whiteBalance.label)
        Parameter.EV -> formatEv(evIndex)
        Parameter.FOCUS -> controls.manualFocusDistance?.let(::formatFocus)
            ?: getString(R.string.focus_value)
    }

    // ── The dial ───────────────────────────────────────────────────────────

    private fun bindDial() {
        val binding = binding ?: return
        binding.isoDial.position = 0.5f
        binding.isoDial.onPositionChanged = { position ->
            if (isSupported(selected)) {
                userSet += selected
                applyDial(position)
                applyControls()
                updateChips()
            }
        }
    }

    private fun applyDial(position: Float) {
        val caps = capabilities
        when (selected) {
            Parameter.ISO -> {
                val range = caps?.isoRange ?: return
                controls = controls.copy(
                    iso = ExposureScale.isoAt(position, range.lower, range.upper),
                    // Auto-exposure goes off for both halves at once, so the shutter is
                    // handed what the sensor was just using rather than being left to the
                    // driver — which held whatever it liked and took the picture to black.
                    exposureTimeNanos = controls.exposureTimeNanos
                        ?: engine?.lastSensorExposureNanos
                )
            }

            Parameter.SHUTTER -> {
                val range = caps?.exposureTimeRange ?: return
                controls = controls.copy(
                    exposureTimeNanos =
                        ExposureScale.shutterAt(position, range.lower, range.upper),
                    iso = controls.iso ?: engine?.lastSensorIso
                )
            }

            Parameter.WB -> {
                val index = (position * (whiteBalances.size - 1)).roundToInt()
                controls = controls.copy(
                    whiteBalance = whiteBalances.getOrElse(index) { WhiteBalance.AUTO }
                )
            }

            Parameter.EV -> {
                val range = engine?.exposureCompensationRange() ?: return
                evIndex = ExposureScale.evIndexAt(position, range.lower, range.upper)
                engine?.setExposureCompensation(evIndex)
            }

            Parameter.FOCUS -> {
                val closest = caps?.minFocusDistance ?: return
                controls = controls.copy(
                    manualFocusDistance = ExposureScale.focusDioptresAt(position, closest)
                )
            }
        }
    }

    /**
     * Puts the marker where the selected parameter already stands. Without this the dial
     * kept whatever position the last parameter left it at, so switching from ISO to
     * shutter showed a marker that had nothing to do with the shutter speed in use.
     */
    private fun syncDialToSelection() {
        val binding = binding ?: return
        val caps = capabilities
        binding.isoDial.position = when (selected) {
            Parameter.ISO -> controls.iso?.let { iso ->
                val range = caps?.isoRange ?: return@let null
                ExposureScale.positionOfIso(iso, range.lower, range.upper)
            } ?: DEFAULT_DIAL_POSITION

            Parameter.SHUTTER -> controls.exposureTimeNanos?.let { nanos ->
                val range = caps?.exposureTimeRange ?: return@let null
                ExposureScale.positionOfShutter(nanos, range.lower, range.upper)
            } ?: DEFAULT_DIAL_POSITION

            Parameter.WB -> {
                val index = whiteBalances.indexOf(controls.whiteBalance).coerceAtLeast(0)
                if (whiteBalances.size <= 1) 0f else index.toFloat() / (whiteBalances.size - 1)
            }

            Parameter.EV -> {
                val range = engine?.exposureCompensationRange()
                if (range == null) {
                    DEFAULT_DIAL_POSITION
                } else {
                    ExposureScale.positionOfEv(evIndex, range.lower, range.upper)
                }
            }

            Parameter.FOCUS -> ExposureScale.positionOfFocus(
                controls.manualFocusDistance ?: 0f,
                caps?.minFocusDistance ?: 0f
            )
        }
    }

    /** The three figures under the dial belong to whichever parameter it is driving. */
    private fun updateDialLegend() {
        val binding = binding ?: return
        val caps = capabilities
        val labels: Triple<String, String, String> = when (selected) {
            Parameter.ISO -> {
                val range = caps?.isoRange
                if (range == null) {
                    Triple("", "", "")
                } else {
                    Triple(
                        range.lower.toString(),
                        ExposureScale.isoAt(0.5f, range.lower, range.upper).toString(),
                        range.upper.toString()
                    )
                }
            }

            Parameter.SHUTTER -> {
                val range = caps?.exposureTimeRange
                if (range == null) {
                    Triple("", "", "")
                } else {
                    Triple(
                        formatShutter(range.lower),
                        formatShutter(ExposureScale.shutterAt(0.5f, range.lower, range.upper)),
                        formatShutter(range.upper)
                    )
                }
            }

            Parameter.WB -> Triple(
                getString(whiteBalances.first().label),
                "",
                getString(whiteBalances.last().label)
            )

            Parameter.EV -> {
                val range = engine?.exposureCompensationRange()
                if (range == null) {
                    Triple("", "", "")
                } else {
                    Triple(formatEv(range.lower), formatEv(0), formatEv(range.upper))
                }
            }

            Parameter.FOCUS -> Triple(
                getString(R.string.focus_infinity),
                "",
                formatFocus(caps?.minFocusDistance ?: 0f)
            )
        }
        binding.tvIsoMin.text = labels.first
        binding.tvIsoMid.text = labels.second
        binding.tvIsoMax.text = labels.third
    }

    private fun applyControls() {
        val engine = engine ?: return
        if (controls.isFullyAuto) {
            engine.clearManualControls()
        } else {
            engine.applyManualControls(controls)
        }
    }

    private fun formatShutter(nanos: Long): String = if (ExposureScale.isWholeSeconds(nanos)) {
        getString(R.string.shutter_seconds, ExposureScale.seconds(nanos))
    } else {
        getString(R.string.shutter_fraction, ExposureScale.shutterDenominator(nanos))
    }

    private fun formatEv(index: Int): String {
        val step = engine?.exposureCompensationStep() ?: 0.0
        return getString(R.string.ev_signed, ExposureScale.evOf(index, step))
    }

    private fun formatFocus(dioptres: Float): String = if (ExposureScale.isInfinity(dioptres)) {
        getString(R.string.focus_infinity)
    } else {
        getString(R.string.focus_distance_meters, ExposureScale.metres(dioptres))
    }

    // ── Lock and viewfinder aids ───────────────────────────────────────────

    /**
     * Holds focus and metering where they are. A manual exposure is already fixed, so the
     * lock only has anything to hold when ISO and shutter are on automatic — which is
     * exactly when the picture would otherwise drift as the scene changes.
     */
    private fun toggleExposureLock() {
        val caps = capabilities
        if (caps != null && !caps.supportsExposureLock) {
            showStatus(R.string.ae_lock_unsupported)
            return
        }
        val locked = !controls.exposureLocked
        controls = controls.copy(exposureLocked = locked)
        engine?.lockFocusAndMetering(locked)
        applyControls()
        updateToggles()
    }

    private fun onFocusAidChanged() {
        if (!focusAid.isActive) binding?.focusAids?.clear()
        updateToggles()
    }

    private fun updateToggles() {
        val binding = binding ?: return
        styleToggle(binding.btnPeaking, focusAid.peakingEnabled, available = true)
        styleToggle(binding.btnZebra, focusAid.zebraEnabled, available = true)
        styleToggle(
            binding.btnLock,
            controls.exposureLocked,
            available = capabilities?.supportsExposureLock ?: true
        )
        // Auto acts on the selected parameter, so it is only live when that one is.
        binding.btnAuto.alpha = if (isSupported(selected)) 1f else DISABLED_ALPHA
    }

    private fun styleToggle(view: TextView, active: Boolean, available: Boolean) {
        view.setBackgroundResource(
            if (active) R.drawable.bg_pro_chip_active else R.drawable.bg_pro_chip
        )
        view.setTextColor(
            ContextCompat.getColor(
                themedContext,
                if (active) R.color.dc_on_accent else R.color.dc_text
            )
        )
        view.alpha = if (available) 1f else DISABLED_ALPHA
        view.isSelected = active
    }

    // ── RAW ────────────────────────────────────────────────────────────────

    private fun toggleRaw() {
        val engine = engine ?: return
        // Before the session is up the capability query answers "no"; that is not the same
        // as the camera refusing RAW, and it used to make the button look dead on entry.
        if (engine.isReady && !engine.rawSupported()) {
            showStatus(R.string.raw_unsupported)
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
        val supported = engine?.let { !it.isReady || it.rawSupported() } ?: true
        val rawOn = supported && CaptureSettings.format(requireContext()) == CaptureFormat.JPEG_RAW
        binding.btnRaw.alpha = if (supported) 1f else DISABLED_ALPHA
        binding.btnRaw.setBackgroundResource(
            if (rawOn) R.drawable.bg_pro_chip_active else R.drawable.bg_square_button_62
        )
        binding.btnRaw.setTextColor(
            ContextCompat.getColor(
                themedContext,
                if (rawOn) R.color.dc_on_accent else R.color.dc_text
            )
        )
        binding.btnRaw.isSelected = rawOn
        binding.tvProBadge.setText(if (rawOn) R.string.pro_raw else R.string.pro_only)
    }

    // ── Capture ────────────────────────────────────────────────────────────

    private fun takePhoto() {
        val engine = engine ?: return
        val binding = binding ?: return
        if (capturing) return
        capturing = true
        binding.btnShutter.playShutterFeedback()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = PhotoCapture.capture(requireContext(), engine)
                if (result is PhotoCapture.Result.Failed) {
                    showStatus(R.string.capture_failed)
                    result.error.printStackTrace()
                }
            } finally {
                capturing = false
            }
        }
    }

    override fun onShutterKey(): Boolean {
        takePhoto()
        return true
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

    // ── Frame analysis ─────────────────────────────────────────────────────

    /**
     * Runs on the analysis thread. The histogram and both focus aids read the same luma
     * plane, so a frame is walked once however many of them are switched on.
     */
    private fun analyseFrame(image: ImageProxy) {
        val plane = image.planes.firstOrNull() ?: return
        publishHistogram(plane.buffer, image.width, image.height, plane.rowStride)
        if (!focusAid.isActive) return
        focusAid.analyze(
            luma = plane.buffer,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
            rotationDegrees = image.imageInfo.rotationDegrees,
            mirrored = engine?.lensFacing == CameraSelector.LENS_FACING_FRONT
        ) { pixels, maskWidth, maskHeight ->
            binding?.focusAids?.post {
                binding?.focusAids?.submit(pixels, maskWidth, maskHeight)
            }
        }
    }

    /** Luma histogram, sampled from the Y plane of the preview frame. */
    private fun publishHistogram(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int
    ) {
        val counts = IntArray(HISTOGRAM_BINS)
        var total = 0

        var y = 0
        while (y < height) {
            val row = y * rowStride
            var x = 0
            while (x < width) {
                val index = row + x
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
        const val HISTOGRAM_BINS = 32
        const val SAMPLE_STEP = 8
        const val STATUS_MS = 1_800L
        const val DISABLED_ALPHA = 0.4f

        /** Long enough for the camera to publish its exposure state. */
        const val CAPABILITY_SETTLE_MS = 400L

        /** Where the marker sits for a parameter that is still on automatic. */
        const val DEFAULT_DIAL_POSITION = 0.5f
    }
}
