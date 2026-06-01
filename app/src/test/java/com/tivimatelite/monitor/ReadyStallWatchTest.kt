package com.tivimatelite.monitor

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyStallWatchTest {

    @Test
    fun `formatSpeed keeps existing thresholds and labels`() {
        assertEquals("512 B/s", ReadyStallWatch.formatSpeed(512.0))
        assertEquals("2 KB/s", ReadyStallWatch.formatSpeed(2048.0))
        assertEquals("1.50 MB/s", ReadyStallWatch.formatSpeed(1572864.0))
    }

    @Test
    fun `heartbeat logs every ten seconds`() = runTest {
        val logs = mutableListOf<String>()
        val watch = ReadyStallWatch(
            scope = this,
            getPlayerSnapshot = { PlayerSnapshot(false, false, 0L) },
            getNowMs = { currentTime },
            getTotalRxBytes = { 0L },
            getPlaylistFingerprint = { "same" },
            onPlaylistChanged = {},
            onReadyStallDetected = {},
            onSpeedText = {},
            onHeartbeat = { logs += it },
            logWarning = { _ -> }
        )

        watch.startHeartbeat()
        advanceTimeBy(9999)
        runCurrent()
        assertTrue(logs.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("HEARTBEAT"), logs)
    }

    @Test
    fun `playlist watcher and ready stall detection keep existing timing semantics`() = runTest {
        var fingerprint = "a"
        var nowMs = 0L
        val playlistChanges = mutableListOf<Unit>()
        val stallSignals = mutableListOf<String>()
        var snapshot = PlayerSnapshot(isReady = true, playWhenReady = true, currentPositionMs = 1000L)

        val watch = ReadyStallWatch(
            scope = this,
            getPlayerSnapshot = { snapshot },
            getNowMs = { nowMs },
            getTotalRxBytes = { 0L },
            getPlaylistFingerprint = { fingerprint },
            onPlaylistChanged = { playlistChanges += Unit },
            onReadyStallDetected = { stallSignals += it },
            onSpeedText = {},
            onHeartbeat = {},
            logWarning = { _ -> }
        )

        watch.startPlaylistWatcher(initialFingerprint = "a")
        advanceTimeBy(1199)
        runCurrent()
        assertTrue(playlistChanges.isEmpty())
        fingerprint = "b"
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, playlistChanges.size)

        watch.setReadyStallIgnoreUntilMs(60000L)
        watch.startReadyStallWatch(lastRecoveryAtMs = 0L)
        advanceTimeBy(60000)
        nowMs = 60000L
        runCurrent()
        snapshot = snapshot.copy(currentPositionMs = 1000L)
        advanceTimeBy(300000)
        nowMs = 360000L
        runCurrent()
        assertEquals(listOf("ready_stall"), stallSignals)
    }
}
