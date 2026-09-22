package com.example.hd_camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import com.example.hd_camera.R

/** White-balance presets offered by the Pro screen's WB chip. */
enum class WhiteBalance(@StringRes val label: Int, val awbMode: Int, val kelvin: Int) {
    AUTO(R.string.wb_auto, CaptureRequest.CONTROL_AWB_MODE_AUTO, 0),
    INCANDESCENT(R.string.wb_2800k, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, 2800),
    FLUORESCENT(R.string.wb_4000k, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, 4000),
    DAYLIGHT(R.string.wb_5600k, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, 5600),
    CLOUDY(R.string.wb_6500k, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, 6500),
    SHADE(R.string.wb_7500k, CaptureRequest.CONTROL_AWB_MODE_SHADE, 7500);

    companion object {
        /**
         * The presets [awbModes] covers. Auto is always offered: a camera that publishes
         * no list at all still does automatic white balance, it just will not be told
         * which preset to hold.
         */
        fun availableIn(awbModes: List<Int>): List<WhiteBalance> {
            if (awbModes.isEmpty()) return listOf(AUTO)
            return entries.filter { it == AUTO || awbModes.contains(it.awbMode) }
        }
    }
}

/**
 * The manual exposure the Pro screen sends to the sensor. A null field means
 * "leave this one on auto", which is how the design describes the mode.
 */
data class ManualControls(
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val manualFocusDistance: Float? = null,
    /**
     * Holds the metering where it is. It lives here rather than being sent on its own
     * because Camera2CameraControl replaces the whole set of options each time it is
     * given one, so a lock sent separately would wipe the ISO and shutter with it.
     */
    val exposureLocked: Boolean = false
) {

    val isFullyAuto: Boolean
        get() = iso == null && exposureTimeNanos == null &&
            whiteBalance == WhiteBalance.AUTO && manualFocusDistance == null &&
            !exposureLocked

    @OptIn(ExperimentalCamera2Interop::class)
    fun toCaptureRequestOptions(): CaptureRequestOptions {
        val builder = CaptureRequestOptions.Builder()

        // ISO and shutter only take effect with auto-exposure switched off.
        if (iso != null || exposureTimeNanos != null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            iso?.let { builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
            exposureTimeNanos?.let {
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
        }

        // Locking a manual exposure would be a contradiction: it is already fixed.
        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_LOCK,
            exposureLocked && iso == null && exposureTimeNanos == null
        )

        builder.setCaptureRequestOption(
            CaptureRequest.CONTROL_AWB_MODE,
            whiteBalance.awbMode
        )

        if (manualFocusDistance != null) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        return builder.build()
    }
}

/** What the sensor behind the current lens will actually accept. */
data class SensorCapabilities(
    val isoRange: Range<Int>,
    val exposureTimeRange: Range<Long>,
    val supportsManualExposure: Boolean,
    val supportsVideoStabilization: Boolean,
    /** True when the sensor can hand back a DNG as well as a JPEG. */
    val supportsRaw: Boolean,
    /** True when the lens can be driven to a distance rather than only focused for us. */
    val supportsManualFocus: Boolean,
    /** True when the metering can be held where it is. */
    val supportsExposureLock: Boolean,
    /** CONTROL_AWB_MODE values this camera accepts. */
    val awbModes: List<Int>,
    val minFocusDistance: Float,
    /** CONTROL_SCENE_MODE values this camera accepts. */
    val sceneModes: List<Int>
) {

    companion object {

        private val DEFAULT = SensorCapabilities(
            isoRange = Range(50, 6400),
            exposureTimeRange = Range(125_000L, 1_000_000_000L),
            supportsManualExposure = false,
            supportsVideoStabilization = false,
            supportsRaw = false,
            supportsManualFocus = false,
            supportsExposureLock = false,
            awbModes = emptyList(),
            minFocusDistance = 0f,
            sceneModes = emptyList()
        )

        fun read(context: Context, lensFacing: Int): SensorCapabilities {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return DEFAULT
            return try {
                val facing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
                val id = manager.cameraIdList.firstOrNull { cameraId ->
                    manager.getCameraCharacteristics(cameraId)
                        .get(CameraCharacteristics.LENS_FACING) == facing
                } ?: return DEFAULT

                val characteristics = manager.getCameraCharacteristics(id)
                val capabilities = characteristics
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.toList()
                    .orEmpty()

                SensorCapabilities(
                    isoRange = characteristics
                        .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                        ?: DEFAULT.isoRange,
                    exposureTimeRange = characteristics
                        .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                        ?: DEFAULT.exposureTimeRange,
                    supportsManualExposure = capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                    ),
                    supportsVideoStabilization = characteristics
                        .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?.any { it != CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF }
                        ?: false,
                    supportsRaw = capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
                    ),
                    // Driving the lens by hand needs both a camera that will turn its
                    // autofocus off and a lens that reports how close it can get. A fixed
                    // focus camera reports zero and cannot be driven anywhere.
                    supportsManualFocus = characteristics
                        .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        ?.contains(CameraCharacteristics.CONTROL_AF_MODE_OFF) == true &&
                        (characteristics
                            .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                            ?: 0f) > 0f,
                    supportsExposureLock = characteristics
                        .get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false,
                    awbModes = characteristics
                        .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                        ?.toList()
                        .orEmpty(),
                    minFocusDistance = characteristics
                        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    sceneModes = characteristics
                        .get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                        ?.toList()
                        .orEmpty()
                )
            } catch (error: Exception) {
                DEFAULT
            }
        }
    }
}
