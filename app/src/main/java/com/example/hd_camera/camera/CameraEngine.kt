package com.example.hd_camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.AspectRatio
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.hd_camera.data.CaptureFormat
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.data.VideoProfile
import com.example.hd_camera.media.MediaOutput
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the CameraX session for the four viewfinder screens: preview, stills, video and the
 * manual controls the Pro screen drives.
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {

    enum class Mode { PHOTO, VIDEO }

    /**
     * Where the recorder is. The Video screen draws three different button rows from this,
     * so "is there a Recording object" was no longer enough to tell them apart.
     */
    enum class RecordingState { IDLE, RECORDING, PAUSED, FINALIZING }

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var mode: Mode = Mode.PHOTO

    /**
     * HDR / Night / Portrait / Beauty from the design map onto CameraX extensions.
     * Falls back to a plain session when the device does not offer the mode.
     */
    var extensionMode: Int = ExtensionMode.NONE
        private set

    /** True when the current session really is running a vendor extension. */
    var usingVendorExtension: Boolean = false
        private set

    var imageCapture: ImageCapture? = null
        private set
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    var recordingState: RecordingState = RecordingState.IDLE
        private set

    /**
     * True when the current VIDEO session also holds a still pipeline. Not every camera can
     * run preview, recorder and still capture at once, so such a session falls back to plain
     * recording and the Video screen hides its snapshot button.
     */
    var snapshotSupported: Boolean = false
        private set

    /** False when the camera refused the requested frame rate and picked its own. */
    var frameRateApplied: Boolean = true
        private set

    /**
     * True when the still pipeline is set up to emit a DNG next to the JPEG. Such a capture
     * needs two sets of output options, so callers have to take the other takePhoto overload.
     */
    var rawCaptureActive: Boolean = false
        private set

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set
    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        private set

    /** The 4:3 / 16:9 chip on the Photo screen. */
    var aspectRatio: Int = AspectRatio.RATIO_4_3
        private set

    /**
     * Set when the user asks for a field of view wider than the main lens can reach. The
     * main camera bottoms out at 1x, so 0.5x means binding the ultra-wide lens instead of
     * asking for a zoom ratio the sensor will simply clamp away.
     */
    var usingWideLens: Boolean = false
        private set

    /** True once a camera is actually open, as opposed to merely requested. */
    val isReady: Boolean get() = provider != null && camera != null

    /** The EIS chip on the Video screen. */
    var stabilizationEnabled: Boolean = false
        private set

    /**
     * Set before [start] to receive preview frames — the Pro screen uses them to draw
     * a live histogram. Frames arrive on a background thread and must be closed by the engine.
     */
    var frameAnalyzer: ((ImageProxy) -> Unit)? = null

    /** The frame-rate range the recorder was actually configured with. */
    var appliedFrameRateRange: Range<Int>? = null
        private set

    /** Raised once the session is live, so screens can read the zoom range and capabilities. */
    var onCameraReady: ((Camera) -> Unit)? = null

    /** Raised when the camera cannot be opened at all: no permission, or no such device. */
    var onCameraError: ((Throwable) -> Unit)? = null

    val isRecording: Boolean get() = recording != null

    fun start(mode: Mode, extensionMode: Int = ExtensionMode.NONE) {
        this.mode = mode
        this.extensionMode = extensionMode
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val extensions = ExtensionsManager.getInstanceAsync(context, cameraProvider)
                extensions.addListener({
                    extensionsManager = try {
                        extensions.get()
                    } catch (error: Exception) {
                        null
                    }
                    bind()
                }, mainExecutor)
            } catch (error: Exception) {
                onCameraError?.invoke(error)
            }
        }, mainExecutor)
    }

    /** Switches the capture mode chip (HDR) or the mode strip entry (Night / Portrait). */
    fun setExtensionMode(mode: Int) {
        if (extensionMode == mode) return
        extensionMode = mode
        bind()
    }

    /**
     * Night and Portrait exist on most phones even without CameraX extensions: the Camera2
     * scene modes drive the same hardware behaviour, so the app uses them as a fallback
     * rather than telling the user the mode does not exist.
     */
    private fun sceneModeFor(mode: Int): Int? = when (mode) {
        ExtensionMode.NIGHT -> CaptureRequest.CONTROL_SCENE_MODE_NIGHT
        ExtensionMode.BOKEH -> CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT
        ExtensionMode.HDR -> CaptureRequest.CONTROL_SCENE_MODE_HDR
        ExtensionMode.FACE_RETOUCH -> CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY
        else -> null
    }

    private fun sceneModeAvailable(mode: Int): Boolean {
        val scene = sceneModeFor(mode) ?: return false
        return sensorCapabilities().sceneModes.contains(scene)
    }

    /** True when the mode can be delivered either way. */
    fun isModeSupported(mode: Int): Boolean =
        mode == ExtensionMode.NONE || isExtensionAvailable(mode) || sceneModeAvailable(mode)

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applySceneFallback() {
        val control = camera?.cameraControl ?: return
        val builder = CaptureRequestOptions.Builder()
        val scene = if (!usingVendorExtension) sceneModeFor(extensionMode) else null

        if (scene != null && sceneModeAvailable(extensionMode)) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
            )
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, scene)
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_MODE_AUTO
            )
        }
        Camera2CameraControl.from(control).setCaptureRequestOptions(builder.build())
    }

    fun isExtensionAvailable(mode: Int): Boolean {
        if (mode == ExtensionMode.NONE) return true
        val manager = extensionsManager ?: return false
        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        return try {
            manager.isExtensionAvailable(base, mode)
        } catch (error: Exception) {
            false
        }
    }

    /** True when a camera is now open. False means the session was left closed. */
    private fun bind(withTargetFrameRate: Boolean = true, withSnapshot: Boolean = true): Boolean {
        val provider = provider ?: return false
        provider.unbindAll()

        val baseSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .apply {
                if (usingWideLens) {
                    addCameraFilter { infos ->
                        val wide = infos.minByOrNull { intrinsicZoomOf(it) }
                        if (wide != null && intrinsicZoomOf(wide) < WIDE_LENS_THRESHOLD) {
                            listOf(wide)
                        } else {
                            infos
                        }
                    }
                }
            }
            .build()
        // Extensions only apply to still capture; the recorder runs on a plain session.
        usingVendorExtension = mode == Mode.PHOTO && extensionMode != ExtensionMode.NONE &&
            isExtensionAvailable(extensionMode)
        val selector = if (usingVendorExtension) {
            extensionsManager?.getExtensionEnabledCameraSelector(baseSelector, extensionMode)
                ?: baseSelector
        } else {
            baseSelector
        }
        val aspectRatioStrategy =
            AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectRatioStrategy)
                    .build()
            )
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val group = UseCaseGroup.Builder().addUseCase(preview)

        when (mode) {
            Mode.PHOTO -> {
                val builder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectRatioStrategy)
                CaptureSettings.photoResolution(context).target?.let { size ->
                    resolution.setResolutionStrategy(
                        ResolutionStrategy(
                            size,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                }
                builder.setResolutionSelector(resolution.build())
                rawCaptureActive = CaptureSettings.format(context) == CaptureFormat.JPEG_RAW &&
                    supportsRawCapture(provider, selector)
                if (rawCaptureActive) {
                    builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                }
                imageCapture = builder.build().also { group.addUseCase(it) }
                videoCapture = null

                // Extensions take over the pipeline, so the histogram only runs on a plain session.
                val analyzer = frameAnalyzer
                if (analyzer != null && extensionMode == ExtensionMode.NONE) {
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { image ->
                        try {
                            analyzer(image)
                        } finally {
                            image.close()
                        }
                    }
                    group.addUseCase(analysis)
                }
            }

            Mode.VIDEO -> {
                rawCaptureActive = false
                val profile = CaptureSettings.videoProfile(context)

                // A still taken while the recorder runs has to be cheap: MAXIMIZE_QUALITY
                // stalls the stream it shares the sensor with, so this one minimises latency.
                imageCapture = if (withSnapshot) {
                    ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
                        .setFlashMode(flashMode)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(
                                    AspectRatioStrategy(
                                        AspectRatio.RATIO_16_9,
                                        AspectRatioStrategy.FALLBACK_RULE_AUTO
                                    )
                                )
                                .build()
                        )
                        .build()
                        .also { group.addUseCase(it) }
                } else {
                    null
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            qualityPreferenceFrom(profile.toQuality()),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
                        )
                    )
                    .build()
                val videoBuilder = VideoCapture.Builder(recorder)
                    .setVideoStabilizationEnabled(stabilizationEnabled)

                // Asking for a rate the sensor does not publish is how the recorder ended up
                // free-running at whatever auto-exposure allowed, so pick from its own list.
                val range = if (withTargetFrameRate) {
                    supportedFrameRateRange(provider, selector, profile.fps)
                } else {
                    null
                }
                appliedFrameRateRange = range
                frameRateApplied = range != null
                if (range != null) videoBuilder.setTargetFrameRate(range)
                Log.d(
                    TAG,
                    "video fps: requested=" + profile.fps + " chosen=" + range +
                        " supported=" + supportedRangesOf(provider, selector)
                )
                videoCapture = videoBuilder.build().also { group.addUseCase(it) }
            }
        }

        return try {
            camera = provider.bindToLifecycle(lifecycleOwner, selector, group.build())
                .also {
                    snapshotSupported = mode == Mode.VIDEO && imageCapture != null
                    applySceneFallback()
                    applyFrameRateRange()
                    onCameraReady?.invoke(it)
                }
            true
        } catch (error: Exception) {
            when {
                // Not every camera can hold the requested frame rate; retry letting it choose.
                mode == Mode.VIDEO && withTargetFrameRate ->
                    bind(withTargetFrameRate = false, withSnapshot = withSnapshot)
                // Preview + recorder + stills is beyond a LEGACY camera. Recording without
                // the snapshot button beats refusing to open the camera at all.
                mode == Mode.VIDEO && withSnapshot ->
                    bind(withTargetFrameRate = true, withSnapshot = false)
                else -> {
                    // The use cases just built were never attached to a session. Dropping
                    // them stops a later takePicture() going to a dead pipeline and failing
                    // with nothing but "could not save the photo".
                    camera = null
                    imageCapture = null
                    videoCapture = null
                    snapshotSupported = false
                    onCameraError?.invoke(error)
                    false
                }
            }
        }
    }

    private fun supportsRawCapture(
        provider: ProcessCameraProvider,
        selector: CameraSelector
    ): Boolean = try {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull()
        info != null && ImageCapture.getImageCaptureCapabilities(info)
            .supportedOutputFormats
            .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
    } catch (error: Exception) {
        false
    }

    /** True when the current camera can actually write a DNG next to the JPEG. */
    fun rawSupported(): Boolean {
        val provider = provider ?: return false
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        return supportsRawCapture(provider, selector)
    }

    /**
     * A camera that refuses to open must not be left as the current one: the session would
     * be closed while the screen still believed it was live, and the next capture would
     * simply never arrive. Failing the switch puts the working lens straight back.
     */
    fun switchLens() {
        val previous = lensFacing
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        if (!bind()) {
            lensFacing = previous
            bind()
        }
    }

    fun setAspectRatio(ratio: Int) {
        if (aspectRatio == ratio) return
        aspectRatio = ratio
        bind()
    }

    fun setStabilizationEnabled(enabled: Boolean) {
        if (stabilizationEnabled == enabled) return
        stabilizationEnabled = enabled
        bind()
    }

    fun isStabilizationSupported(): Boolean =
        sensorCapabilities().supportsVideoStabilization

    fun setFlashMode(mode: Int) {
        flashMode = mode
        imageCapture?.flashMode = mode
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun zoomRange(): ClosedFloatingPointRange<Float> {
        val state = camera?.cameraInfo?.zoomState?.value ?: return 1f..1f
        return state.minZoomRatio..state.maxZoomRatio
    }

    fun setZoomRatio(ratio: Float) {
        val range = zoomRange()
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(range.start, range.endInclusive))
    }

    private fun intrinsicZoomOf(info: CameraInfo) = info.intrinsicZoomRatio

    /** True when the device exposes a lens wider than the default one. */
    fun hasWideLens(): Boolean {
        val provider = provider ?: return false
        return try {
            CameraSelector.Builder().requireLensFacing(lensFacing).build()
                .filter(provider.availableCameraInfos)
                .any { intrinsicZoomOf(it) < WIDE_LENS_THRESHOLD }
        } catch (error: Exception) {
            false
        }
    }

    /**
     * The zoom steps worth offering: the wide lens when there is one, 1x, and a couple of
     * stops the current lens can actually reach.
     */
    fun zoomStops(): List<Float> {
        val range = zoomRange()
        val stops = mutableListOf<Float>()
        if (range.start < 0.95f || hasWideLens()) stops += 0.5f
        stops += 1f
        listOf(2f, 3f, 5f).forEach { stop ->
            if (stop <= range.endInclusive && stops.size < 4) stops += stop
        }
        return stops
    }

    /**
     * Applies a zoom step, moving to the ultra-wide lens when the request is below what the
     * current one can do, and back again when it is not.
     */
    fun requestZoom(ratio: Float) {
        val wantsWide = ratio < 0.95f && zoomRange().start >= 0.95f && hasWideLens()
        if (wantsWide != usingWideLens) {
            val previous = usingWideLens
            usingWideLens = wantsWide
            // Same reasoning as switchLens: an ultra-wide that will not bind has to be
            // given back rather than left as the selected camera.
            if (!bind()) {
                usingWideLens = previous
                bind()
                return
            }
            // The new session starts at its own 1x, which is the wider field of view.
            if (!wantsWide) setZoomRatio(ratio)
            return
        }
        setZoomRatio(if (usingWideLens) ratio * 2f else ratio)
    }

    /** Tap to focus, metering on the point the user touched in the preview. */
    fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .addPoint(point, FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        control.startFocusAndMetering(action)
    }

    fun exposureCompensationRange(): Range<Int> =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: Range(0, 0)

    fun exposureCompensationStep(): Double =
        camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toDouble() ?: 0.0

    fun setExposureCompensation(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    /** The ISO and exposure-time the sensor actually supports, for the Pro dials. */
    fun sensorCapabilities(): SensorCapabilities =
        SensorCapabilities.read(context, lensFacing)

    /** Manual ISO / shutter / white balance for the Pro screen, through Camera2 interop. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun applyManualControls(controls: ManualControls) {
        val control = camera?.cameraControl ?: return
        Camera2CameraControl.from(control)
            .setCaptureRequestOptions(controls.toCaptureRequestOptions())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun clearManualControls() {
        val control = camera?.cameraControl ?: return
        Camera2CameraControl.from(control)
            .setCaptureRequestOptions(CaptureRequestOptions.Builder().build())
    }

    fun takePhoto(callback: ImageCapture.OnImageCapturedCallback) {
        imageCapture?.takePicture(mainExecutor, callback)
    }

    fun takePhoto(
        outputOptions: ImageCapture.OutputFileOptions,
        callback: ImageCapture.OnImageSavedCallback
    ) {
        imageCapture?.takePicture(outputOptions, mainExecutor, callback)
    }

    /** RAW + JPEG in one shot: the DNG options come first, then the JPEG's. */
    fun takePhoto(
        rawOptions: ImageCapture.OutputFileOptions,
        jpegOptions: ImageCapture.OutputFileOptions,
        callback: ImageCapture.OnImageSavedCallback
    ) {
        imageCapture?.takePicture(rawOptions, jpegOptions, mainExecutor, callback)
    }

    @SuppressLint("MissingPermission")
    fun startRecording(withAudio: Boolean, listener: (VideoRecordEvent) -> Unit): Boolean {
        val videoCapture = videoCapture ?: return false
        if (recording != null) return false
        val pending = videoCapture.output
            .prepareRecording(context, MediaOutput.videoOptions(context))
            .apply { if (withAudio) withAudioEnabled() }
        val started = pending.start(mainExecutor) { event ->
            // The recorder is the authority on what state it is in: a pause it refused must
            // not leave the screen showing a Resume button.
            when (event) {
                is VideoRecordEvent.Start -> recordingState = RecordingState.RECORDING
                is VideoRecordEvent.Pause -> recordingState = RecordingState.PAUSED
                is VideoRecordEvent.Resume -> recordingState = RecordingState.RECORDING
                is VideoRecordEvent.Finalize -> {
                    recordingState = RecordingState.IDLE
                    recording = null
                }
                else -> Unit
            }
            listener(event)
        }
        recording = started
        recordingState = RecordingState.RECORDING
        return true
    }

    /** Both are public CameraX calls on the live [Recording]; neither rebinds the session. */
    fun pauseRecording(): Boolean {
        val recording = recording ?: return false
        if (recordingState != RecordingState.RECORDING) return false
        recording.pause()
        return true
    }

    fun resumeRecording(): Boolean {
        val recording = recording ?: return false
        if (recordingState != RecordingState.PAUSED) return false
        recording.resume()
        return true
    }

    /**
     * Picks the closest range the camera publishes: a fixed one at the requested rate when
     * offered, otherwise the fastest it will hold.
     */
    private fun supportedRangesOf(
        provider: ProcessCameraProvider,
        selector: CameraSelector
    ): Set<Range<Int>> = try {
        selector.filter(provider.availableCameraInfos).firstOrNull()
            ?.supportedFrameRateRanges.orEmpty()
    } catch (error: Exception) {
        emptySet()
    }

    private fun supportedFrameRateRange(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        desired: Int
    ): Range<Int>? = try {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull()
        val supported = info?.supportedFrameRateRanges.orEmpty()
        supported.firstOrNull { it.lower == desired && it.upper == desired }
            ?: supported.filter { it.upper == desired }.maxByOrNull { it.lower }
            // Among equal ceilings prefer the tightest floor: (30,30) holds a steady 30,
            // whereas (10,30) lets auto-exposure drop the rate as the light falls.
            ?: supported.filter { it.upper <= desired }
                .maxWithOrNull(compareBy({ it.upper }, { it.lower }))
            ?: supported.minByOrNull { it.upper }
    } catch (error: Exception) {
        null
    }

    /**
     * Auto-exposure is free to halve the frame rate in dim light. Locking its target range
     * keeps the recording at the rate the mode depends on.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyFrameRateRange() {
        if (mode != Mode.VIDEO) return
        val control = camera?.cameraControl ?: return
        val range = appliedFrameRateRange ?: return
        Camera2CameraControl.from(control).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                .build()
        )
    }

    /** The resolution the recorder actually settled on, which may be below the request. */
    fun videoResolution(): android.util.Size? = videoCapture?.resolutionInfo?.resolution

    /**
     * Hands the file to the recorder to close. [recording] is cleared by the Finalize event
     * rather than here, so a second tap cannot start a clip over the top of one that is
     * still being written.
     */
    fun stopRecording() {
        val recording = recording ?: return
        if (recordingState == RecordingState.FINALIZING) return
        recordingState = RecordingState.FINALIZING
        recording.stop()
    }

    fun release() {
        recording?.stop()
        recording = null
        recordingState = RecordingState.IDLE
        provider?.unbindAll()
        analysisExecutor.shutdown()
        camera = null
    }

    /** Requested quality first, then everything below it, so a 720p-only camera still binds. */
    private fun qualityPreferenceFrom(quality: Quality): List<Quality> {
        val ladder = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
        val start = ladder.indexOf(quality).coerceAtLeast(0)
        return ladder.drop(start)
    }

    private companion object {
        const val TAG = "CameraEngine"

        /** Anything below this is a wider lens than the default one. */
        const val WIDE_LENS_THRESHOLD = 0.95f
    }

    private fun VideoProfile.toQuality(): Quality = when (this) {
        VideoProfile.UHD_60, VideoProfile.UHD_30 -> Quality.UHD
        VideoProfile.FHD_60, VideoProfile.FHD_30 -> Quality.FHD
        VideoProfile.HD_30 -> Quality.HD
    }
}
