package com.tivimatelite.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.tivimatelite.web.AppLogStore

object PlayerManager {
    private const val TAG = "PlayerManager"
    private const val USER_AGENT = "TiviMateLite/1.0 (AndroidTV; ExoPlayer)"
    private const val CONNECT_TIMEOUT_MS = 15_000  // P0: 放宽到 15s 防止慢源误判
    private const val READ_TIMEOUT_MS = 20_000     // P0: 放宽到 20s

    enum class BufferProfile(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val playbackBufferMs: Int,
        val rebufferMs: Int
    ) {
        FAST_SWITCH(5_000, 20_000, 2_000, 3_000),
        BALANCED(15_000, 45_000, 3_000, 8_000),
        STABLE(30_000, 90_000, 5_000, 15_000)
    }

    enum class DecoderFallbackPolicy(
        val extensionRendererMode: Int,
        val enableDecoderFallback: Boolean
    ) {
        HW_ONLY(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, true),
        HW_WITH_SW_FALLBACK(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, true),
        SW_PREFERRED(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, true)
    }

    @Volatile
    private var bufferProfile = BufferProfile.BALANCED

    @Volatile
    private var decoderFallbackPolicy = DecoderFallbackPolicy.HW_ONLY

    @Volatile
    private var player: ExoPlayer? = null
    // 保存 applicationContext 供异步回调使用（ExoPlayer 接口无 applicationContext）
    private var appContext: Context? = null

    // P1: 记录当前 URL 已尝试过的内容类型（用于 PARSING_CONTAINER_UNSUPPORTED 重试）
    private val contentTypeAttempts = mutableMapOf<String, MutableSet<Int>>()
    // P5: 解码器元数据（最新）
    @Volatile
    var lastDecoderMetadata: DecoderMetadata = DecoderMetadata()
        private set

    data class DecoderMetadata(
        val videoMimeType: String = "",
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val videoFrameRate: Float = 0f,
        val videoBitrate: Int = 0,
        val videoDecoder: String = "",
        val audioMimeType: String = "",
        val audioChannels: Int = 0,
        val audioSampleRate: Int = 0,
        val audioDecoder: String = ""
    )

    fun getPlayer(context: Context): ExoPlayer {
        appContext = context.applicationContext
        return player ?: synchronized(this) {
            player ?: buildPlayer(context.applicationContext).also { player = it }
        }
    }

    @OptIn(UnstableApi::class)
    fun play(context: Context, url: String, forceHls: Boolean = false) {
        val exoPlayer = getPlayer(context)

        // P1: 记录当前 URL（用于容器类型重试）
        val mediaItemBuilder = MediaItem.Builder().setUri(url)

        // 清除旧 URL 的内容类型尝试记录
        contentTypeAttempts.keys.removeAll { it != url }

        // 确定内容类型
        val contentType: Int = when {
            forceHls -> {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                AppLogStore.i(TAG, "Force HLS for current source")
                C.CONTENT_TYPE_HLS
            }
            else -> C.CONTENT_TYPE_OTHER // 让 ExoPlayer 自动推断
        }

        val mediaItem = mediaItemBuilder.build()
        contentTypeAttempts.getOrPut(url) { mutableSetOf() }.add(contentType)

        // 使用 MediaSource 来支持容器类型重试
        if (contentType != C.CONTENT_TYPE_OTHER) {
            val dataSourceFactory = DefaultDataSource.Factory(
                context,
                DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                    .setReadTimeoutMs(READ_TIMEOUT_MS)
                    .setKeepPostFor302Redirects(true)
                    .setDefaultRequestProperties(mapOf("Accept" to "*/*", "Connection" to "keep-alive"))
            )
            val mediaSource = when (contentType) {
                C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
                else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            exoPlayer.setMediaSource(mediaSource)
        } else {
            exoPlayer.setMediaItem(mediaItem)
        }

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        player?.pause()
    }

    fun setBufferProfile(profile: BufferProfile) {
        bufferProfile = profile
    }

    fun getBufferProfile(): BufferProfile = bufferProfile

    fun setDecoderFallbackPolicy(policy: DecoderFallbackPolicy) {
        decoderFallbackPolicy = policy
    }

    fun getDecoderFallbackPolicy(): DecoderFallbackPolicy = decoderFallbackPolicy

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
                bufferProfile.minBufferMs,
                bufferProfile.maxBufferMs,
                bufferProfile.playbackBufferMs,
                bufferProfile.rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            )

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        // P4: 使用 DefaultRenderersFactory
        // NextLib FFmpeg 软解在 S905L3 (2GB) 上 CPU 无法实时出帧，弃用
        // 依赖硬件解码器（OMX.amlogic.*）确保视频正常渲染
        val renderersFactory = AmlogicRenderersFactory(context).apply {
            setExtensionRendererMode(decoderFallbackPolicy.extensionRendererMode)
            setEnableDecoderFallback(decoderFallbackPolicy.enableDecoderFallback)
            enableAudioOutputPlaybackParameters(this)
        }

        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setRenderersFactory(renderersFactory)
            .build()
            .apply {
                addListener(playbackListener)
                addAnalyticsListener(codecAnalyticsListener)
                // P5: 解码器信息监听器
                addAnalyticsListener(decoderInfoListener)
            }
    }

    private fun enableAudioOutputPlaybackParameters(renderersFactory: DefaultRenderersFactory) {
        val factoryClass = renderersFactory.javaClass
        val booleanType = Boolean::class.javaPrimitiveType ?: return
        val methodNames = listOf(
            "setEnableAudioOutputPlaybackParameters",
            "setEnableAudioTrackPlaybackParams"
        )

        for (methodName in methodNames) {
            val method = runCatching { factoryClass.getMethod(methodName, booleanType) }.getOrNull()
                ?: continue
            runCatching { method.invoke(renderersFactory, true) }
                .onSuccess {
                    AppLogStore.i(TAG, "Enabled audio playback params via $methodName")
                }
                .onFailure {
                    AppLogStore.w(TAG, "Failed to enable audio playback params via $methodName", it)
                }
            return
        }

        AppLogStore.w(TAG, "Audio playback params API not available in current Media3 build")
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (isCodecFailure(error)) {
                AppLogStore.e(TAG, "Hardware decoder failure captured", error)
                player?.stop()
                return
            }

            // P1: BEHIND_LIVE_WINDOW → 跳到默认位置重试
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                AppLogStore.w(TAG, "Behind live window, seeking to default position")
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }

            // P1: PARSING_CONTAINER_UNSUPPORTED → 换内容类型重试
            val currentUrl = player?.currentMediaItem?.localConfiguration?.uri?.toString()
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                && currentUrl != null
            ) {
                val attempts = contentTypeAttempts.getOrPut(currentUrl) { mutableSetOf() }
                val nextType = when {
                    C.CONTENT_TYPE_HLS !in attempts -> C.CONTENT_TYPE_HLS
                    C.CONTENT_TYPE_OTHER !in attempts -> C.CONTENT_TYPE_OTHER
                    else -> null
                }
                if (nextType != null) {
                    AppLogStore.w(TAG, "Container unsupported, retrying as type=$nextType")
                    val context = appContext ?: return
                    play(context, currentUrl, forceHls = nextType == C.CONTENT_TYPE_HLS)
                    return
                }
            }

            AppLogStore.e(TAG, "Playback error captured", error)
        }

        override fun onPlaybackStateChanged(playbackState: Int) = Unit

        override fun onRenderedFirstFrame() {
            AppLogStore.i(TAG, "Rendered first frame")
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            AppLogStore.i(TAG, "Video size changed: ${videoSize.width}x${videoSize.height}")
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

    // P5: 解码器信息采集
    @OptIn(UnstableApi::class)
    private val decoderInfoListener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            lastDecoderMetadata = lastDecoderMetadata.copy(
                videoMimeType = format.sampleMimeType ?: "",
                videoWidth = format.width,
                videoHeight = format.height,
                videoFrameRate = format.frameRate,
                videoBitrate = format.bitrate,
            )
            AppLogStore.i(TAG, "Video: ${format.sampleMimeType} ${format.width}x${format.height} " +
                    "${format.frameRate}fps ${format.bitrate}bps")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            lastDecoderMetadata = lastDecoderMetadata.copy(videoDecoder = decoderName)
            AppLogStore.i(TAG, "Video decoder: $decoderName (init=${initializationDurationMs}ms)")
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            lastDecoderMetadata = lastDecoderMetadata.copy(
                audioMimeType = format.sampleMimeType ?: "",
                audioChannels = format.channelCount,
                audioSampleRate = format.sampleRate,
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            lastDecoderMetadata = lastDecoderMetadata.copy(audioDecoder = decoderName)
            AppLogStore.i(TAG, "Audio decoder: $decoderName (init=${initializationDurationMs}ms)")
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
