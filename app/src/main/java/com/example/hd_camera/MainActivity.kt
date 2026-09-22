package com.example.hd_camera

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.commit
import com.example.hd_camera.data.ViewfinderPrefs
import com.example.hd_camera.ui.camera.ShutterKeyHandler
import com.example.hd_camera.ui.home.HomeFragment
import com.example.hd_camera.ui.intro.IntroFragment

/**
 * Single-activity host. Every screen is a fragment swapped into R.id.nav_host: the eleven
 * from "HD Camera App.dc.html", and the Home dashboard the app now opens on, which is the
 * one screen with no design of its own behind it.
 */
class MainActivity : AppCompatActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        if (savedInstanceState == null) {
            val onboarded = ViewfinderPrefs.get(this, KEY_ONBOARDED)
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.nav_host, if (onboarded) HomeFragment() else IntroFragment())
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeKey && ViewfinderPrefs.get(this, ViewfinderPrefs.KEY_VOLUME_SHUTTER)) {
            val current = supportFragmentManager.findFragmentById(R.id.nav_host)
            if (current is ShutterKeyHandler && current.onShutterKey()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val KEY_ONBOARDED = "onboarding_done"
    }
}
