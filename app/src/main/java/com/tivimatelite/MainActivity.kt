package com.tivimatelite

import android.net.TrafficStats
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.SurfaceHolder
import androidx.appcompat.app.AppCompatActivity
import com.tivimatelite.databinding.ActivityMainBinding
import com.tivimatelite.input.InputHandler
import com.tivimatelite.loader.ChannelGroup
import com.tivimatelite.loader.ChannelLoader
import com.tivimatelite.monitor.PlayerSnapshot
import com.tivimatelite.monitor.ReadyStallWatch
import com.tivimatelite.player.PlayableHostStore
import com.tivimatelite.player.PlayerManager
import com.tivimatelite.player.PlaybackHistoryStore
import com.tivimatelite.switcher.ChannelSwitcher
import com.tivimatelite.web.AppLogStore
import com.tivimatelite.web.AudioGainPrefs
import com.tivimatelite.web.FileLogStore
import com.tivimatelite.web.LocalAdminServerManager
import com.tivimatelite.ysp.YspConfig
import com.tivimatelite.ysp.YspEngine
import com.tivimatelite.ysp.YspEngineService
import com.tivimatelite.ysp.YspEvents
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val scope = MainScope()

    private var channelGroups: List<ChannelGroup> = emptyList()
    private var currentChannelIndex = -1
    private var currentSourceIndex = 0
    private var backendInfoHideJob: Job? = null
    private var reloadChannelsJob: Job? = null
    private lateinit var inputHandler: InputHandler
    private lateinit var channelLoader: ChannelLoader
    private lateinit var channelSwitcher: ChannelSwitcher
    private lateinit var readyStallWatch: ReadyStallWatch
    private var readyStallIgnoreUntilMs = 0L
    private var lastReadyStallRecoveryAtMs = 0L
    private var lastPlaylistFingerprint: String? = null
    private var activePlaylistSource = "ysp 本机引擎"
    private var channelNameHideJob: Job? = null

    /** SurfaceHolder.Callback ȷ�����洴��/�ؽ�ʱ������ʱ��棬�ƹ� Amlogic HWC ������� bug */
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            PlayerManager.getPlayer(this@MainActivity)
            PlayerManager.setSurface(holder.surface)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            PlayerManager.setSurface(null)
        }
    }

    private val playerListener = object : PlayerManager.Events {
        override fun onError(errorWhat: Int, errorExtra: Int) {
            FileLogStore.w(TAG, "onError what=$errorWhat extra=$errorExtra")
            cancelReadyStallWatch()
            // P0: 记忆移除——线路播放失�?
            val currentUrl = PlayerManager.currentUrl
            if (currentUrl != null) {
                PlayableHostStore.removeHost(this@MainActivity, currentUrl)
                AppLogStore.i(TAG, "Removed failed host from playable hosts: $currentUrl")
            }
            AppLogStore.w(TAG, "Playback error, retrying current channel")
            // ysp 只有一条线路：出错只重连当前频道，绝不跳到下一个台
            channelSwitcher.retryCurrentSingleSource("player_error")
        }

        override fun onStateChanged(state: PlayerManager.PlaybackState) {
            FileLogStore.i(TAG, "onStateChanged: $state")
            when (state) {
                PlayerManager.PlaybackState.BUFFERING -> {
                    cancelReadyStallWatch()
                    channelSwitcher.scheduleBufferingFailover()
                }
                PlayerManager.PlaybackState.READY -> {
                    channelSwitcher.cancelBufferingFailover()
                    channelSwitcher.cancelLoadTimeout()
                    channelSwitcher.resetRetryState()
                    startReadyStallWatch()
                    binding.engineStatusText.visibility = View.GONE
                    YspEvents.record(currentChannelName(), YspEvents.STAGE_PLAYER, "播放器 READY")
                    // P0: 线路播放成功→记忆域�?
                    val url = PlayerManager.currentUrl
                    if (url != null) {
                        PlayableHostStore.addHost(this@MainActivity, url)
                    }
                }
                PlayerManager.PlaybackState.ENDED -> {
                    cancelReadyStallWatch()
                    channelSwitcher.retryCurrentSingleSource("state_ended")
                }
                PlayerManager.PlaybackState.IDLE -> {}
            }
        }

        override fun onVideoSizeChanged(width: Int, height: Int, sarNum: Int, sarDen: Int) {
            FileLogStore.i(TAG, "onVideoSizeChanged: ${width}x$height sar=$sarNum:$sarDen")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            FileLogStore.i(TAG, "onIsPlayingChanged: $isPlaying")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogStore.init(this)
        FileLogStore.i(TAG, "onCreate savedInstanceState=$savedInstanceState")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.playerSurface.setZOrderMediaOverlay(true)
        binding.backendInfoText.bringToFront()
        binding.channelNumberText.bringToFront()
        binding.channelNameText.bringToFront()
        binding.engineStatusText.bringToFront()
        binding.netSpeedText.bringToFront()

        YspEngineService.start(this)
        LocalAdminServerManager.start(this)
        AppLogStore.i(TAG, "Admin ready at ${LocalAdminServerManager.getAdminUrl()}")

        channelLoader = ChannelLoader(this)
        channelSwitcher = ChannelSwitcher(
            scope = scope,
            getChannelGroups = { channelGroups },
            getCurrentChannelIndex = { currentChannelIndex },
            setCurrentChannelIndex = { currentChannelIndex = it },
            getCurrentSourceIndex = { currentSourceIndex },
            setCurrentSourceIndex = { currentSourceIndex = it },
            getActivePlaylistSource = { activePlaylistSource },
            showChannelNumberOverlay = { text ->
                inputHandler.showChannelNumberOverlay(text)
                showChannelNameFor(text)
            },
            onReadyStallWarmup = {
                cancelReadyStallWatch()
                readyStallIgnoreUntilMs = System.currentTimeMillis() + READY_STALL_WARMUP_MS
            },
            savePlayback = { channelName, sourceIndex, url ->
                PlaybackHistoryStore.saveLastPlayedChannel(this, channelName, sourceIndex, url)
            },
            playUrl = { url, forceHls -> PlayerManager.play(this, url, forceHls) },
            logInfo = { message ->
                AppLogStore.i(TAG, message)
                FileLogStore.i(TAG, message)
            },
            logWarning = { message -> AppLogStore.w(TAG, message) },
            logError = { message ->
                FileLogStore.e(TAG, message)
                AppLogStore.e(TAG, message)
            },
            getNowMs = { System.currentTimeMillis() },
            isPlayerBufferingAndPlaying = {
                PlayerManager.playbackState == PlayerManager.PlaybackState.BUFFERING && PlayerManager.playWhenReady
            },
            getPlayableHosts = { PlayableHostStore.getHosts(this@MainActivity) }
        )
        readyStallWatch = ReadyStallWatch(
            scope = scope,
            getPlayerSnapshot = {
                PlayerSnapshot(
                    isReady = PlayerManager.playbackState == PlayerManager.PlaybackState.READY,
                    playWhenReady = PlayerManager.playWhenReady,
                    currentPositionMs = PlayerManager.currentPositionMs(),
                    isPlaying = PlayerManager.isPlaying(),
                    bufferedPositionMs = PlayerManager.bufferedPositionMs()
                )
            },
            getNowMs = { System.currentTimeMillis() },
            getTotalRxBytes = { TrafficStats.getTotalRxBytes() },
            getPlaylistFingerprint = { YspConfig.load(this).restartFingerprint() },
            onPlaylistChanged = {
                lastPlaylistFingerprint = YspConfig.load(this).restartFingerprint()
                AppLogStore.i(TAG, "Playlist config changed, reloading channels")
                reloadChannelsKeepingCurrent()
            },
            getAudioGain = { AudioGainPrefs.loadGain(this) },
            onAudioGainChanged = { newGain ->
                PlayerManager.setAudioGain(newGain)
                if (PlayerManager.currentUrl != null && PlayerManager.playWhenReady) {
                    // IJK 的 af 选项只在 prepare 时读取，播放中必须重播才会套用新增益
                    AppLogStore.i(TAG, "Audio gain -> $newGain, replaying current source")
                    FileLogStore.i(TAG, "Audio gain -> $newGain, replaying current source")
                    channelSwitcher.playCurrentSource(resetAttempts = true)
                } else {
                    AppLogStore.i(TAG, "Audio gain -> $newGain, will apply on next play")
                    FileLogStore.i(TAG, "Audio gain -> $newGain, will apply on next play")
                }
            },
            onReadyStallDetected = { reason ->
                lastReadyStallRecoveryAtMs = System.currentTimeMillis()
                channelSwitcher.retryCurrentSingleSource(reason)
            },
            onSpeedText = { text -> binding.netSpeedText.text = text },
            onHeartbeat = { message -> FileLogStore.i(TAG, message) },
            logWarning = { message -> AppLogStore.w(TAG, message) }
        )
        inputHandler = InputHandler(
            scope = scope,
            channelCountProvider = { channelGroups.size },
            onChannelRequest = { targetIndex -> channelSwitcher.switchChannelImmediately(targetIndex) },
            channelNumberText = binding.channelNumberText
        )

        PlayerManager.setEvents(playerListener)
        // Register SurfaceHolder.Callback ONCE �� never removed, ensures surface lifecycle is tracked
        binding.playerSurface.holder.addCallback(surfaceCallback)
        attachPlayerSurface()

        // 先于首次 prepare 应用持久化增益，保证开机首个频道就带正确滤镜
        PlayerManager.setAudioGain(AudioGainPrefs.loadGain(this))
        lastPlaylistFingerprint = YspConfig.load(this).restartFingerprint()
        binding.engineStatusText.visibility = View.VISIBLE
        loadChannels()
        readyStallWatch.startPlaylistWatcher(lastPlaylistFingerprint.orEmpty())
        readyStallWatch.startNetworkSpeedMonitor()
        readyStallWatch.startHeartbeat()
        readyStallWatch.startAudioGainWatcher(AudioGainPrefs.loadGain(this))
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
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                AppLogStore.i(TAG, "User reconnect current channel")
                FileLogStore.i(TAG, "User reconnect current channel")
                showReconnectBadge()
                channelSwitcher.reconnectCurrentNow("user_reconnect")
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
                inputHandler.handleKeyCode(event.keyCode)
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onStart() {
        super.onStart()
        FileLogStore.i(TAG, "onStart")
        attachPlayerSurface()
    }

    override fun onStop() {
        FileLogStore.i(TAG, "onStop")
        // Keep SurfaceHolder.Callback registered - it will receive surfaceCreated on resume
        super.onStop()
        PlayerManager.pause()
    }

    override fun onDestroy() {
        FileLogStore.i(TAG, "onDestroy isFinishing=$isFinishing")
        PlayerManager.setEvents(null)
        PlayerManager.setSurface(null)
        binding.playerSurface.alpha = 0f
        backendInfoHideJob?.cancel()
        reloadChannelsJob?.cancel()
        inputHandler.cancel()
        channelSwitcher.cancel()
        readyStallWatch.cancel()
        scope.cancel()
        if (isFinishing) {
            PlayerManager.release()
            LocalAdminServerManager.stop()
            YspEngineService.stop(this)
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            FileLogStore.w(TAG, "onTrimMemory CRITICAL: $level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        FileLogStore.w(TAG, "onLowMemory")
    }

    private fun loadChannels() {
        scope.launch {
            val deadline = System.currentTimeMillis() + ENGINE_WAIT_TIMEOUT_MS
            var result: ChannelLoader.LoadResult? = null
            while (System.currentTimeMillis() < deadline) {
                val engine = YspEngine.state.value
                when {
                    engine.failed -> {
                        setEngineStatus("引擎启动失败：${engine.error.ifBlank { "未知错误" }}")
                        AppLogStore.e(TAG, "Engine failed: ${engine.error}")
                        return@launch
                    }
                    engine.ready -> {
                        setEngineStatus("正在加载频道…")
                        result = channelLoader.loadInitial()
                        if (result != null) break
                    }
                    else -> setEngineStatus(
                        engine.stage.ifBlank { "正在启动直播引擎…" }
                    )
                }
                delay(400)
            }

            if (result == null) {
                setEngineStatus("引擎未就绪，请访问 设备IP:${LocalAdminServerManager.getPort()} 查看日志")
                AppLogStore.e(TAG, "Engine not ready, no channels loaded")
                FileLogStore.e(TAG, "Engine not ready, no channels loaded")
                return@launch
            }

            channelGroups = result.channelGroups
            currentChannelIndex = result.selection.channelIndex
            currentSourceIndex = result.selection.sourceIndex
            activePlaylistSource = result.activePlaylistSource

            val msg = "Loaded ${channelGroups.size} channel groups from ${result.activePlaylistSource}"
            AppLogStore.i(TAG, msg)
            FileLogStore.i(TAG, msg)
            channelSwitcher.playCurrentSource(resetAttempts = true)
        }
    }

    private fun setEngineStatus(text: String) {
        binding.engineStatusText.text = text
        binding.engineStatusText.visibility = View.VISIBLE
    }

    private fun currentChannelName(): String =
        channelGroups.getOrNull(currentChannelIndex)?.name.orEmpty()

    /** 换台/数字选台时在左下角显示台名。 */
    private fun showChannelNameFor(text: String) {
        val index = text.trim().toIntOrNull()?.minus(1)
        val name = index?.let { channelGroups.getOrNull(it)?.name }
        if (name.isNullOrBlank()) {
            binding.channelNameText.visibility = View.GONE
            return
        }
        binding.channelNameText.text = name
        binding.channelNameText.visibility = View.VISIBLE
        channelNameHideJob?.cancel()
        channelNameHideJob = scope.launch {
            delay(CHANNEL_NAME_HIDE_MS)
            binding.channelNameText.visibility = View.GONE
        }
    }

    private fun showReconnectBadge() {
        binding.channelNameText.text = "重连当前频道"
        binding.channelNameText.visibility = View.VISIBLE
        channelNameHideJob?.cancel()
        channelNameHideJob = scope.launch {
            delay(CHANNEL_NAME_HIDE_MS)
            binding.channelNameText.visibility = View.GONE
        }
    }

    private fun reloadChannelsKeepingCurrent() {
        if (reloadChannelsJob?.isActive == true) return

        reloadChannelsJob = scope.launch {
            val result = channelLoader.reloadKeepingCurrent(
                currentGroups = channelGroups,
                currentChannelIndex = currentChannelIndex,
                currentSourceIndex = currentSourceIndex
            )

            if (result == null) {
                AppLogStore.e(TAG, "Reload failed: no channels available")
                return@launch
            }

            channelGroups = result.channelGroups
            currentChannelIndex = result.selection.channelIndex
            currentSourceIndex = result.selection.sourceIndex
            activePlaylistSource = result.activePlaylistSource
            result.fallbackChannelName?.let { fallbackName ->
                AppLogStore.w(TAG, "Previous channel not found after reload, switched to $fallbackName")
            }
            channelSwitcher.playCurrentSource(resetAttempts = true)
        }
    }

    private fun requestSwitchByDelta(delta: Int) {
        channelSwitcher.requestSwitchByDelta(delta)
    }

    private fun startReadyStallWatch() {
        readyStallWatch.setReadyStallIgnoreUntilMs(readyStallIgnoreUntilMs)
        readyStallWatch.startReadyStallWatch(lastReadyStallRecoveryAtMs)
    }

    private fun cancelReadyStallWatch() {
        readyStallWatch.cancelReadyStallWatch()
    }

    private fun attachPlayerSurface() {
        // post ensures the SurfaceView surface is fully created before we bind
        // Using setVideoSurface(holder.surface) directly instead of setVideoSurfaceView()
        // to bypass the Amlogic phantom-surface bug where holder.getSurface() returns stale surface
        binding.playerSurface.post {
            PlayerManager.getPlayer(this@MainActivity)
            val surface = binding.playerSurface.holder.surface
            if (surface?.isValid == true) {
                PlayerManager.setSurface(surface)
            }
            binding.playerSurface.requestFocus()
        }
    }

    private fun toggleBackendInfo() {
        if (binding.backendInfoText.visibility == View.VISIBLE) {
            hideBackendInfo()
            return
        }

        val engine = YspEngine.state.value
        val text = buildString {
            append("Engine: ")
            append(if (engine.running) "ready :${engine.port}" else "starting")
            append('\n')
            append("Channels: ")
            append(channelGroups.size)
            append('\n')
            append("Admin URL: ")
            append(LocalAdminServerManager.getAdminUrl())
            append('\n')
            append("Active source: ")
            append(channelSwitcher.describeActiveSource())
        }

        binding.backendInfoText.text = text
        AppLogStore.i(TAG, text.replace('\n', ' '))
        binding.backendInfoText.bringToFront()
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

    companion object {
        private const val TAG = "MainActivity"
        private const val BACKEND_INFO_AUTO_HIDE_MS = 4000L
        private const val READY_STALL_WARMUP_MS = 60000L
        private const val ENGINE_WAIT_TIMEOUT_MS = 25000L
        private const val CHANNEL_NAME_HIDE_MS = 1800L
    }
}
