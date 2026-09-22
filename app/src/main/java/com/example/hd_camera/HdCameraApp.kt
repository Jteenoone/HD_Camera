package com.example.hd_camera

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.example.hd_camera.data.LocalePrefs

/**
 * Coil needs the video decoder registered explicitly, otherwise the gallery tiles and the
 * last-shot thumbnail come up empty for clips.
 */
class HdCameraApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // AppCompat normally restores the chosen locale itself; this covers the case where
        // its own storage has been cleared but the app's preference survived.
        LocalePrefs.restore(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
