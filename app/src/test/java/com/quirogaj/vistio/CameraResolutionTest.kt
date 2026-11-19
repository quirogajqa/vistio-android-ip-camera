package com.quirogaj.vistio

import com.quirogaj.vistio.data.CameraControl
import com.quirogaj.vistio.data.CameraSettings
import com.quirogaj.vistio.data.CameraSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraResolutionTest {
    @Test fun labelsSupportedSizesWithTheirAspectRatio() {
        assertEquals("1280×720 · 16:9", CameraSize(1280, 720).displayName)
        assertEquals("640×480 · 4:3", CameraSize(640, 480).displayName)
        assertEquals("720×1280 · 9:16", CameraSize(720, 1280).displayName)
    }

    @Test fun sendsExactSizeAndMatchesOnlyTheConfirmedSelection() {
        val request = CameraControl.Resolution(CameraSize(1280, 720).value)
        val automatic = CameraSettings(emptyList(), null, "auto", null, null)
        assertEquals("resolution", request.parameter)
        assertEquals("1280x720", request.value)
        assertFalse(request.matches(automatic))
        assertTrue(request.matches(automatic.copy(resolution = "1280x720")))
    }
}
