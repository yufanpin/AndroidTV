package com.tivimatelite.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmlogicSurfaceWorkaroundTest {
    @Test
    fun `returns false for non amlogic decoder on android 9`() {
        assertFalse(
            AmlogicSurfaceWorkaround.shouldForceSetOutputSurfaceWorkaround(
                sdkInt = 28,
                codecName = "OMX.qcom.video.decoder.avc",
            )
        )
    }

    @Test
    fun `returns false for amlogic decoder above android 9`() {
        assertFalse(
            AmlogicSurfaceWorkaround.shouldForceSetOutputSurfaceWorkaround(
                sdkInt = 29,
                codecName = "OMX.amlogic.avc.decoder.awesome",
            )
        )
    }

    @Test
    fun `returns true for amlogic decoder on android 9 or below`() {
        assertTrue(
            AmlogicSurfaceWorkaround.shouldForceSetOutputSurfaceWorkaround(
                sdkInt = 28,
                codecName = "OMX.amlogic.avc.decoder.awesome",
            )
        )
    }
}
