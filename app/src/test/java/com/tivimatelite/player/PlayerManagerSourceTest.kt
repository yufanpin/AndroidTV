package com.tivimatelite.player

import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerManagerSourceTest {

    @Test
    fun source_contains_deeper_live_buffer_values() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/player/PlayerManager.kt"
        ).readText()

        assertTrue(source.contains("private const val MIN_BUFFER_MS = 15_000"))
        assertTrue(source.contains("private const val MAX_BUFFER_MS = 45_000"))
        assertTrue(source.contains("private const val PLAYBACK_BUFFER_MS = 3_000"))
        assertTrue(source.contains("private const val REBUFFER_MS = 8_000"))
    }
}
