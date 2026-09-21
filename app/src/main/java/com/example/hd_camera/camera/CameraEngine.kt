package com.example.hd_camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
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

    /** The EIS chip on the Video screen. */
    var stabilizationEnabled: Boolean = false
        private set

    /**
     * Set before [start] to receive preview frames — the Pro screen uses them to draw
     * a live histogram. Frames arrive on a background thread and must be closed by the engine.
     */
    var frameAnalyzer: ((ImageProxy) -> Unit)? = null

    /** Time-lapse wants upright RGBA frames it can encode; the histogram wants raw luma. */
    var analysisUsesRgba: Boolean = false

    /** Time-lapse binds no still capture, so a RAW session would only get in the way. */
    var allowRawCapture: Boolean = true

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

    private fun bind(withTargetFrameRate: Boolean = true) {
        val provider = provider ?: return
        provider.unbindAll()

        val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
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
                rawCaptureActive = allowRawCapture &&
                    CaptureSettings.format(context) == CaptureFormat.JPEG_RAW &&
                    supportsRawCapture(provider, selector)
                if (rawCaptureActive) {
                    builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
                }
                imageCapture = builder.build().also { group.addUseCase(it) }
                videoCapture = null

                // Extensions take over the pipeline, so the histogram only runs on a plain session.
                val analyzer = frameAnalyzer
                if (analyzer != null && extensionMode == ExtensionMode.NONE) {
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    if (analysisUsesRgba) {
                        analysisBuilder
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setOutputImageRotationEnabled(true)
                            // The default analysis stream is 640x480, too soft for a clip.
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            android.util.Size(1280, 720),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                        )
                                    )
                                    .build()
                            )
                    }
                    val analysis = analysisBuilder.build()
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
                videoCapture = videoBuilder.build().also { group.addUseCase(it) }
                imageCapture = null
            }
        }

        try {
            camera = provider.bindToLifecycle(lifecycleOwner, selector, group.build())
                .also {
                    applySceneFallback()
                    applyFrameRateRange()
                    onCameraReady?.invoke(it)
                }
        } catch (error: Exception) {
            // Not every camera can hold the requested frame rate; retry letting it choose.
            if (mode == Mode.VIDEO && withTargetFrameRate) {
                bind(withTargetFrameRate = false)
            } else {
                onCameraError?.invoke(error)
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

    fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bind()
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
        recording = pending.start(mainExecutor) { event ->
            if (event is VideoRecordEvent.Finalize) recording = null
            listener(event)
        }
        return true
    }

    /**
     * Picks the closest range the camera publishes: a fixed one at the requested rate when
     * offered, otherwise the fastest it will hold.
     */
    private fun supportedFrameRateRange(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        desired: Int
    ): Range<Int>? = try {
        val info = selector.filter(provider.availableCameraInfos).firstOrNull()
        val supported = info?.supportedFrameRateRanges.orEmpty()
        supported.firstOrNull { it.lower == desired && it.upper == desired }
            ?: supported.filter { it.upper == desired }.maxByOrNull { it.lower }
            ?: supported.filter { it.upper <= desired }.maxByOrNull { it.upper }
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

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    fun release() {
        recording?.stop()
        recording = null
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

    private fun VideoProfile.toQuality(): Quality = when (this) {
        VideoProfile.UHD_60, VideoProfile.UHD_30 -> Quality.UHD
        VideoProfile.FHD_60, VideoProfile.FHD_30 -> Quality.FHD
        VideoProfile.HD_30 -> Quality.HD
    }
}
