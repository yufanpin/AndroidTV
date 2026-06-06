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
import com.tivimatelite.web.FileLogStore
import com.tivimatelite.web.LocalAdminServerManager
import com.tivimatelite.web.PlaylistStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val scope = MainScope()
    private lateinit var audioManager: AudioManager

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
    private var activePlaylistSource = "local assets/channels.m3u"

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            FileLogStore.w(TAG, "onPlayerError: errorCode=${error.errorCode} ${error.localizedMessage}")
            cancelReadyStallWatch()
            // P0: 记忆移除——线路播放失败
            val currentUrl = PlayerManager.getPlayer(this@MainActivity).currentMediaItem?.localConfiguration?.uri?.toString()
            if (currentUrl != null) {
                PlayableHostStore.removeHost(this@MainActivity, currentUrl)
                AppLogStore.i(TAG, "Removed failed host from playable hosts: $currentUrl")
            }
            if (channelSwitcher.tryForceHlsForCurrentSource(error)) return
            AppLogStore.w(TAG, "Playback error, trying next source", error)
            channelSwitcher.playNextSourceForCurrentChannel("player_error")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            FileLogStore.i(TAG, "onPlaybackStateChanged: $playbackState")
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    cancelReadyStallWatch()
                    channelSwitcher.scheduleBufferingFailover()
                }
                Player.STATE_READY -> {
                    channelSwitcher.cancelBufferingFailover()
                    channelSwitcher.cancelLoadTimeout()
                    startReadyStallWatch()
                    // P0: 线路播放成功→记忆域名
                    val player = PlayerManager.getPlayer(this@MainActivity)
                    val url = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (url != null) {
                        PlayableHostStore.addHost(this@MainActivity, url)
                    }
                }
                Player.STATE_ENDED -> {
                    cancelReadyStallWatch()
                    channelSwitcher.playNextSourceForCurrentChannel("state_ended")
                }
            }
        }

        override fun onPlayerErrorChanged(error: PlaybackException?) {
            FileLogStore.w(TAG, "onPlayerErrorChanged: ${error?.localizedMessage}")
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

        LocalAdminServerManager.start(this)
        AppLogStore.i(TAG, "Admin ready at ${LocalAdminServerManager.getAdminUrl()}")

        audioManager = getSystemService(AudioManager::class.java)
        channelLoader = ChannelLoader(this)
        channelSwitcher = ChannelSwitcher(
            scope = scope,
            getChannelGroups = { channelGroups },
            getCurrentChannelIndex = { currentChannelIndex },
            setCurrentChannelIndex = { currentChannelIndex = it },
            getCurrentSourceIndex = { currentSourceIndex },
            setCurrentSourceIndex = { currentSourceIndex = it },
            getActivePlaylistSource = { activePlaylistSource },
            showChannelNumberOverlay = { text -> inputHandler.showChannelNumberOverlay(text) },
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
                val player = PlayerManager.getPlayer(this@MainActivity)
                player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
            },
            getPlayableHosts = { PlayableHostStore.getHosts(this@MainActivity) }
        )
        readyStallWatch = ReadyStallWatch(
            scope = scope,
            getPlayerSnapshot = {
                val player = PlayerManager.getPlayer(this@MainActivity)
                PlayerSnapshot(
                    isReady = player.playbackState == Player.STATE_READY,
                    playWhenReady = player.playWhenReady,
                    currentPositionMs = player.currentPosition
                )
            },
            getNowMs = { System.currentTimeMillis() },
            getTotalRxBytes = { TrafficStats.getTotalRxBytes() },
            getPlaylistFingerprint = { PlaylistStore.getConfigFingerprint(this) },
            onPlaylistChanged = {
                lastPlaylistFingerprint = PlaylistStore.getConfigFingerprint(this)
                AppLogStore.i(TAG, "Playlist config changed, reloading channels")
                reloadChannelsKeepingCurrent()
            },
            onReadyStallDetected = { reason ->
                lastReadyStallRecoveryAtMs = System.currentTimeMillis()
                channelSwitcher.playNextSourceForCurrentChannel(reason)
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

        val player = PlayerManager.getPlayer(this)
        player.addListener(playerListener)
        attachPlayerSurface()

        lastPlaylistFingerprint = PlaylistStore.getConfigFingerprint(this)
        loadChannels()
        readyStallWatch.startPlaylistWatcher(lastPlaylistFingerprint.orEmpty())
        readyStallWatch.startNetworkSpeedMonitor()
        readyStallWatch.startHeartbeat()
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
        PlayerManager.getPlayer(this).clearVideoSurfaceView(binding.playerSurface)
        super.onStop()
        PlayerManager.pause()
    }

    override fun onDestroy() {
        FileLogStore.i(TAG, "onDestroy isFinishing=$isFinishing")
        val player = PlayerManager.getPlayer(this)
        player.removeListener(playerListener)
        player.clearVideoSurfaceView(binding.playerSurface)
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
            val result = channelLoader.loadInitial()
            if (result == null) {
                AppLogStore.e(TAG, "No channels available")
                FileLogStore.e(TAG, "No channels available")
                return@launch
            }

            channelGroups = result.channelGroups
            currentChannelIndex = result.selection.channelIndex
            currentSourceIndex = result.selection.sourceIndex
            activePlaylistSource = result.activePlaylistSource

            val msg = "Loaded ${channelGroups.size} channel groups"
            AppLogStore.i(TAG, msg)
            FileLogStore.i(TAG, msg)
            channelSwitcher.playCurrentSource(resetAttempts = true)
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

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun attachPlayerSurface() {
        val player = PlayerManager.getPlayer(this)
        binding.playerSurface.alpha = 1f
        player.clearVideoSurface()
        player.setVideoSurfaceView(binding.playerSurface)
        binding.playerSurface.requestFocus()
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
            append(channelSwitcher.describeActiveSource())
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

    companion object {
        private const val TAG = "MainActivity"
        private const val BACKEND_INFO_AUTO_HIDE_MS = 4000L
        private const val READY_STALL_WARMUP_MS = 60000L
    }
}
