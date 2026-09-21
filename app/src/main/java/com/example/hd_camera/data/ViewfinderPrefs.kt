package com.example.hd_camera.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The switches on the Settings screen and the `showGrid` / `showWatermark` / `showZoomChips`
 * props the design document exposes are the same state — this is where it lives.
 * Defaults match the design's declared defaults.
 */
object ViewfinderPrefs {

    private const val FILE = "viewfinder_prefs"

    const val KEY_GRID = "show_grid"
    const val KEY_WATERMARK = "show_watermark"
    const val KEY_ZOOM_CHIPS = "show_zoom_chips"
    const val KEY_SHUTTER_SOUND = "shutter_sound"
    const val KEY_VOLUME_SHUTTER = "volume_key_shutter"
    const val KEY_GEOTAGGING = "geotagging"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun defaultOf(key: String): Boolean = when (key) {
        KEY_GRID, KEY_WATERMARK, KEY_ZOOM_CHIPS, KEY_VOLUME_SHUTTER -> true
        else -> false
    }

    fun get(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, defaultOf(key))

    fun set(context: Context, key: String, value: Boolean) {
        prefs(context).edit { putBoolean(key, value) }
    }
}
