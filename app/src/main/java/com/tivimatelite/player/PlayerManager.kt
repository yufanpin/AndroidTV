package com.tivimatelite.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultLoadControl
import com.tivimatelite.web.AppLogStore

/**
 * 播放内核：Media3 ExoPlayer（HLS）。移植自 ysp-live-android 验证过的方案。
 *
 * 相比旧的 IJKPlayer：
 * - 前向 15s / 后向 30s 缓冲（DefaultLoadControl），抗 CDN 抖动，不再几秒一卡；
 * - setAllowChunklessPreparation(true) 秒开；
 * - 分片走本机 8767 代理（引擎缓存 + 稳定取流），不经 IJK 的完整 GET 限速。
 *
 * 对外 API 保持与原 IJK 版一致，UI / Web 后台 / ChannelSwitcher 无需改动。
 */
object PlayerManager {
    private const val TAG = "PlayerManager"

    private const val USER_AGENT = "SYTV/1.6"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    // 直播稳定性优先：前向 15s、后向 30s
    private const val BUFFER_FORWARD_MS = 15_000L
    private const val BUFFER_BACK_MS = 30_000L

    /** 默认音量倍数（1.0 = 原始）。 */
    const val DEFAULT_AUDIO_GAIN = 1.0f
    const val AUDIO_GAIN_MIN = 0.5f
    const val AUDIO_GAIN_MAX = 2.0f

    fun clampAudioGain(value: Float): Float = value.coerceIn(AUDIO_GAIN_MIN, AUDIO_GAIN_MAX)

    enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

    interface Events {
        fun onError(errorWhat: Int, errorExtra: Int)
        fun onStateChanged(state: PlaybackState)
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onVideoSizeChanged(width: Int, height: Int, sarNum: Int, sarDen: Int)
    }

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var events: Events? = null

    @Volatile
    private var currentSurface: Surface? = null

    @Volatile
    var playbackState: PlaybackState = PlaybackState.IDLE
        private set

    @Volatile
    var playWhenReady: Boolean = false
        private set

    @Volatile
    var currentUrl: String? = null
        private set

    @Volatile
    var audioGain: Float = DEFAULT_AUDIO_GAIN
        private set

    fun setAudioGain(value: Float) {
        val clamped = clampAudioGain(value)
        if (audioGain == clamped) return
        audioGain = clamped
        AppLogStore.i(TAG, "audioGain -> $clamped")
        player?.setVolume(clamped)
    }

    /** 保留原签名以兼容 MainActivity 的 surface 回调；ExoPlayer 构造发生在 play()。 */
    fun getPlayer(context: Context): ExoPlayer? {
        appContext = context.applicationContext
        return player
    }

    fun setEvents(value: Events?) {
        events = value
    }

    fun play(context: Context, url: String, forceHls: Boolean = false) {
        appContext = context.applicationContext
        currentUrl = url
        playWhenReady = true
        AppLogStore.i(TAG, "play url=$url forceHls=$forceHls audioGain=$audioGain")
        setState(PlaybackState.BUFFERING)

        // 换台：先释放旧 player，保证旧分片连接立刻断开，首帧更快、避免连接堆积。
        releaseInternal(resetState = false)

        try {
            val ctx = appContext ?: context.applicationContext
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    (BUFFER_FORWARD_MS + BUFFER_BACK_MS).toInt(),
                    (BUFFER_FORWARD_MS + BUFFER_BACK_MS).toInt(),
                    1_500,
                    3_000
                )
                .build()

            val p = ExoPlayer.Builder(ctx)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .build()

            p.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            p.setVolume(audioGain)
            p.addListener(listener)
            currentSurface?.let { p.setVideoSurface(it) }

            val item = MediaItem.fromUri(Uri.parse(url))
            val source = HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
            p.setMediaSource(source)
            p.playWhenReady = true
            p.prepare()

            synchronized(this) { player = p }
        } catch (t: Throwable) {
            AppLogStore.e(TAG, "play() failed for $url", t)
            events?.onError(0, 0)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> setState(PlaybackState.BUFFERING)
                Player.STATE_READY -> setState(PlaybackState.READY)
                Player.STATE_ENDED -> setState(PlaybackState.ENDED)
                Player.STATE_IDLE -> {}
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            events?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLogStore.w(TAG, "onPlayerError: ${error.message}")
            setState(PlaybackState.IDLE)
            events?.onError(error.errorCode, 0)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            val w = videoSize.width
            val h = videoSize.height
            if (w <= 0 || h <= 0) return
            // ExoPlayer 不直接给 SAR num/den 的整数形式，用 width/height 比例近似传给 UI
            val sar = w * 1.0f / h
            events?.onVideoSizeChanged(w, h, sar.toInt(), 1)
        }
    }

    fun pause() {
        playWhenReady = false
        player?.playWhenReady = false
    }

    fun release() {
        releaseInternal(resetState = true)
    }

    private fun releaseInternal(resetState: Boolean) {
        synchronized(this) {
            player?.let { p ->
                p.removeListener(listener)
                p.release()
            }
            player = null
        }
        currentUrl = null
        playWhenReady = false
        if (resetState) setState(PlaybackState.IDLE)
    }

    fun setSurface(surface: Surface?) {
        currentSurface = surface
        player?.setVideoSurface(surface)
    }

    fun currentPositionMs(): Long = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)

    /** ExoPlayer 的已缓冲时长(ms)。 */
    fun bufferedPositionMs(): Long = runCatching {
        val p = player ?: return@runCatching 0L
        (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
    }.getOrDefault(0L)

    fun isPlaying(): Boolean = runCatching { player?.isPlaying ?: false }.getOrDefault(false)

    private fun setState(next: PlaybackState) {
        if (playbackState == next) return
        playbackState = next
        events?.onStateChanged(next)
    }
}
