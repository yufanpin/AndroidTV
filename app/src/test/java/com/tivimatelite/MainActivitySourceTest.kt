package com.tivimatelite

import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivitySourceTest {

    @Test
    fun backendInfo_contains_source_latency_and_buffer_profile() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/MainActivity.kt"
        ).readText()

        assertTrue(source.contains("Source latency:"))
        assertTrue(source.contains("Buffer profile:"))
        assertTrue(source.contains("currentPlayRequestAtMs"))
        assertTrue(source.contains("sourceLatencyMsByUrl"))
    }
}
