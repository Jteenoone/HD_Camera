package com.digitalcamerahd.camera4k.selfiecamera.ui.options

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule a chooser row leans on: an option the device cannot deliver has to say why,
 * because the whole point of listing it is that it no longer silently does nothing.
 */
class CameraOptionTest {

    @Test
    fun `an available option shows its subtitle`() {
        val option = CameraOption(
            id = "HIGH",
            title = "High",
            subtitle = "12 MP · 4000×3000"
        )
        assertEquals("12 MP · 4000×3000", option.detail)
    }

    @Test
    fun `an unavailable option shows the reason instead of the subtitle`() {
        val option = CameraOption(
            id = "UHD_60",
            title = "4K · 60fps",
            subtitle = "The sharpest this app records",
            enabled = false,
            disabledReason = "This camera cannot record at that size"
        )
        assertEquals("This camera cannot record at that size", option.detail)
    }

    @Test
    fun `an unavailable option with no reason falls back to its subtitle`() {
        val option = CameraOption(
            id = "JPEG_RAW",
            title = "JPEG + RAW",
            subtitle = "A DNG beside every JPEG",
            enabled = false
        )
        assertEquals("A DNG beside every JPEG", option.detail)
    }

    @Test
    fun `an option with nothing to add shows no second line`() {
        assertNull(CameraOption(id = "4:3", title = "4:3").detail)
    }

    @Test
    fun `a reason on an available option stays out of the way`() {
        // Reasons are carried whether or not they apply, so building a list can be a
        // straight map; only the disabled rows are meant to show one.
        val option = CameraOption(
            id = "ON",
            title = "Flash on",
            disabledReason = "This camera has no flash"
        )
        assertNull(option.detail)
    }
}
