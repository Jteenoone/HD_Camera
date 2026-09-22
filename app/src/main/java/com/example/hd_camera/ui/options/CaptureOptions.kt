package com.example.hd_camera.ui.options

import android.content.Context
import androidx.camera.core.CameraSelector
import com.example.hd_camera.R
import com.example.hd_camera.camera.CameraEngine
import com.example.hd_camera.camera.SensorCapabilities
import com.example.hd_camera.data.CaptureFormat
import com.example.hd_camera.data.CaptureSettings
import com.example.hd_camera.data.PhotoResolution
import com.example.hd_camera.data.VideoProfile

/**
 * The option lists behind the capture settings.
 *
 * Settings and the viewfinder offer the same three choices, so they are described once
 * here: whichever screen asks gets the same titles, the same order, and the same answer
 * about what this device can actually deliver.
 */
object CaptureOptions {

    /** The keys a [CameraOptionBottomSheet.Host] switches on. */
    const val KEY_PHOTO_RESOLUTION = "photo_resolution"
    const val KEY_CAPTURE_FORMAT = "capture_format"
    const val KEY_VIDEO_PROFILE = "video_profile"

    /**
     * Resolution and frame rate travel together as one profile, so the list is the matrix
     * of the two. [engine] is what knows the camera: without one — the Settings screen has
     * no session open — nothing can be ruled out and every profile is offered.
     */
    fun videoProfiles(context: Context, engine: CameraEngine?): List<CameraOption> {
        val current = CaptureSettings.videoProfile(context)
        return VideoProfile.entries.map { profile ->
            val sizeSupported = engine?.isVideoHeightSupported(profile.heightPx) ?: true
            val rateSupported = engine?.isFrameRateSupported(profile.fps) ?: true
            CameraOption(
                id = profile.name,
                title = context.getString(profile.label),
                selected = profile == current,
                enabled = sizeSupported && rateSupported,
                disabledReason = when {
                    !sizeSupported -> context.getString(R.string.video_resolution_unsupported)
                    !rateSupported -> context.getString(R.string.frame_rate_unsupported)
                    else -> null
                }
            )
        }
    }

    /**
     * Every camera can be asked for a smaller still than its sensor takes, so these are
     * always offered; the subtitle says what each one comes to in pixels.
     */
    fun photoResolutions(context: Context): List<CameraOption> {
        val current = CaptureSettings.photoResolution(context)
        return PhotoResolution.entries.map { resolution ->
            CameraOption(
                id = resolution.name,
                title = context.getString(resolution.label),
                subtitle = resolution.target?.let { size ->
                    val megapixels = (size.width.toLong() * size.height) / 1_000_000f
                    context.getString(
                        R.string.resolution_sub,
                        Math.round(megapixels),
                        size.width,
                        size.height
                    )
                } ?: context.getString(R.string.resolution_sub_sensor),
                selected = resolution == current
            )
        }
    }

    /**
     * RAW is a sensor capability, so this asks the camera characteristics rather than a
     * live session: the Settings screen has no camera open and still has to tell the truth
     * about whether a DNG is possible.
     */
    fun captureFormats(context: Context): List<CameraOption> {
        val current = CaptureSettings.format(context)
        val rawSupported = SensorCapabilities
            .read(context, CameraSelector.LENS_FACING_BACK)
            .supportsRaw
        return CaptureFormat.entries.map { format ->
            val supported = format != CaptureFormat.JPEG_RAW || rawSupported
            CameraOption(
                id = format.name,
                title = context.getString(format.label),
                selected = format == current,
                enabled = supported,
                disabledReason = context.getString(R.string.raw_unsupported)
                    .takeIf { !supported }
            )
        }
    }
}
