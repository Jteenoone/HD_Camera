package com.digitalcamerahd.camera4k.selfiecamera.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
    launchOrReport(intent, R.string.link_open_failed)
}

/**
 * Starts an email to [addressRes] in the user's mail app. The row that calls this shows the
 * address too, so a device with no mail app still leaves the user a way to write to it.
 */
fun Fragment.composeEmail(@StringRes addressRes: Int, subject: String) {
    val address = getString(addressRes)
    // Mail apps disagree on where they look: Gmail takes the subject only from the URI,
    // others only from the extras. Both carry everything.
    val uri = "mailto:$address?subject=${Uri.encode(subject)}".toUri()
    val intent = Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
    launchOrReport(intent, R.string.email_app_missing)
}

private fun Fragment.launchOrReport(intent: Intent, @StringRes failure: Int) {
    // resolveActivity only sees the handlers the manifest's <queries> entries name; without
    // them package visibility would hide every one on Android 11+.
    if (intent.resolveActivity(requireContext().packageManager) == null) {
        report(failure)
        return
    }
    try {
        startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        // The resolve above can go stale between the check and the launch.
        report(failure)
        error.printStackTrace()
    }
}

private fun Fragment.report(@StringRes message: Int) {
    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
}
