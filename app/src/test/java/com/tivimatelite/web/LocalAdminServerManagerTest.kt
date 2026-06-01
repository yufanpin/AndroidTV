package com.tivimatelite.web

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdminServerManagerTest {

    @Test
    fun startPath_includesFileLogDiagnosticsAndRunningStateExposure() {
        val source = java.io.File(
            "src/main/java/com/tivimatelite/web/LocalAdminServerManager.kt"
        ).readText()

        assertTrue(
            "start() should log startup diagnostics to FileLogStore",
            source.contains("FileLogStore.i(TAG, \"Starting local admin server") ||
                source.contains("FileLogStore.w(TAG, \"Local admin server start failed")
        )
        assertTrue(
            "manager should expose running state for diagnostics",
            source.contains("fun isServerRunning()")
        )
        assertTrue(
            "manager should expose last start error for diagnostics",
            source.contains("fun getLastStartError()")
        )
    }
}
