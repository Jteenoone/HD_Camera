package com.digitalcamerahd.camera4k.selfiecamera

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.digitalcamerahd.camera4k.selfiecamera.data.LocalePrefs
import com.digitalcamerahd.camera4k.selfiecamera.data.ThemePrefs

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
        ThemePrefs.restore(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
