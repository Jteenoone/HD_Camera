package com.digitalcamerahd.camera4k.selfiecamera.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit

/**
 * Light or dark for the app's own screens. Dark is the default because it is what the
 * design was drawn in; the camera screens are dark either way.
 */
object ThemePrefs {

    private const val FILE = "theme_prefs"
    private const val KEY_DARK = "dark_mode"

    fun isDark(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(KEY_DARK, true)

    /**
     * Stores the choice and applies it. AppCompat recreates the running activities itself,
     * so callers must not recreate on top of it.
     */
    fun setDark(context: Context, dark: Boolean) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_DARK, dark) }
        apply(dark)
    }

    fun restore(context: Context) = apply(isDark(context))

    private fun apply(dark: Boolean) {
        val mode = if (dark) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
