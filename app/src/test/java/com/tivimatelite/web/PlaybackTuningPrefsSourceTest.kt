package com.tivimatelite.web

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTuningPrefsSourceTest {

    @Test
    fun source_contains_buffer_and_decoder_pref_keys() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/PlaybackTuningPrefs.kt"
        ).readText()

        assertTrue(source.contains("KEY_BUFFER_PROFILE"))
        assertTrue(source.contains("KEY_DECODER_FALLBACK_POLICY"))
        assertTrue(source.contains("getBufferProfile"))
        assertTrue(source.contains("setBufferProfile"))
        assertTrue(source.contains("getDecoderFallbackPolicy"))
        assertTrue(source.contains("setDecoderFallbackPolicy"))
    }
}
