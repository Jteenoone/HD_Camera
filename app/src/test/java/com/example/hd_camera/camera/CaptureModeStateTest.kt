package com.example.hd_camera.camera

import androidx.camera.extensions.ExtensionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing a capture mode, and surviving one that cannot be delivered.
 *
 * These are the cases that took the preview down on a real phone: a vendor pipeline that
 * says it is available and then refuses to bind, and a bind that succeeds while the
 * previous request is still on the stack and asks for the old mode all over again.
 */
class CaptureModeStateTest {

    // ── How a mode can be delivered ────────────────────────────────────────

    @Test
    fun `no mode asked for means a plain session`() {
        assertEquals(
            ModeDelivery.PLAIN,
            CaptureModeResolver.deliveryFor(
                requested = ExtensionMode.NONE,
                extensionAvailable = false,
                sceneAvailable = false
            )
        )
    }

    @Test
    fun `the vendor pipeline is preferred when the camera has one`() {
        assertEquals(
            ModeDelivery.VENDOR_EXTENSION,
            CaptureModeResolver.deliveryFor(
                requested = ExtensionMode.NIGHT,
                extensionAvailable = true,
                sceneAvailable = true
            )
        )
    }

    @Test
    fun `a failed extension falls back to the scene mode`() {
        // This is the state after bind() has dropped the extension rung: the camera no
        // longer offers the vendor pipeline, but Camera2 still takes the scene mode.
        assertEquals(
            ModeDelivery.SCENE_MODE,
            CaptureModeResolver.deliveryFor(
                requested = ExtensionMode.NIGHT,
                extensionAvailable = false,
                sceneAvailable = true
            )
        )
    }

    @Test
    fun `with neither the extension nor the scene mode the request is unsupported`() {
        assertEquals(
            ModeDelivery.UNSUPPORTED,
            CaptureModeResolver.deliveryFor(
                requested = ExtensionMode.BOKEH,
                extensionAvailable = false,
                sceneAvailable = false
            )
        )
    }

    // ── What the screen shows ──────────────────────────────────────────────

    @Test
    fun `a session starts on the mode it was given`() {
        val session = CaptureModeSession(ExtensionMode.NONE)
        assertEquals(ExtensionMode.NONE, session.stable)
        assertEquals(ExtensionMode.NONE, session.active)
        assertNull(session.pending)
    }

    @Test
    fun `a request that succeeds becomes the active mode`() {
        val session = CaptureModeSession(ExtensionMode.NONE)
        val token = session.request(ExtensionMode.NIGHT)
        assertEquals(ExtensionMode.NIGHT, session.active)
        assertTrue(session.succeeded(token))
        assertEquals(ExtensionMode.NIGHT, session.stable)
        assertNull(session.pending)
    }

    @Test
    fun `a request that fails leaves the previous stable mode in place`() {
        val session = CaptureModeSession(ExtensionMode.NONE)
        val token = session.request(ExtensionMode.NIGHT)
        assertTrue(session.failed(token))
        assertEquals(ExtensionMode.NONE, session.stable)
        assertEquals(ExtensionMode.NONE, session.active)
        assertNull(session.pending)
    }

    @Test
    fun `a failed request does not disturb a mode that was already working`() {
        val session = CaptureModeSession(ExtensionMode.NONE)
        session.succeeded(session.request(ExtensionMode.BOKEH))
        assertEquals(ExtensionMode.BOKEH, session.stable)

        session.failed(session.request(ExtensionMode.NIGHT))
        assertEquals(ExtensionMode.BOKEH, session.stable)
        assertEquals(ExtensionMode.BOKEH, session.active)
    }

    // ── Stale callbacks ────────────────────────────────────────────────────

    @Test
    fun `a callback from an overtaken request is refused`() {
        val session = CaptureModeSession(ExtensionMode.NONE)
        val first = session.request(ExtensionMode.NIGHT)
        val second = session.request(ExtensionMode.BOKEH)

        // The Night bind finally reports back, long after Portrait took over.
        assertFalse(session.succeeded(first))
        assertEquals(ExtensionMode.NONE, session.stable)

        assertTrue(session.succeeded(second))
        assertEquals(ExtensionMode.BOKEH, session.stable)
    }

    @Test
    fun `an overtaken request cannot report a failure either`() {
        val session = CaptureModeSession(ExtensionMode.NONE)
        val first = session.request(ExtensionMode.NIGHT)
        val second = session.request(ExtensionMode.BOKEH)

        // An old failure must not clear the request that is genuinely in flight.
        assertFalse(session.failed(first))
        assertEquals(ExtensionMode.BOKEH, session.pending)
        assertTrue(session.isCurrent(second))
    }

    @Test
    fun `every request gets its own token`() {
        val session = CaptureModeSession(ExtensionMode.NONE)
        val first = session.request(ExtensionMode.NIGHT)
        val second = session.request(ExtensionMode.NIGHT)
        assertFalse(first == second)
        assertFalse(session.isCurrent(first))
        assertTrue(session.isCurrent(second))
    }

    @Test
    fun `the whole Photo to Night to Portrait to Photo round trip settles correctly`() {
        val session = CaptureModeSession(ExtensionMode.NONE)

        // Night cannot bind on this device.
        session.failed(session.request(ExtensionMode.NIGHT))
        assertEquals(ExtensionMode.NONE, session.stable)

        // Portrait comes through on the scene-mode fallback.
        session.succeeded(session.request(ExtensionMode.BOKEH))
        assertEquals(ExtensionMode.BOKEH, session.stable)

        // And back to plain Photo.
        session.succeeded(session.request(ExtensionMode.NONE))
        assertEquals(ExtensionMode.NONE, session.stable)
        assertNull(session.pending)
    }
}
