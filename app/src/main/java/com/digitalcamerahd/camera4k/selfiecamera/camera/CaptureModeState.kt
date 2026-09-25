package com.digitalcamerahd.camera4k.selfiecamera.camera

import androidx.camera.extensions.ExtensionMode

/** How a capture mode is being delivered by the session that is open. */
enum class ModeDelivery {
    /** No mode asked for; a plain session. */
    PLAIN,

    /** The camera vendor's own Night / Portrait / HDR pipeline. */
    VENDOR_EXTENSION,

    /** Camera2's scene mode, which drives the same hardware more crudely. */
    SCENE_MODE,

    /** The session is open but the mode asked for is not on it. */
    UNSUPPORTED
}

/** Works out how a mode could be delivered, before anything is bound. */
object CaptureModeResolver {

    /**
     * The best delivery [requested] can get. Asking the camera first and binding second is
     * what stops a mode nothing can deliver from taking a working session down with it.
     */
    fun deliveryFor(
        requested: Int,
        extensionAvailable: Boolean,
        sceneAvailable: Boolean
    ): ModeDelivery = when {
        requested == ExtensionMode.NONE -> ModeDelivery.PLAIN
        extensionAvailable -> ModeDelivery.VENDOR_EXTENSION
        sceneAvailable -> ModeDelivery.SCENE_MODE
        else -> ModeDelivery.UNSUPPORTED
    }
}

/**
 * Which capture mode the camera is really running and which one is on its way.
 *
 * Two things went wrong without this. The screen marked a mode active the moment it was
 * asked for, so a mode that failed to bind left the strip pointing at a session that was
 * not there. And a bind that succeeded raised its ready callback while the previous
 * request was still on the stack, which asked for the *old* mode all over again and tore
 * the new session straight back down. A request carries a token; a callback that arrives
 * with an old one belongs to a session that has already been replaced and is dropped.
 */
class CaptureModeSession(initialMode: Int = ExtensionMode.NONE) {

    /** The mode the running session really has. */
    var stable: Int = initialMode
        private set

    /** The mode being bound right now, if any. */
    var pending: Int? = null
        private set

    /** Bumped on every request, so a stale callback can be told apart from a live one. */
    var token: Long = 0L
        private set

    /** What the screen should be showing: the request in flight, or the last good mode. */
    val active: Int get() = pending ?: stable

    /** Starts a request and returns the token that identifies it. */
    fun request(mode: Int): Long {
        pending = mode
        token += 1
        return token
    }

    /** True when [token] is the request still in flight. */
    fun isCurrent(token: Long): Boolean = token == this.token

    /**
     * The request bound. Returns false when a newer one has overtaken it, in which case the
     * caller must not touch the screen.
     */
    fun succeeded(token: Long): Boolean {
        if (!isCurrent(token)) return false
        stable = pending ?: stable
        pending = null
        return true
    }

    /**
     * The request could not be delivered. The camera stays on the mode that last worked,
     * which is the one the screen is already showing.
     */
    fun failed(token: Long): Boolean {
        if (!isCurrent(token)) return false
        pending = null
        return true
    }
}
