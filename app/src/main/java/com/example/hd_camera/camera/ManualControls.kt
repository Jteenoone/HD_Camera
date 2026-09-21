package com.example.hd_camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector

/** White-balance presets offered by the Pro screen's WB chip. */
enum class WhiteBalance(val label: String, val awbMode: Int, val kelvin: Int) {
    AUTO("AUTO", CaptureRequest.CONTROL_AWB_MODE_AUTO, 0),
    INCANDESCENT("2800K", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, 2800),
    FLUORESCENT("4000K", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, 4000),
    DAYLIGHT("5600K", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, 5600),
    CLOUDY("6500K", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, 6500),
    SHADE("7500K", CaptureRequest.CONTROL_AWB_MODE_SHADE, 7500)
}

/**
 * The manual exposure the Pro screen sends to the sensor. A null field means
 * "leave this one on auto", which is how the design describes the mode.
 */
data class ManualControls(
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val manualFocusDistance: Float? = null
) {

    val isFullyAuto: Boolean
        get() = iso == null && exposureTimeNanos == null &&
            whiteBalance == WhiteBalance.AUTO && manualFocusDistance == null

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
