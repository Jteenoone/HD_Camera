package com.example.hd_camera.ui.camera

/**
 * Implemented by the viewfinder screens so the volume keys can fire the shutter —
 * the "Volume key shutter" switch on the Settings screen.
 */
interface ShutterKeyHandler {
    /** Returns true when the screen handled the key. */
    fun onShutterKey(): Boolean
}
