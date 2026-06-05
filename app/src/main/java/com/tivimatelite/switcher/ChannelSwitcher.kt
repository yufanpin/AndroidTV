package com.tivimatelite.switcher

import com.tivimatelite.loader.ChannelGroup
import com.tivimatelite.player.PlayableHostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChannelSwitcher(
    private val scope: CoroutineScope,
    private val getChannelGroups: () -> List<ChannelGroup>,
    private val getCurrentChannelIndex: () -> Int,
    private val setCurrentChannelIndex: (Int) -> Unit,
    private val getCurrentSourceIndex: () -> Int,
    private val setCurrentSourceIndex: (Int) -> Unit,
    private val getActivePlaylistSource: () -> String,
    private val showChannelNumberOverlay: (String) -> Unit,
    private val onReadyStallWarmup: () -> Unit,
    private val savePlayback: (channelName: String, sourceIndex: Int, url: String) -> Unit,
    private val playUrl: (url: String, forceHls: Boolean) -> Unit,
    private val logInfo: (String) -> Unit,
    private val logWarning: (String) -> Unit,
    private val logError: (String) -> Unit,
    private val getNowMs: () -> Long,
    private val isPlayerBufferingAndPlaying: () -> Boolean,
    // P0: 智能线路记忆 - 获取可播放域名集合
    private val getPlayableHosts: () -> Set<String> = { emptySet() }
) {
    private val attemptedSourceIndexes = HashSet<Int>(8)
    private val hlsRetriedSourceIndexes = HashSet<Int>(8)
    private var pendingSwitchIndex: Int? = null
    private var singleSourceRetryCount = 0
    private var lastSingleSourceRetryAtMs = 0L
    private var switchDebounceJob: Job? = null
    private var singleSourceRetryJob: Job? = null
    private var bufferingFailoverJob: Job? = null
    // P2: 15s 加载超时计时器
    private var loadTimeoutJob: Job? = null

    fun requestSwitchByDelta(delta: Int) {
        val channelGroups = getChannelGroups()
        if (channelGroups.isEmpty()) return
        val size = channelGroups.size
        val base = pendingSwitchIndex ?: if (getCurrentChannelIndex() >= 0) getCurrentChannelIndex() else 0
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

    fun switchChannelImmediately(targetIndex: Int) {
        val channelGroups = getChannelGroups()
        if (channelGroups.isEmpty()) return
        setCurrentChannelIndex(targetIndex.coerceIn(0, channelGroups.lastIndex))
        // P0: 优先选择记忆中可播放的域名
        val preferredSourceIdx = findPreferredSourceIndex(channelGroups[getCurrentChannelIndex()])
        setCurrentSourceIndex(preferredSourceIdx)
        showChannelNumberOverlay((getCurrentChannelIndex() + 1).toString())
        playCurrentSource(resetAttempts = true)
    }

    fun playCurrentSource(resetAttempts: Boolean, forceHls: Boolean = false) {
        val channelGroups = getChannelGroups()
        val currentChannelIndex = getCurrentChannelIndex()
        if (currentChannelIndex !in channelGroups.indices) return
        val group = channelGroups[currentChannelIndex]
        if (group.sources.isEmpty()) return

        if (getCurrentSourceIndex() !in group.sources.indices) setCurrentSourceIndex(0)
        if (resetAttempts) {
            attemptedSourceIndexes.clear()
            hlsRetriedSourceIndexes.clear()
            singleSourceRetryCount = 0
            lastSingleSourceRetryAtMs = 0L
            singleSourceRetryJob?.cancel()
            // P0: 重置时重新排优先序
            val preferredIdx = findPreferredSourceIndex(group)
            if (preferredIdx != getCurrentSourceIndex()) {
                setCurrentSourceIndex(preferredIdx)
            }
        }

        attemptedSourceIndexes.add(getCurrentSourceIndex())
        cancelBufferingFailover()
        // P2: 加载超时在 onReadyStallWarmup 之后启动（避免干扰）
        onReadyStallWarmup()
        startLoadTimeout()

        val sourceIndex = getCurrentSourceIndex()
        val sourceUrl = group.sources[sourceIndex]
        try {
            playUrl(sourceUrl, forceHls)
            savePlayback(group.name, sourceIndex, sourceUrl)
            val modeSuffix = if (forceHls) " (forced HLS)" else ""
            logInfo("Playing ${group.name} source ${sourceIndex + 1}/${group.sources.size}$modeSuffix")
        } catch (_: Throwable) {
            logError("playCurrentSource failed")
            playNextSourceForCurrentChannel("play_call_failed")
            return
        }
    }

    fun playNextSourceForCurrentChannel(reason: String) {
        cancelLoadTimeout()

        val channelGroups = getChannelGroups()
        val currentChannelIndex = getCurrentChannelIndex()
        if (currentChannelIndex !in channelGroups.indices) return
        val group = channelGroups[currentChannelIndex]
        if (group.sources.size < 2) {
            retryCurrentSingleSource(reason)
            return
        }

        // P0: 选源时优先记忆中可播放的，且未尝试过的
        val playableHosts = getPlayableHosts()
        for (offset in 1 until group.sources.size) {
            val candidateIndex = (getCurrentSourceIndex() + offset) % group.sources.size
            if (attemptedSourceIndexes.contains(candidateIndex)) continue
            // 如果有可播放域名集合，优先选匹配的
            if (playableHosts.isNotEmpty()) {
                val candidateHost = PlayableHostStore.extractHost(group.sources[candidateIndex])
                if (candidateHost != null && candidateHost in playableHosts) {
                    logWarning("Switching to known-good host for ${group.name}, reason=$reason, to index=$candidateIndex")
                    setCurrentSourceIndex(candidateIndex)
                    playCurrentSource(resetAttempts = false)
                    return
                }
            }
        }

        // 回退：选第一个未尝试过的
        for (offset in 1 until group.sources.size) {
            val candidateIndex = (getCurrentSourceIndex() + offset) % group.sources.size
            if (attemptedSourceIndexes.contains(candidateIndex)) continue

            logWarning("Switching source for ${group.name}, reason=$reason, to index=$candidateIndex")
            setCurrentSourceIndex(candidateIndex)
            playCurrentSource(resetAttempts = false)
            return
        }

        logError("All sources failed for channel: ${group.name}")
        retryCurrentSingleSource("all_sources_failed")
    }

    fun retryCurrentSingleSource(reason: String) {
        val channelGroups = getChannelGroups()
        val currentChannelIndex = getCurrentChannelIndex()
        if (currentChannelIndex !in channelGroups.indices) return
        val group = channelGroups[currentChannelIndex]
        val nowMs = getNowMs()
        if (nowMs - lastSingleSourceRetryAtMs < SINGLE_SOURCE_RETRY_MIN_GAP_MS) return

        if (singleSourceRetryCount >= SINGLE_SOURCE_RETRY_MAX_COUNT) {
            logError("Max retry attempts reached for ${group.name}, giving up until user switches channel")
            return
        }

        singleSourceRetryCount += 1
        val retryDelayMs = (SINGLE_SOURCE_RETRY_BASE_MS * singleSourceRetryCount.toLong())
            .coerceAtMost(SINGLE_SOURCE_RETRY_MAX_MS)
        lastSingleSourceRetryAtMs = nowMs

        logWarning(
            "Single-source retry for ${group.name}, reason=$reason, attempt=$singleSourceRetryCount/$SINGLE_SOURCE_RETRY_MAX_COUNT, delayMs=$retryDelayMs"
        )

        singleSourceRetryJob?.cancel()
        singleSourceRetryJob = scope.launch {
            delay(retryDelayMs)
            val groups = getChannelGroups()
            if (getCurrentChannelIndex() !in groups.indices) return@launch
            attemptedSourceIndexes.clear()
            hlsRetriedSourceIndexes.clear()
            setCurrentSourceIndex(0)
            playCurrentSource(resetAttempts = false)
        }
    }

    fun scheduleBufferingFailover() {
        cancelBufferingFailover()
        bufferingFailoverJob = scope.launch {
            delay(BUFFERING_FAILOVER_MS)
            if (isPlayerBufferingAndPlaying()) {
                playNextSourceForCurrentChannel("buffer_timeout")
            }
        }
    }

    fun cancelBufferingFailover() {
        bufferingFailoverJob?.cancel()
        bufferingFailoverJob = null
    }

    /** P2: 取消 15s 加载超时——在 STATE_READY 或位置推进后调用 */
    fun cancelLoadTimeout() {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = null
    }

    fun tryForceHlsForCurrentSource(error: Throwable): Boolean {
        if (!isUnrecognizedInputFormat(error)) return false
        val currentChannelIndex = getCurrentChannelIndex()
        if (currentChannelIndex !in getChannelGroups().indices) return false
        if (!hlsRetriedSourceIndexes.add(getCurrentSourceIndex())) return false

        val group = getChannelGroups()[currentChannelIndex]
        logWarning("Retrying as HLS for ${group.name} source ${getCurrentSourceIndex() + 1}/${group.sources.size}")
        playCurrentSource(resetAttempts = false, forceHls = true)
        return true
    }

    fun cancel() {
        switchDebounceJob?.cancel()
        singleSourceRetryJob?.cancel()
        cancelBufferingFailover()
        cancelLoadTimeout()
    }

    fun describeActiveSource(): String = getActivePlaylistSource()

    /** P0: 从记忆中找已证明可播放的源索引 */
    private fun findPreferredSourceIndex(group: ChannelGroup): Int {
        val playableHosts = getPlayableHosts()
        if (playableHosts.isEmpty()) return getCurrentSourceIndex().coerceIn(0, group.sources.lastIndex)
        val preferredIdx = group.sources.indexOfFirst { url ->
            val host = PlayableHostStore.extractHost(url)
            host != null && host in playableHosts
        }
        return if (preferredIdx >= 0) preferredIdx else getCurrentSourceIndex().coerceIn(0, group.sources.lastIndex)
    }

    /** P2: 启动 15s 加载超时 */
    private fun startLoadTimeout() {
        loadTimeoutJob?.cancel()
        loadTimeoutJob = scope.launch {
            delay(CHANNEL_LOAD_TIMEOUT_MS)
            logWarning("Load timeout (${CHANNEL_LOAD_TIMEOUT_MS}ms), trying next source")
            playNextSourceForCurrentChannel("load_timeout")
        }
    }

    private fun isUnrecognizedInputFormat(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message?.contains("UnrecognizedInputFormatException") == true ||
                current.javaClass.name.contains("UnrecognizedInputFormatException")
            ) return true
            current = current.cause
        }
        return false
    }

    companion object {
        private const val CHANNEL_ZAP_DEBOUNCE_MS = 300L
        private const val BUFFERING_FAILOVER_MS = 35000L
        private const val SINGLE_SOURCE_RETRY_BASE_MS = 5000L
        private const val SINGLE_SOURCE_RETRY_MAX_MS = 15000L
        private const val SINGLE_SOURCE_RETRY_MIN_GAP_MS = 12000L
        private const val SINGLE_SOURCE_RETRY_MAX_COUNT = 3
        // P2: 15s 初始加载超时
        private const val CHANNEL_LOAD_TIMEOUT_MS = 15000L
    }
}
