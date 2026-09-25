package com.digitalcamerahd.camera4k.selfiecamera

import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.digitalcamerahd.camera4k.selfiecamera.data.ViewfinderPrefs
import com.digitalcamerahd.camera4k.selfiecamera.ui.AlwaysDark
import com.digitalcamerahd.camera4k.selfiecamera.ui.camera.ShutterKeyHandler
import com.digitalcamerahd.camera4k.selfiecamera.ui.home.HomeFragment
import com.digitalcamerahd.camera4k.selfiecamera.ui.intro.IntroFragment

/**
 * Single-activity host. Every screen is a fragment swapped into R.id.nav_host: the eleven
 * from "HD Camera App.dc.html", and the Home dashboard the app now opens on, which is the
 * one screen with no design of its own behind it.
 */
class MainActivity : AppCompatActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        updateSystemBarIcons(null)

        // The bars are transparent, so their icons follow whichever screen is showing: dark
        // on the light theme, light on the dark theme and always on the camera screens.
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                    if (f.id == R.id.nav_host) updateSystemBarIcons(f)
                }
            },
            false
        )

        if (savedInstanceState == null) {
            val onboarded = ViewfinderPrefs.get(this, KEY_ONBOARDED)
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.nav_host, if (onboarded) HomeFragment() else IntroFragment())
            }
        }
    }

    private fun updateSystemBarIcons(screen: Fragment?) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val lightIcons = night || screen is AlwaysDark
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
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
