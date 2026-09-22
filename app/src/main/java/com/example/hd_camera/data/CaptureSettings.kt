package com.example.hd_camera.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Size
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.example.hd_camera.R

/** Photo resolution choices behind the "Photo resolution" row of the Settings screen. */
enum class PhotoResolution(@StringRes val label: Int, val target: Size?) {
    /** Let CameraX pick the sensor's largest still size. */
    HIGHEST(R.string.highest, null),
    HIGH(R.string.high, Size(4000, 3000)),
    STANDARD(R.string.standard, Size(3264, 2448));

    companion object {
        fun of(name: String?): PhotoResolution =
            entries.firstOrNull { it.name == name } ?: HIGHEST
    }
}

/** "Format" row: plain JPEG, or JPEG plus a DNG sidecar where the camera supports it. */
enum class CaptureFormat(@StringRes val label: Int) {
    JPEG(R.string.format_jpeg),
    JPEG_RAW(R.string.format_jpeg_raw);

    companion object {
        fun of(name: String?): CaptureFormat = entries.firstOrNull { it.name == name } ?: JPEG_RAW
    }
}

/** "Video" row: resolution and frame rate for the recorder. */
enum class VideoProfile(@StringRes val label: Int, val heightPx: Int, val fps: Int) {
    UHD_60(R.string.video_uhd_60, 2160, 60),
    UHD_30(R.string.video_uhd_30, 2160, 30),
    FHD_60(R.string.video_fhd_60, 1080, 60),
    FHD_30(R.string.video_fhd_30, 1080, 30),
    HD_30(R.string.video_hd_30, 720, 30);

    companion object {
        fun of(name: String?): VideoProfile = entries.firstOrNull { it.name == name } ?: UHD_60
    }
}

/**
 * The enum-valued half of the Settings screen. The switches live in [ViewfinderPrefs];
 * these are the rows that open a chooser.
 */
object CaptureSettings {

    private const val FILE = "capture_settings"
    private const val KEY_PHOTO_RESOLUTION = "photo_resolution"
    private const val KEY_FORMAT = "capture_format"
    private const val KEY_VIDEO_PROFILE = "video_profile"

    /** Everything this app writes goes here, under the shared DCIM tree. */
    const val RELATIVE_PATH = "DCIM/HDCamera"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun photoResolution(context: Context): PhotoResolution =
        PhotoResolution.of(prefs(context).getString(KEY_PHOTO_RESOLUTION, null))

    fun setPhotoResolution(context: Context, value: PhotoResolution) {
        prefs(context).edit { putString(KEY_PHOTO_RESOLUTION, value.name) }
    }

    fun format(context: Context): CaptureFormat =
        CaptureFormat.of(prefs(context).getString(KEY_FORMAT, null))

    fun setFormat(context: Context, value: CaptureFormat) {
        prefs(context).edit { putString(KEY_FORMAT, value.name) }
    }

    fun videoProfile(context: Context): VideoProfile =
        VideoProfile.of(prefs(context).getString(KEY_VIDEO_PROFILE, null))

    fun setVideoProfile(context: Context, value: VideoProfile) {
        prefs(context).edit { putString(KEY_VIDEO_PROFILE, value.name) }
    }
}
