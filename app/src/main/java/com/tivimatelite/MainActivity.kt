package com.tivimatelite

import android.media.AudioManager
import android.net.TrafficStats
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.tivimatelite.data.RemotePlaylistRepository
import com.tivimatelite.databinding.ActivityMainBinding
import com.tivimatelite.model.Channel
import com.tivimatelite.parser.M3U8Parser
import com.tivimatelite.player.PlaybackHistoryStore
import com.tivimatelite.player.PlayerManager
import com.tivimatelite.web.AppLogStore
import com.tivimatelite.web.LocalAdminServerManager
import com.tivimatelite.web.PlaylistStore
import java.io.FileNotFoundException
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val scope = MainScope()
    private lateinit var audioManager: AudioManager

    private var channelGroups: List<ChannelGroup> = emptyList()
    private var currentChannelIndex = -1
    private var currentSourceIndex = 0
    private val attemptedSourceIndexes = HashSet<Int>(8)
    private val hlsRetriedSourceIndexes = HashSet<Int>(8)
    private var backendInfoHideJob: Job? = null
    private var bufferingFailoverJob: Job? = null
    private var playlistWatchJob: Job? = null
    private var reloadChannelsJob: Job? = null
    private var readyStallWatchJob: Job? = null
    private var switchDebounceJob: Job? = null
    private var channelNumberHideJob: Job? = null
    private var numericCommitJob: Job? = null
    private var singleSourceRetryJob: Job? = null
    private var netSpeedJob: Job? = null
    private var pendingSwitchIndex: Int? = null
    private val numericInputBuffer = StringBuilder(4)
    private var singleSourceRetryCount = 0
    private var readyStallIgnoreUntilMs = 0L
    private var lastReadyStallRecoveryAtMs = 0L
    private var lastSingleSourceRetryAtMs = 0L
    private var lastPlaylistFingerprint: String? = null
    private var activePlaylistSource = "local assets/channels.m3u"

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            cancelReadyStallWatch()
            if (tryForceHlsForCurrentSource(error)) return
            AppLogStore.w(TAG, "Playback error, trying next source", error)
            playNextSourceForCurrentChannel("player_error")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    cancelReadyStallWatch()
                    scheduleBufferingFailover()
                }
                Player.STATE_READY -> {
                    cancelBufferingFailover()
                    singleSourceRetryCount = 0
                    singleSourceRetryJob?.cancel()
                    lastSingleSourceRetryAtMs = 0L
                    startReadyStallWatch()
                }
                Player.STATE_ENDED -> {
                    cancelReadyStallWatch()
                    playNextSourceForCurrentChannel("state_ended")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        LocalAdminServerManager.start(this)
        AppLogStore.i(TAG, "Admin ready at ${LocalAdminServerManager.getAdminUrl()}")

        audioManager = getSystemService(AudioManager::class.java)

        val player = PlayerManager.getPlayer(this)
        player.addListener(playerListener)
        binding.playerView.player = player
        binding.playerView.requestFocus()

        lastPlaylistFingerprint = PlaylistStore.getConfigFingerprint(this)
        loadChannels()
        startPlaylistWatcher()
        startNetworkSpeedMonitor()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                requestSwitchByDelta(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                requestSwitchByDelta(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO -> {
                toggleBackendInfo()
                true
            }
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_NUMPAD_0,
            KeyEvent.KEYCODE_NUMPAD_1,
            KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_NUMPAD_3,
            KeyEvent.KEYCODE_NUMPAD_4,
            KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_6,
            KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_NUMPAD_8,
            KeyEvent.KEYCODE_NUMPAD_9 -> {
                handleNumericKey(event.keyCode)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onStop() {
        super.onStop()
        PlayerManager.pause()
    }

    override fun onDestroy() {
        binding.playerView.player?.removeListener(playerListener)
        binding.playerView.player = null
        backendInfoHideJob?.cancel()
        playlistWatchJob?.cancel()
        reloadChannelsJob?.cancel()
        switchDebounceJob?.cancel()
        channelNumberHideJob?.cancel()
        numericCommitJob?.cancel()
        singleSourceRetryJob?.cancel()
        netSpeedJob?.cancel()
        cancelReadyStallWatch()
        cancelBufferingFailover()
        scope.cancel()
        if (isFinishing) {
            PlayerManager.release()
            LocalAdminServerManager.stop()
        }
        super.onDestroy()
    }

    private fun loadChannels() {
        scope.launch {
            val channels = loadChannelRows()
            channelGroups = groupChannels(channels)

            if (channelGroups.isEmpty()) {
                AppLogStore.e(TAG, "No channels available")
                return@launch
            }

            restoreLastPlayedChannel()
            playCurrentSource(resetAttempts = true)
        }
    }

    private fun startPlaylistWatcher() {
        playlistWatchJob?.cancel()
        playlistWatchJob = scope.launch {
            while (true) {
                delay(1200)
                val currentFingerprint = PlaylistStore.getConfigFingerprint(this@MainActivity)
                if (currentFingerprint == lastPlaylistFingerprint) continue
                lastPlaylistFingerprint = currentFingerprint
                AppLogStore.i(TAG, "Playlist config changed, reloading channels")
                reloadChannelsKeepingCurrent()
            }
        }
    }

    private fun reloadChannelsKeepingCurrent() {
        if (reloadChannelsJob?.isActive == true) return
        val previousGroup = channelGroups.getOrNull(currentChannelIndex)
        val previousChannelName = previousGroup?.name
        val previousSourceUrl = previousGroup?.sources?.getOrNull(currentSourceIndex)

        reloadChannelsJob = scope.launch {
            val channels = loadChannelRows()
            val newGroups = groupChannels(channels)

            if (newGroups.isEmpty()) {
                AppLogStore.e(TAG, "Reload failed: no channels available")
                return@launch
            }

            channelGroups = newGroups

            val byUrlIndex = previousSourceUrl?.let { url ->
                newGroups.indexOfFirst { group -> url in group.sources }
            } ?: -1

            if (byUrlIndex >= 0) {
                currentChannelIndex = byUrlIndex
                currentSourceIndex = newGroups[byUrlIndex].sources.indexOf(previousSourceUrl).coerceAtLeast(0)
                playCurrentSource(resetAttempts = true)
                return@launch
            }

            val byNameIndex = previousChannelName?.let { name ->
                newGroups.indexOfFirst { group -> group.name == name }
            } ?: -1

            if (byNameIndex >= 0) {
                currentChannelIndex = byNameIndex
                currentSourceIndex = 0
                playCurrentSource(resetAttempts = true)
                return@launch
            }

            currentChannelIndex = 0
            currentSourceIndex = 0
            AppLogStore.w(TAG, "Previous channel not found after reload, switched to ${newGroups[0].name}")
            playCurrentSource(resetAttempts = true)
        }
    }

    private suspend fun loadChannelRows(): List<Channel> {
        val remoteResult = RemotePlaylistRepository.loadChannels()
        if (remoteResult != null) {
            activePlaylistSource = remoteResult.activeSourceLabel
            AppLogStore.i(TAG, "Loaded channels from remote backend: ${remoteResult.activeSourceLabel}")
            return remoteResult.channels
        }

        activePlaylistSource = "local assets/channels.m3u"
        return try {
            val channels = ArrayList<Channel>(512)
            assets.open("channels.m3u").use { input ->
                M3U8Parser.parse(input).collect { channel -> channels.add(channel) }
            }
            AppLogStore.i(TAG, "Loaded channels from local asset")
            channels
        } catch (exception: FileNotFoundException) {
            AppLogStore.w(TAG, "channels.m3u asset not found", exception)
            emptyList()
        }
    }

    private fun groupChannels(rows: List<Channel>): List<ChannelGroup> {
        val grouped = LinkedHashMap<String, LinkedHashSet<String>>(rows.size)
        for (row in rows) {
            val name = row.name.trim()
            val url = row.streamUrl.trim()
            if (name.isEmpty() || url.isEmpty()) continue
            grouped.getOrPut(name) { LinkedHashSet(4) }.add(url)
        }
        return grouped.entries.map { ChannelGroup(it.key, it.value.toList()) }
    }

    private fun restoreLastPlayedChannel() {
        val lastState = PlaybackHistoryStore.getLastPlayedState(this)

        val byUrlIndex = lastState.url?.let { savedUrl ->
            channelGroups.indexOfFirst { group -> savedUrl in group.sources }
        } ?: -1

        if (byUrlIndex >= 0) {
            currentChannelIndex = byUrlIndex
            currentSourceIndex = channelGroups[byUrlIndex].sources.indexOf(lastState.url).coerceAtLeast(0)
            return
        }

        val byNameIndex = lastState.channelName?.let { savedName ->
            channelGroups.indexOfFirst { it.name == savedName }
        } ?: -1

        if (byNameIndex >= 0) {
            currentChannelIndex = byNameIndex
            currentSourceIndex = lastState.sourceIndex.coerceIn(0, channelGroups[byNameIndex].sources.lastIndex)
            return
        }

        currentChannelIndex = 0
        currentSourceIndex = 0
    }

    private fun requestSwitchByDelta(delta: Int) {
        if (channelGroups.isEmpty()) return
        val size = channelGroups.size
        val base = pendingSwitchIndex ?: if (currentChannelIndex >= 0) currentChannelIndex else 0
        pendingSwitchIndex = (base + delta + size) % size
        showChannelNumberOverlay((pendingSwitchIndex!! + 1).toString())

        switchDebounceJob?.cancel()
        switchDebounceJob = scope.launch {
            delay(CHANNEL_ZAP_DEBOUNCE_MS)
            val target = pendingSwitchIndex ?: return@launch
            pendingSwitchIndex = null
            switchChannelImmediately(target)
        }
    }

    private fun switchChannelImmediately(targetIndex: Int) {
        if (channelGroups.isEmpty()) return
        currentChannelIndex = targetIndex.coerceIn(0, channelGroups.lastIndex)
        currentSourceIndex = 0
        showChannelNumberOverlay((currentChannelIndex + 1).toString())
        playCurrentSource(resetAttempts = true)
    }

    private fun handleNumericKey(keyCode: Int) {
        val digit = keyCodeToDigit(keyCode) ?: return
        if (numericInputBuffer.length >= 4) numericInputBuffer.clear()
        numericInputBuffer.append(digit)
        showChannelNumberOverlay(numericInputBuffer.toString())

        numericCommitJob?.cancel()
        numericCommitJob = scope.launch {
            delay(NUMERIC_INPUT_COMMIT_MS)
            val number = numericInputBuffer.toString().toIntOrNull()
            numericInputBuffer.clear()
            if (number == null || number <= 0 || channelGroups.isEmpty()) return@launch

            val targetIndex = number - 1
            if (targetIndex !in channelGroups.indices) {
                AppLogStore.w(TAG, "Numeric channel out of range: $number")
                return@launch
            }
            switchChannelImmediately(targetIndex)
        }
    }

    private fun keyCodeToDigit(keyCode: Int): Int? {
        return when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
            else -> null
        }
    }

    private fun showChannelNumberOverlay(text: String) {
        binding.channelNumberText.text = text
        binding.channelNumberText.visibility = View.VISIBLE
        channelNumberHideJob?.cancel()
        channelNumberHideJob = scope.launch {
            delay(CHANNEL_NUMBER_HIDE_MS)
            binding.channelNumberText.visibility = View.GONE
        }
    }

    private fun startNetworkSpeedMonitor() {
        netSpeedJob?.cancel()
        netSpeedJob = scope.launch {
            var lastBytes = TrafficStats.getTotalRxBytes()
            var lastTimeMs = System.currentTimeMillis()

            while (true) {
                delay(NET_SPEED_UPDATE_MS)
                val nowBytes = TrafficStats.getTotalRxBytes()
                val nowTimeMs = System.currentTimeMillis()
                val byteDiff = (nowBytes - lastBytes).coerceAtLeast(0L)
                val timeDiffMs = (nowTimeMs - lastTimeMs).coerceAtLeast(1L)
                val bytesPerSecond = byteDiff * 1000.0 / timeDiffMs
                binding.netSpeedText.text = formatSpeed(bytesPerSecond)
                lastBytes = nowBytes
                lastTimeMs = nowTimeMs
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024.0 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
        }
    }

    private fun playCurrentSource(resetAttempts: Boolean, forceHls: Boolean = false) {
        if (currentChannelIndex !in channelGroups.indices) return
        val group = channelGroups[currentChannelIndex]
        if (group.sources.isEmpty()) return

        if (currentSourceIndex !in group.sources.indices) currentSourceIndex = 0
        if (resetAttempts) {
            attemptedSourceIndexes.clear()
            hlsRetriedSourceIndexes.clear()
            singleSourceRetryCount = 0
            lastSingleSourceRetryAtMs = 0L
            singleSourceRetryJob?.cancel()
        }
        attemptedSourceIndexes.add(currentSourceIndex)
        cancelBufferingFailover()
        cancelReadyStallWatch()
        readyStallIgnoreUntilMs = System.currentTimeMillis() + READY_STALL_WARMUP_MS

        val sourceUrl = group.sources[currentSourceIndex]
        try {
            PlayerManager.play(this, sourceUrl, forceHls)
            PlaybackHistoryStore.saveLastPlayedChannel(this, group.name, currentSourceIndex, sourceUrl)
            val modeSuffix = if (forceHls) " (forced HLS)" else ""
            AppLogStore.i(TAG, "Playing ${group.name} source ${currentSourceIndex + 1}/${group.sources.size}$modeSuffix")
        } catch (exception: Throwable) {
            AppLogStore.e(TAG, "playCurrentSource failed", exception)
            playNextSourceForCurrentChannel("play_call_failed")
        }
    }

    private fun playNextSourceForCurrentChannel(reason: String) {
        if (currentChannelIndex !in channelGroups.indices) return
        val group = channelGroups[currentChannelIndex]
        if (group.sources.size < 2) {
            retryCurrentSingleSource(reason)
            return
        }

        for (offset in 1 until group.sources.size) {
            val candidateIndex = (currentSourceIndex + offset) % group.sources.size
            if (attemptedSourceIndexes.contains(candidateIndex)) continue

            AppLogStore.w(TAG, "Switching source for ${group.name}, reason=$reason, to index=$candidateIndex")
            currentSourceIndex = candidateIndex
            playCurrentSource(resetAttempts = false)
            return
        }

        AppLogStore.e(TAG, "All sources failed for channel: ${group.name}")
        retryCurrentSingleSource("all_sources_failed")
    }

    private fun retryCurrentSingleSource(reason: String) {
        if (currentChannelIndex !in channelGroups.indices) return
        val group = channelGroups[currentChannelIndex]
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSingleSourceRetryAtMs < SINGLE_SOURCE_RETRY_MIN_GAP_MS) return

        if (singleSourceRetryCount >= SINGLE_SOURCE_RETRY_MAX_COUNT) {
            AppLogStore.e(TAG, "Max retry attempts reached for ${group.name}, giving up until user switches channel")
            return
        }

        singleSourceRetryCount += 1
        val retryDelayMs = (SINGLE_SOURCE_RETRY_BASE_MS * singleSourceRetryCount.toLong())
            .coerceAtMost(SINGLE_SOURCE_RETRY_MAX_MS)
        lastSingleSourceRetryAtMs = nowMs

        AppLogStore.w(
            TAG,
            "Single-source retry for ${group.name}, reason=$reason, attempt=$singleSourceRetryCount/$SINGLE_SOURCE_RETRY_MAX_COUNT, delayMs=$retryDelayMs"
        )

        singleSourceRetryJob?.cancel()
        singleSourceRetryJob = scope.launch {
            delay(retryDelayMs)
            if (currentChannelIndex !in channelGroups.indices) return@launch
            attemptedSourceIndexes.clear()
            hlsRetriedSourceIndexes.clear()
            currentSourceIndex = 0
            playCurrentSource(resetAttempts = false)
        }
    }

    private fun scheduleBufferingFailover() {
        cancelBufferingFailover()
        bufferingFailoverJob = scope.launch {
            delay(BUFFERING_FAILOVER_MS)
            val player = binding.playerView.player ?: return@launch
            if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                playNextSourceForCurrentChannel("buffer_timeout")
            }
        }
    }

    private fun cancelBufferingFailover() {
        bufferingFailoverJob?.cancel()
        bufferingFailoverJob = null
    }

    private fun startReadyStallWatch() {
        cancelReadyStallWatch()
        readyStallWatchJob = scope.launch {
            val player = binding.playerView.player ?: return@launch
            var lastPositionMs = player.currentPosition
            var stagnantDurationMs = 0L

            while (true) {
                delay(READY_STALL_CHECK_INTERVAL_MS)
                val currentPlayer = binding.playerView.player ?: return@launch
                val nowMs = System.currentTimeMillis()

                if (nowMs < readyStallIgnoreUntilMs) {
                    lastPositionMs = currentPlayer.currentPosition
                    stagnantDurationMs = 0L
                    continue
                }

                if (currentPlayer.playbackState != Player.STATE_READY || !currentPlayer.playWhenReady) {
                    lastPositionMs = currentPlayer.currentPosition
                    stagnantDurationMs = 0L
                    continue
                }

                val currentPositionMs = currentPlayer.currentPosition
                val isAdvancing = currentPositionMs > lastPositionMs + READY_STALL_ADVANCE_TOLERANCE_MS

                if (isAdvancing) {
                    lastPositionMs = currentPositionMs
                    stagnantDurationMs = 0L
                    continue
                }

                stagnantDurationMs += READY_STALL_CHECK_INTERVAL_MS
                if (stagnantDurationMs < READY_STALL_TIMEOUT_MS) continue

                if (nowMs - lastReadyStallRecoveryAtMs < READY_STALL_RECOVERY_COOLDOWN_MS) {
                    stagnantDurationMs = 0L
                    continue
                }

                AppLogStore.w(TAG, "Detected ready stall, trying next source")
                lastReadyStallRecoveryAtMs = nowMs
                playNextSourceForCurrentChannel("ready_stall")
                return@launch
            }
        }
    }

    private fun cancelReadyStallWatch() {
        readyStallWatchJob?.cancel()
        readyStallWatchJob = null
    }

    private fun tryForceHlsForCurrentSource(error: PlaybackException): Boolean {
        if (!isUnrecognizedInputFormat(error)) return false
        if (currentChannelIndex !in channelGroups.indices) return false
        if (!hlsRetriedSourceIndexes.add(currentSourceIndex)) return false

        val group = channelGroups[currentChannelIndex]
        AppLogStore.w(
            TAG,
            "Retrying as HLS for ${group.name} source ${currentSourceIndex + 1}/${group.sources.size}"
        )
        playCurrentSource(resetAttempts = false, forceHls = true)
        return true
    }

    private fun isUnrecognizedInputFormat(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.javaClass.name.contains("UnrecognizedInputFormatException")) return true
            current = current.cause
        }
        return false
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun toggleBackendInfo() {
        if (binding.backendInfoText.visibility == View.VISIBLE) {
            hideBackendInfo()
            return
        }

        val probe = RemotePlaylistRepository.getLastProbeInfo()
        val text = buildString {
            append("Detected IP: ")
            append(probe.localIp ?: "unknown")
            append('\n')
            append("Admin URL: ")
            append(LocalAdminServerManager.getAdminUrl())
            append('\n')
            append("Active source: ")
            append(activePlaylistSource)
        }

        binding.backendInfoText.text = text
        AppLogStore.i(TAG, text.replace('\n', ' '))
        binding.backendInfoText.visibility = View.VISIBLE

        backendInfoHideJob?.cancel()
        backendInfoHideJob = scope.launch {
            delay(BACKEND_INFO_AUTO_HIDE_MS)
            hideBackendInfo()
        }
    }

    private fun hideBackendInfo() {
        backendInfoHideJob?.cancel()
        binding.backendInfoText.visibility = View.GONE
    }

    private data class ChannelGroup(
        val name: String,
        val sources: List<String>
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val BACKEND_INFO_AUTO_HIDE_MS = 4000L
        private const val BUFFERING_FAILOVER_MS = 20000L
        private const val CHANNEL_ZAP_DEBOUNCE_MS = 300L
        private const val CHANNEL_NUMBER_HIDE_MS = 1500L
        private const val NUMERIC_INPUT_COMMIT_MS = 900L
        private const val READY_STALL_CHECK_INTERVAL_MS = 5000L
        private const val READY_STALL_TIMEOUT_MS = 300000L
        private const val READY_STALL_ADVANCE_TOLERANCE_MS = 1000L
        private const val READY_STALL_WARMUP_MS = 60000L
        private const val READY_STALL_RECOVERY_COOLDOWN_MS = 300000L
        private const val SINGLE_SOURCE_RETRY_BASE_MS = 8000L
        private const val SINGLE_SOURCE_RETRY_MAX_MS = 20000L
        private const val SINGLE_SOURCE_RETRY_MIN_GAP_MS = 10000L
        private const val SINGLE_SOURCE_RETRY_MAX_COUNT = 5
        private const val NET_SPEED_UPDATE_MS = 1000L
    }
}
