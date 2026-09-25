package com.digitalcamerahd.camera4k.selfiecamera.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.digitalcamerahd.camera4k.selfiecamera.R

/**
 * Opens a legal document in whatever browser the device uses. The policies live on the web
 * and are not bundled: a WebView inside the app would only serve a stale copy, and would
 * make the app responsible for rendering pages it does not own.
 *
 * A device with no browser at all is rare but real — a kiosk build, or one where the user
 * has disabled it — so the failure is reported rather than allowed to throw.
 */
fun Fragment.openExternalUrl(@StringRes urlRes: Int) {
    val intent = Intent(Intent.ACTION_VIEW, getString(urlRes).toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // resolveActivity only sees browsers because the manifest declares the matching
    // <queries> entry; without it package visibility would hide them on Android 11+.
    if (intent.resolveActivity(requireContext().packageManager) == null) {
        reportNoBrowser()
        return
    }
    try {
        startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        // The resolve above can go stale between the check and the launch.
        reportNoBrowser()
        error.printStackTrace()
    }
}

private fun Fragment.reportNoBrowser() {
    Toast.makeText(requireContext(), R.string.link_open_failed, Toast.LENGTH_LONG).show()
}
