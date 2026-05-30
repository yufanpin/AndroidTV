package com.tivimatelite.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.tivimatelite.web.AppLogStore

@UnstableApi
object PlayerManager {
    private const val TAG = "PlayerManager"
    private const val MIN_BUFFER_MS = 5_000
    private const val MAX_BUFFER_MS = 20_000
    private const val PLAYBACK_BUFFER_MS = 2_000
    private const val REBUFFER_MS = 5_000
    private const val USER_AGENT = "TiviMateLite/1.0 (AndroidTV; ExoPlayer)"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 12_000

    @Volatile
    private var player: ExoPlayer? = null

    fun getPlayer(context: Context): ExoPlayer {
        return player ?: synchronized(this) {
            player ?: buildPlayer(context.applicationContext).also { player = it }
        }
    }

    fun play(context: Context, url: String, forceHls: Boolean = false) {
        val exoPlayer = getPlayer(context)
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        if (forceHls) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            AppLogStore.i(TAG, "Force HLS retry for current source")
        }
        exoPlayer.setMediaItem(mediaItemBuilder.build())
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        player?.pause()
    }

    fun release() {
        synchronized(this) {
            player?.release()
            player = null
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(context: Context): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                PLAYBACK_BUFFER_MS,
                REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            )

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val audioSink = DefaultAudioSink.Builder(context)
            .setEnableAudioTrackPlaybackParams(true)
            .build()
        val renderersFactory = DefaultRenderersFactory(context)
            .setAudioSink(audioSink)

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                addListener(playbackListener)
                addAnalyticsListener(codecAnalyticsListener)
            }
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (isCodecFailure(error)) {
                AppLogStore.e(TAG, "Hardware decoder failure captured", error)
                player?.stop()
                return
            }
            AppLogStore.e(TAG, "Playback error captured", error)
        }
    }

    private val codecAnalyticsListener = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: java.io.IOException,
            wasCanceled: Boolean
        ) {
            AppLogStore.w(TAG, "Stream load error captured", error)
        }
    }

    private fun isCodecFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val name = current.javaClass.name
            if (name.contains("MediaCodec", ignoreCase = true) ||
                name.contains("MediaCodecVideoRenderer", ignoreCase = true) ||
                name.contains("DecoderInitializationException", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
