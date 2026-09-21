package com.example.hd_camera

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

/**
 * Coil needs the video decoder registered explicitly, otherwise the gallery tiles and the
 * last-shot thumbnail come up empty for clips.
 */
class HdCameraApp : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
