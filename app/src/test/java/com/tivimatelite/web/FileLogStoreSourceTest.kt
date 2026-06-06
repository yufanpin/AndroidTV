package com.tivimatelite.web

import org.junit.Assert.assertTrue
import org.junit.Test

class FileLogStoreSourceTest {

    @Test
    fun source_contains_batched_flush_configuration() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/FileLogStore.kt"
        ).readText()

        assertTrue(source.contains("private const val FLUSH_INTERVAL_MS = 2000L"))
        assertTrue(source.contains("private const val FLUSH_LINE_THRESHOLD = 10"))
        assertTrue(source.contains("pendingLines"))
        assertTrue(source.contains("flushPendingLocked"))
    }
}
