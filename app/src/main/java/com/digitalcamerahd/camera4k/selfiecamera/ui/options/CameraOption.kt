package com.digitalcamerahd.camera4k.selfiecamera.ui.options

/**
 * One line of a chooser.
 *
 * Every setting that used to advance on each tap — the aspect ratio, the video profile, the
 * photo resolution, the file format — is described as a list of these instead, so the whole
 * set is on screen at once and a setting the camera cannot deliver says so rather than
 * quietly doing nothing.
 *
 * @param id what [CameraOptionBottomSheet.Host] gets back; an enum name, usually.
 * @param subtitle a short line under the title. Replaced by [disabledReason] when the option
 *   is not available, since the reason is the more useful of the two.
 */
data class CameraOption(
    val id: String,
    val title: CharSequence,
    val subtitle: CharSequence? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val disabledReason: CharSequence? = null
) {

    /** What the row shows under the title. */
    val detail: CharSequence?
        get() = if (enabled) subtitle else disabledReason ?: subtitle
}
