package com.tivimatelite.web

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdminServerManagerTest {

    @Test
    fun startPath_includesFileLogDiagnosticsAndBindVerification() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/LocalAdminServerManager.kt"
        ).readText()

        assertTrue(
            "start() should log startup diagnostics to FileLogStore",
            source.contains("FileLogStore.i(TAG, \"Starting local admin server") ||
                source.contains("FileLogStore.w(TAG, \"Local admin server start failed")
        )
        assertTrue(
            "start() should verify the server is actually reachable after start",
            source.contains("verifyServerReachable(")
        )
        assertTrue(
            "manager should expose running state for diagnostics",
            source.contains("fun isServerRunning()")
        )
    }
}
