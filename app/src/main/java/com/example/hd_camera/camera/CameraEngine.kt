package com.example.hd_camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
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

    /**
     * How the open session is delivering [extensionMode]. UNSUPPORTED means the camera is
     * open and working but the mode asked for is not on it, which is a different thing
     * from the camera having failed and must read differently on screen.
     */
    var modeDelivery: ModeDelivery = ModeDelivery.PLAIN
        private set

    /** Set while a failed mode change still has a mode to fall back to. */
    private var rollingBackMode = false

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

    /**
     * The main lens's zoom ceiling, remembered while it is bound. Once the ultra-wide is
     * the open camera there is no asking the closed one how far it reaches, and a pinch has
     * to know it can climb back out.
     */
    private var defaultLensMaxZoom: Float = 1f

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

    /**
     * What the sensor used for the last frame it finished.
     *
     * Camera2 has no half-manual exposure: setting either the sensitivity or the exposure
     * time by hand switches auto-exposure off for both. The Pro screen hands the other half
     * one of these so the picture carries on from where it was, instead of the driver
     * keeping whatever it last happened to hold and the frame going black.
     */
    @Volatile
    var lastSensorIso: Int? = null
        private set

    @Volatile
    var lastSensorExposureNanos: Long? = null
        private set

    private val sensorProbe = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastSensorIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastSensorExposureNanos = it }
        }
    }

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

    /**
     * Switches the capture mode chip (HDR) or the mode strip entry (Night / Portrait).
     *
     * Returns true when the open session really is delivering [mode], by vendor extension
     * or by scene mode. False means the camera is still open and usable on the mode it had
     * — the request simply could not be met.
     */
    fun setExtensionMode(mode: Int): Boolean {
        if (extensionMode == mode) return modeDelivery != ModeDelivery.UNSUPPORTED
        val previous = extensionMode
        extensionMode = mode

        // A failure here is not final: the mode it was on is about to be tried again, so
        // the screen must not be told the camera is gone while there is still a way back.
        rollingBackMode = true
        val opened = try {
            bind()
        } finally {
            rollingBackMode = false
        }
        if (opened) return modeDelivery != ModeDelivery.UNSUPPORTED

        // Not even the plain session would open on this mode. Put back the one that was
        // working rather than leaving the screen looking at an unbound preview; if that
        // will not open either, the camera really is gone and bind() says so.
        extensionMode = previous
        bind()
        return false
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

    /** True when Camera2 will take this mode as a scene mode. */
    fun sceneModeAvailable(mode: Int): Boolean {
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
    @OptIn(ExperimentalCamera2Interop::class)
    private fun bind(
        withTargetFrameRate: Boolean = true,
        withSnapshot: Boolean = true,
        withExtension: Boolean = true
    ): Boolean {
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
        usingVendorExtension = withExtension && mode == Mode.PHOTO &&
            extensionMode != ExtensionMode.NONE && isExtensionAvailable(extensionMode)
        val selector = if (usingVendorExtension) {
            extensionsManager?.getExtensionEnabledCameraSelector(baseSelector, extensionMode)
                ?: baseSelector
        } else {
            baseSelector
        }
        val aspectRatioStrategy =
            AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val previewBuilder = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectRatioStrategy)
                    .build()
            )
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(sensorProbe)
        val preview = previewBuilder
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
                // An extension session refuses a RAW output format outright — asking
                // for one is what took Night down with an IllegalArgumentException, since
                // the capability query answers for the plain camera behind the extension.
                rawCaptureActive = CaptureSettings.format(context) == CaptureFormat.JPEG_RAW &&
                    !usingVendorExtension &&
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
                    modeDelivery = when {
                        extensionMode == ExtensionMode.NONE -> ModeDelivery.PLAIN
                        usingVendorExtension -> ModeDelivery.VENDOR_EXTENSION
                        sceneModeAvailable(extensionMode) -> ModeDelivery.SCENE_MODE
                        else -> ModeDelivery.UNSUPPORTED
                    }
                    if (!usingWideLens) {
                        defaultLensMaxZoom =
                            it.cameraInfo.zoomState.value?.maxZoomRatio ?: defaultLensMaxZoom
                    }
                    applySceneFallback()
                    applyFrameRateRange()
                    onCameraReady?.invoke(it)
                }
            true
        } catch (error: Exception) {
            when {
                // The vendor pipeline refused this configuration. The camera itself is
                // fine: drop to a plain session, where the scene-mode fallback can still
                // deliver Night or Portrait, rather than leaving the preview unbound.
                mode == Mode.PHOTO && withExtension && usingVendorExtension ->
                    bind(withExtension = false)

                // Not every camera can hold the requested frame rate; retry letting it choose.
                mode == Mode.VIDEO && withTargetFrameRate ->
                    bind(withTargetFrameRate = false, withSnapshot = withSnapshot)
                // Preview + recorder + stills is beyond a LEGACY camera. Recording without
                // the snapshot button beats refusing to open the camera at all.
                mode == Mode.VIDEO && withSnapshot ->
                    bind(withTargetFrameRate = true, withSnapshot = false)
                else -> {
                    Log.w(TAG, "could not open the camera: ext=" + extensionMode, error)
                    // The use cases just built were never attached to a session. Dropping
                    // them stops a later takePicture() going to a dead pipeline and failing
                    // with nothing but "could not save the photo".
                    camera = null
                    imageCapture = null
                    videoCapture = null
                    snapshotSupported = false
                    modeDelivery = ModeDelivery.UNSUPPORTED
                    if (!rollingBackMode) onCameraError?.invoke(error)
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

    /** True when the current camera has a flash to fire. */
    fun hasFlash(): Boolean = camera?.cameraInfo?.hasFlashUnit() == true

    /** The recording qualities this camera really offers. */
    fun supportedVideoQualities(): Set<Quality> {
        val info = camera?.cameraInfo ?: return emptySet()
        return try {
            Recorder.getVideoCapabilities(info)
                .getSupportedQualities(DynamicRange.SDR)
                .toSet()
        } catch (error: Exception) {
            emptySet()
        }
    }

    /**
     * True when the recorder can deliver a clip this tall. An empty answer means the
     * session is not up yet rather than that the camera refuses: greying the whole list
     * out on the way in would be worse than offering a profile that later falls back.
     */
    fun isVideoHeightSupported(heightPx: Int): Boolean {
        val qualities = supportedVideoQualities()
        return qualities.isEmpty() || qualities.contains(qualityFor(heightPx))
    }

    /** The frame rates the camera will hold, as the ceiling of each range it publishes. */
    fun supportedFrameRates(): Set<Int> = try {
        camera?.cameraInfo?.supportedFrameRateRanges.orEmpty().map { it.upper }.toSet()
    } catch (error: Exception) {
        emptySet()
    }

    /** True when the camera publishes a range that reaches [fps]. */
    fun isFrameRateSupported(fps: Int): Boolean {
        val rates = supportedFrameRates()
        return rates.isEmpty() || rates.any { it >= fps }
    }

    /** What the lens that is bound can do, in its own sensor ratios. */
    fun zoomRange(): ClosedFloatingPointRange<Float> {
        val state = camera?.cameraInfo?.zoomState?.value ?: return 1f..1f
        return state.minZoomRatio..state.maxZoomRatio
    }

    fun setZoomRatio(ratio: Float) {
        val range = zoomRange()
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(range.start, range.endInclusive))
    }

    /**
     * How a ratio the user asks for maps onto the lens that is bound. The ultra-wide covers
     * roughly twice the field of view of the main lens, so its own 1x is the user's 0.5x.
     * The sensor publishes the real figure; the fallback is only for cameras that do not.
     */
    private fun lensScale(): Float {
        if (!usingWideLens) return 1f
        val intrinsic = camera?.cameraInfo?.let { intrinsicZoomOf(it) } ?: 0f
        return if (intrinsic > 0f && intrinsic < WIDE_LENS_THRESHOLD) {
            1f / intrinsic
        } else {
            WIDE_LENS_FALLBACK_SCALE
        }
    }

    /** The zoom as the user reads it, which is not what the ultra-wide reports. */
    fun currentZoomRatio(): Float {
        val ratio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        return ratio / lensScale()
    }

    /** The widest field of view this device can reach, on the main lens's scale. */
    private fun wideLensZoom(): Float {
        val provider = provider ?: return 1f
        return try {
            CameraSelector.Builder().requireLensFacing(lensFacing).build()
                .filter(provider.availableCameraInfos)
                .minOfOrNull { intrinsicZoomOf(it) } ?: 1f
        } catch (error: Exception) {
            1f
        }
    }

    /**
     * The whole range the user can pinch through, which spans both lenses: crossing 1x is
     * a lens change rather than a zoom. [allowLensSwitch] is false once a clip is running,
     * because rebinding the session would tear the recorder's surface out from under it.
     */
    private fun zoomRangeFor(allowLensSwitch: Boolean): ClosedFloatingPointRange<Float> {
        val range = zoomRange()
        val scale = lensScale()
        val min = range.start / scale
        val max = range.endInclusive / scale
        if (!allowLensSwitch) return min..max
        return when {
            // On the main lens the ultra-wide extends the bottom of the range...
            !usingWideLens && hasWideLens() -> minOf(min, wideLensZoom())..max
            // ...and on the ultra-wide the main lens extends the top of it.
            usingWideLens -> min..maxOf(max, defaultLensMaxZoom)
            else -> min..max
        }
    }

    /** What a pinch can reach right now. */
    fun availableZoomRange(): ClosedFloatingPointRange<Float> =
        zoomRangeFor(allowLensSwitch = !isRecording)

    /**
     * Everything the device can reach across both lenses, whatever the recorder is doing.
     * The zoom wheel spans this and greys out the part [availableZoomRange] excludes, so
     * the arc does not change length the moment a clip starts.
     */
    fun fullZoomRange(): ClosedFloatingPointRange<Float> =
        zoomRangeFor(allowLensSwitch = true)

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
     * The zoom steps worth offering, across both lenses so the row does not change shape
     * the moment a clip starts. A step the device cannot reach is left out rather than
     * shown as a chip that does nothing.
     */
    fun zoomStops(): List<Float> = ZoomMath.stops(zoomRangeFor(allowLensSwitch = true))

    /**
     * Applies a zoom the user asked for, moving to the ultra-wide lens when the request is
     * below what the current one can do and back again when it is not. Returns the ratio
     * that was really applied: the request clamped to what the camera can reach.
     */
    fun requestZoom(ratio: Float): Float {
        val target = ZoomMath.clamp(ratio, availableZoomRange())
        // A lens that already covers the whole range zooms on its own; only a camera that
        // bottoms out at 1x has to be swapped for the wider one.
        // Two thresholds, not one. A single one at 1x meant a thumb resting on the
        // boundary rebound the session over and over as it wobbled; getting on to the
        // ultra-wide now takes a deliberate move down, and getting off it one back up.
        val crossing = if (usingWideLens) WIDE_LENS_EXIT else ZoomMath.WIDE_THRESHOLD
        val wantsWide = target < crossing &&
            zoomRange().start >= ZoomMath.WIDE_THRESHOLD &&
            hasWideLens()
        // Rebinding would tear the recorder's surface away, so a live clip stays on its
        // lens; availableZoomRange has already clamped the request to what that lens does.
        if (wantsWide != usingWideLens && !isRecording) {
            val previous = usingWideLens
            usingWideLens = wantsWide
            // Same reasoning as switchLens: an ultra-wide that will not bind has to be
            // given back rather than left as the selected camera.
            if (!bind()) {
                usingWideLens = previous
                bind()
                return currentZoomRatio()
            }
        }
        setZoomRatio(target * lensScale())
        return target
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

    /**
     * True when the camera offers exposure compensation at all. A range of (0, 0) is how a
     * camera says it has none, and a dial with one stop on it is not a dial.
     */
    fun supportsExposureCompensation(): Boolean {
        val range = exposureCompensationRange()
        return range.upper > range.lower
    }

    /**
     * Holds focus and metering on the middle of the frame, or lets them go again. The
     * auto-cancel that an ordinary tap-to-focus carries is switched off here: a lock that
     * released itself after a few seconds would not be a lock.
     */
    fun lockFocusAndMetering(locked: Boolean) {
        val control = camera?.cameraControl ?: return
        if (!locked) {
            control.cancelFocusAndMetering()
            return
        }
        val point = previewView.meteringPointFactory
            .createPoint(previewView.width / 2f, previewView.height / 2f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .addPoint(point, FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        control.startFocusAndMetering(action)
    }

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
        const val WIDE_LENS_THRESHOLD = ZoomMath.WIDE_THRESHOLD

        /** Used only when a camera does not publish an intrinsic zoom ratio of its own. */
        const val WIDE_LENS_FALLBACK_SCALE = 2f

        /** How far back up the ultra-wide has to be pushed before the main lens returns. */
        const val WIDE_LENS_EXIT = 1.05f
    }

    private fun VideoProfile.toQuality(): Quality = qualityFor(heightPx)

    private fun qualityFor(heightPx: Int): Quality = when {
        heightPx >= 2160 -> Quality.UHD
        heightPx >= 1080 -> Quality.FHD
        heightPx >= 720 -> Quality.HD
        else -> Quality.SD
    }
}
