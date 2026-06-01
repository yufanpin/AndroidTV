package com.tivimatelite.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

data class PlayerSnapshot(
    val isReady: Boolean,
    val playWhenReady: Boolean,
    val currentPositionMs: Long
)

class ReadyStallWatch(
    private val scope: CoroutineScope,
    private val getPlayerSnapshot: () -> PlayerSnapshot,
    private val getNowMs: () -> Long,
    private val getTotalRxBytes: () -> Long,
    private val getPlaylistFingerprint: () -> String,
    private val onPlaylistChanged: () -> Unit,
    private val onReadyStallDetected: (String) -> Unit,
    private val onSpeedText: (String) -> Unit,
    private val onHeartbeat: (String) -> Unit,
    private val logWarning: (String) -> Unit
) {
    private var readyStallWatchJob: Job? = null
    private var playlistWatchJob: Job? = null
    private var netSpeedJob: Job? = null
    private var heartbeatJob: Job? = null
    private var readyStallIgnoreUntilMs = 0L

    fun setReadyStallIgnoreUntilMs(value: Long) {
        readyStallIgnoreUntilMs = value
    }

    fun startPlaylistWatcher(initialFingerprint: String) {
        playlistWatchJob?.cancel()
        var lastPlaylistFingerprint = initialFingerprint
        playlistWatchJob = scope.launch {
            while (true) {
                delay(PLAYLIST_WATCH_INTERVAL_MS)
                val currentFingerprint = getPlaylistFingerprint()
                if (currentFingerprint == lastPlaylistFingerprint) continue
                lastPlaylistFingerprint = currentFingerprint
                onPlaylistChanged()
            }
        }
    }

    fun startNetworkSpeedMonitor() {
        netSpeedJob?.cancel()
        netSpeedJob = scope.launch {
            var lastBytes = getTotalRxBytes()
            var lastTimeMs = getNowMs()

            while (true) {
                delay(NET_SPEED_UPDATE_MS)
                val nowBytes = getTotalRxBytes()
                val nowTimeMs = getNowMs()
                val byteDiff = (nowBytes - lastBytes).coerceAtLeast(0L)
                val timeDiffMs = (nowTimeMs - lastTimeMs).coerceAtLeast(1L)
                val bytesPerSecond = byteDiff * 1000.0 / timeDiffMs
                onSpeedText(formatSpeed(bytesPerSecond))
                lastBytes = nowBytes
                lastTimeMs = nowTimeMs
            }
        }
    }

    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                onHeartbeat("HEARTBEAT")
            }
        }
    }

    fun startReadyStallWatch(lastRecoveryAtMs: Long) {
        cancelReadyStallWatch()
        readyStallWatchJob = scope.launch {
            var lastPositionMs = getPlayerSnapshot().currentPositionMs
            var stagnantDurationMs = 0L
            var lastReadyStallRecoveryAtMs = lastRecoveryAtMs

            while (true) {
                delay(READY_STALL_CHECK_INTERVAL_MS)
                val snapshot = getPlayerSnapshot()
                val nowMs = getNowMs()

                if (nowMs < readyStallIgnoreUntilMs) {
                    lastPositionMs = snapshot.currentPositionMs
                    stagnantDurationMs = 0L
                    continue
                }

                if (!snapshot.isReady || !snapshot.playWhenReady) {
                    lastPositionMs = snapshot.currentPositionMs
                    stagnantDurationMs = 0L
                    continue
                }

                val isAdvancing = snapshot.currentPositionMs > lastPositionMs + READY_STALL_ADVANCE_TOLERANCE_MS
                if (isAdvancing) {
                    lastPositionMs = snapshot.currentPositionMs
                    stagnantDurationMs = 0L
                    continue
                }

                stagnantDurationMs += READY_STALL_CHECK_INTERVAL_MS
                if (stagnantDurationMs < READY_STALL_TIMEOUT_MS) continue

                if (nowMs - lastReadyStallRecoveryAtMs < READY_STALL_RECOVERY_COOLDOWN_MS) {
                    stagnantDurationMs = 0L
                    continue
                }

                logWarning("Detected ready stall, trying next source")
                lastReadyStallRecoveryAtMs = nowMs
                onReadyStallDetected("ready_stall")
                return@launch
            }
        }
    }

    fun cancelReadyStallWatch() {
        readyStallWatchJob?.cancel()
        readyStallWatchJob = null
    }

    fun cancel() {
        playlistWatchJob?.cancel()
        netSpeedJob?.cancel()
        heartbeatJob?.cancel()
        cancelReadyStallWatch()
    }

    companion object {
        private const val PLAYLIST_WATCH_INTERVAL_MS = 1200L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
        private const val NET_SPEED_UPDATE_MS = 1000L
        private const val READY_STALL_CHECK_INTERVAL_MS = 5000L
        private const val READY_STALL_TIMEOUT_MS = 300000L
        private const val READY_STALL_ADVANCE_TOLERANCE_MS = 1000L
        private const val READY_STALL_RECOVERY_COOLDOWN_MS = 300000L

        fun formatSpeed(bytesPerSecond: Double): String {
            return when {
                bytesPerSecond >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0))
                bytesPerSecond >= 1024.0 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
                else -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
            }
        }
    }
}
