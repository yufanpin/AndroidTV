package com.tivimatelite.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAdapterTest {

    /** Verify bindLogo includes Glide .error() fallback drawable. */
    @Test
    fun bindLogo_includesErrorFallback() {
        val source = ChannelAdapter::class.java
            .getResourceAsStream("/ChannelAdapter.kt")
            ?.bufferedReader()?.readText()
            ?: // Fallback: read from source tree (works in IDE / local test)
            java.io.File(
                "app/src/main/java/com/tivimatelite/ui/ChannelAdapter.kt"
            ).readText()

        assertTrue(
            "bindLogo should call .error(R.drawable.ic_channel_fallback)",
            source.contains(".error(R.drawable.ic_channel_fallback)")
        )
    }

    /** Verify LOGO_OPTIONS still uses override(64,64) + RGB_565 + dontAnimate. */
    @Test
    fun logoOptions_unchanged() {
        val source = java.io.File(
            "app/src/main/java/com/tivimatelite/ui/ChannelAdapter.kt"
        ).readText()

        assertTrue("override(64, 64)", source.contains(".override(64, 64)"))
        assertTrue("PREFER_RGB_565", source.contains("DecodeFormat.PREFER_RGB_565"))
        assertTrue("dontAnimate", source.contains(".dontAnimate()"))
    }
}
