package com.example.hd_camera.ui

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import com.example.hd_camera.R

/**
 * A screen that stays dark whatever theme the rest of the app is in: the viewfinders, the
 * editor and the intro. A picture is judged against black, and the white controls drawn
 * over it were never meant to sit on a light surface.
 *
 * Implementers route [Fragment.onGetLayoutInflater] through [darkInflater], so every view
 * they inflate — and everything those views resolve from their own context — gets the
 * night palette. MainActivity reads the marker to keep the status bar icons light.
 */
interface AlwaysDark

/** [inflater], re-rooted on a context that resolves the night resources. */
fun Fragment.darkInflater(inflater: LayoutInflater): LayoutInflater =
    inflater.cloneInContext(requireContext().darkContext())

/**
 * The context a colour should be looked up in from code. On an [AlwaysDark] screen this is
 * the view's own night-mode context; `requireContext()` would hand back the app theme's.
 */
val Fragment.themedContext: Context
    get() = view?.context ?: requireContext()

fun Context.darkContext(): Context {
    val config = Configuration(resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_YES
    }
    return ContextThemeWrapper(this, R.style.Theme_HD_Camera).apply {
        applyOverrideConfiguration(config)
    }
}

