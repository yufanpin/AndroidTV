package com.tivimatelite

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.tivimatelite.databinding.ActivityMainBinding
import com.tivimatelite.model.Channel
import com.tivimatelite.parser.M3U8Parser
import com.tivimatelite.player.PlaybackHistoryStore
import com.tivimatelite.player.PlayerManager
import com.tivimatelite.ui.ChannelAdapter
import java.io.FileNotFoundException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChannelAdapter
    private val scope = MainScope()
    private var tuneJob: Job? = null
    private var firstChannelStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.playerView.player = PlayerManager.getPlayer(this)
        setupChannelList()
        loadChannels()
        binding.playerView.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (isOverlayVisible()) {
                    hideOverlay()
                    true
                } else {
                    super.dispatchKeyEvent(event)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> handleRemoteKey(event.keyCode)
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onStop() {
        super.onStop()
        PlayerManager.pause()
    }

    override fun onDestroy() {
        binding.playerView.player = null
        tuneJob?.cancel()
        scope.cancel()
        if (isFinishing) PlayerManager.release()
        super.onDestroy()
    }

    private fun setupChannelList() {
        adapter = ChannelAdapter(Glide.with(this), ::onChannelFocused)
        binding.channelRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.channelRecyclerView.adapter = adapter
        binding.channelRecyclerView.itemAnimator = null
        binding.channelRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                adapter.setLogoLoadingEnabled(newState == RecyclerView.SCROLL_STATE_IDLE, recyclerView)
            }
        })
    }

    private fun loadChannels() {
        scope.launch {
            val channels = ArrayList<Channel>(256)
            val lastPlayedUrl = PlaybackHistoryStore.getLastPlayedUrl(this@MainActivity)
            var firstParsedChannel: Channel? = null
            try {
                assets.open("channels.m3u").use { input ->
                    M3U8Parser.parse(input).collect { channel ->
                        if (firstParsedChannel == null) firstParsedChannel = channel
                        channels.add(channel)
                        adapter.submitList(ArrayList(channels))
                        if (!firstChannelStarted && channel.streamUrl == lastPlayedUrl) {
                            startFirstChannel(channel)
                        }
                    }
                }
                if (!firstChannelStarted) {
                    firstParsedChannel?.let { startFirstChannel(it) }
                }
            } catch (exception: FileNotFoundException) {
                Log.w(TAG, "channels.m3u asset not found", exception)
                adapter.submitList(emptyList())
            }
        }
    }

    private fun handleRemoteKey(keyCode: Int): Boolean {
        if (!isOverlayVisible()) {
            showOverlay()
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
    }

    private fun onChannelFocused(channel: Channel) {
        binding.epgDetailsText.text = channel.epgText
        tuneJob?.cancel()
        tuneJob = scope.launch {
            delay(KEY_DEBOUNCE_MS)
            playChannel(channel)
        }
    }

    private fun startFirstChannel(channel: Channel) {
        if (firstChannelStarted) return
        firstChannelStarted = true
        binding.epgDetailsText.text = channel.epgText
        playChannel(channel)
    }

    private fun playChannel(channel: Channel) {
        PlayerManager.play(this, channel.streamUrl)
        adapter.setSelectedUrl(channel.streamUrl)
        PlaybackHistoryStore.saveLastPlayedUrl(this, channel.streamUrl)
    }

    private fun showOverlay() {
        binding.overlayPanel.visibility = View.VISIBLE
        binding.channelRecyclerView.requestFocus()
        if (adapter.itemCount > 0) {
            binding.channelRecyclerView.post {
                binding.channelRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun hideOverlay() {
        binding.overlayPanel.visibility = View.GONE
        binding.playerView.requestFocus()
    }

    private fun isOverlayVisible(): Boolean {
        return binding.overlayPanel.visibility == View.VISIBLE
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_DEBOUNCE_MS = 300L
    }
}
