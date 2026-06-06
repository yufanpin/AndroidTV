package com.tivimatelite.player

import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerSourceTest {

    @Test
    fun source_contains_deeper_live_buffer_values() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/player/PlayerManager.kt"
        ).readText()

        assertTrue(source.contains("enum class BufferProfile"))
        assertTrue(source.contains("BALANCED(15_000, 45_000, 3_000, 8_000)"))
        assertTrue(source.contains("FAST_SWITCH"))
        assertTrue(source.contains("STABLE"))
        assertTrue(source.contains("bufferProfile.minBufferMs"))
        assertTrue(source.contains("bufferProfile.maxBufferMs"))
        assertTrue(source.contains("bufferProfile.playbackBufferMs"))
        assertTrue(source.contains("bufferProfile.rebufferMs"))
    }
}
