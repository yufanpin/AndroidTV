package com.tivimatelite

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import java.io.FileNotFoundException
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

    private var channelGroups: List<ChannelGroup> = emptyList()
    private var currentChannelIndex = -1
    private var currentSourceIndex = 0
    private val attemptedSourceIndexes = HashSet<Int>(8)
    private var backendInfoHideJob: Job? = null

    private val playerErrorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error, trying next source", error)
            playNextSourceForCurrentChannel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val player = PlayerManager.getPlayer(this)
        player.addListener(playerErrorListener)
        binding.playerView.player = player
        binding.playerView.requestFocus()

        loadChannels()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                switchChannel(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                switchChannel(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO -> {
                toggleBackendInfo()
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
        binding.playerView.player?.removeListener(playerErrorListener)
        binding.playerView.player = null
        backendInfoHideJob?.cancel()
        scope.cancel()
        if (isFinishing) PlayerManager.release()
        super.onDestroy()
    }

    private fun loadChannels() {
        scope.launch {
            val channels = loadChannelRows()
            channelGroups = groupChannels(channels)

            if (channelGroups.isEmpty()) {
                Log.e(TAG, "No channels available")
                return@launch
            }

            restoreLastPlayedChannel()
            playCurrentSource(resetAttempts = true)
        }
    }

    private suspend fun loadChannelRows(): List<Channel> {
        val remoteChannels = RemotePlaylistRepository.loadChannels()
        if (!remoteChannels.isNullOrEmpty()) {
            Log.i(TAG, "Loaded channels from remote backend")
            return remoteChannels
        }

        return try {
            val channels = ArrayList<Channel>(512)
            assets.open("channels.m3u").use { input ->
                M3U8Parser.parse(input).collect { channel ->
                    channels.add(channel)
                }
            }
            Log.i(TAG, "Loaded channels from local asset")
            channels
        } catch (exception: FileNotFoundException) {
            Log.w(TAG, "channels.m3u asset not found", exception)
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

        return grouped.entries.map { entry ->
            ChannelGroup(name = entry.key, sources = entry.value.toList())
        }
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

    private fun switchChannel(delta: Int) {
        if (channelGroups.isEmpty()) return
        if (currentChannelIndex < 0) {
            currentChannelIndex = 0
            currentSourceIndex = 0
        } else {
            val size = channelGroups.size
            currentChannelIndex = (currentChannelIndex + delta + size) % size
            currentSourceIndex = 0
        }
        playCurrentSource(resetAttempts = true)
    }

    private fun playCurrentSource(resetAttempts: Boolean) {
        if (currentChannelIndex !in channelGroups.indices) return

        val group = channelGroups[currentChannelIndex]
        if (group.sources.isEmpty()) return

        if (currentSourceIndex !in group.sources.indices) {
            currentSourceIndex = 0
        }

        if (resetAttempts) attemptedSourceIndexes.clear()
        attemptedSourceIndexes.add(currentSourceIndex)

        val sourceUrl = group.sources[currentSourceIndex]
        try {
            PlayerManager.play(this, sourceUrl)
            PlaybackHistoryStore.saveLastPlayedChannel(
                context = this,
                channelName = group.name,
                sourceIndex = currentSourceIndex,
                url = sourceUrl
            )
            Log.i(TAG, "Playing ${group.name} source ${currentSourceIndex + 1}/${group.sources.size}")
        } catch (exception: Throwable) {
            Log.e(TAG, "playCurrentSource failed", exception)
            playNextSourceForCurrentChannel()
        }
    }

    private fun toggleBackendInfo() {
        if (binding.backendInfoText.visibility == android.view.View.VISIBLE) {
            hideBackendInfo()
            return
        }

        val backendUrl = BuildConfig.PLAYLIST_URL.trim()
        val text = if (backendUrl.isEmpty()) {
            "Backend URL: (not configured)\nSource: local assets/channels.m3u"
        } else {
            "Backend URL: $backendUrl"
        }

        binding.backendInfoText.text = text
        Log.i(TAG, text.replace('\n', ' '))
        binding.backendInfoText.visibility = android.view.View.VISIBLE
        backendInfoHideJob?.cancel()
        backendInfoHideJob = scope.launch {
            delay(BACKEND_INFO_AUTO_HIDE_MS)
            hideBackendInfo()
        }
    }

    private fun hideBackendInfo() {
        backendInfoHideJob?.cancel()
        binding.backendInfoText.visibility = android.view.View.GONE
        Log.i(TAG, "Backend info hidden")
    }

    private fun playNextSourceForCurrentChannel() {
        if (currentChannelIndex !in channelGroups.indices) return
        val group = channelGroups[currentChannelIndex]
        if (group.sources.size < 2) return

        for (offset in 1 until group.sources.size) {
            val candidateIndex = (currentSourceIndex + offset) % group.sources.size
            if (attemptedSourceIndexes.contains(candidateIndex)) continue

            currentSourceIndex = candidateIndex
            playCurrentSource(resetAttempts = false)
            return
        }

        Log.e(TAG, "All sources failed for channel: ${group.name}")
    }

    private data class ChannelGroup(
        val name: String,
        val sources: List<String>
    )

    companion object {
        private const val TAG = "MainActivity"
        private const val BACKEND_INFO_AUTO_HIDE_MS = 4000L
    }
}
