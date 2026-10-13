package com.tivimatelite.player

import android.content.Context
import android.view.Surface
import com.tivimatelite.web.AppLogStore
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * IJKPlayer 内核封装（替代原 ExoPlayer/Media3）。
 *
 * 播放参数逐一照抄 DIYP `IjkVideoView.a(I)` 的 setOption 调用
 * （category: 1=FORMAT, 2=CODEC, 3=SWS, 4=PLAYER）。
 */
object PlayerManager {
    private const val TAG = "PlayerManager"

    // ---- 照抄 DIYP 的 setOption 参数（见 IjkVideoView.smali L676-791）----
    private const val USER_AGENT = "SYTV/1.6"
    private const val OPT_PROBESIZE = "1024000"
    private const val OPT_ANALYZEDURATION = "2000000"
    private const val OPT_CACHE_BUFFER_DURATION_MS = 20_000L   // 0x4e20
    private const val OPT_SKIP_LOOP_FILTER = 48L               // 0x30, CODEC 类别
    private const val OPT_FRAMEDROP = 5L
    private const val OPT_OVERLAY_FORMAT_RV32 = 842225234L     // SDL_FCC_RV32

    enum class PlaybackState { IDLE, BUFFERING, READY, ENDED }

    /** 播放事件回调（替代 ExoPlayer 的 Player.Listener）。 */
    interface Events {
        fun onError(errorWhat: Int, errorExtra: Int)
        fun onStateChanged(state: PlaybackState)
        fun onIsPlayingChanged(isPlaying: Boolean)
        fun onVideoSizeChanged(width: Int, height: Int, sarNum: Int, sarDen: Int)
    }

    @Volatile
    private var player: IjkMediaPlayer? = null

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

    fun getPlayer(context: Context): IjkMediaPlayer {
        return player ?: synchronized(this) {
            player ?: createPlayer().also { player = it }
        }
    }

    fun setEvents(value: Events?) {
        events = value
    }

    fun play(context: Context, url: String, forceHls: Boolean = false) {
        val p = getPlayer(context)
        currentUrl = url
        playWhenReady = true
        AppLogStore.i(TAG, "play url=$url forceHls=$forceHls")
        setState(PlaybackState.BUFFERING)
        runCatching {
            p.reset()
            applyOptions(p)
            currentSurface?.let { p.setSurface(it) }
            p.setDataSource(url)
            p.prepareAsync()
        }.onFailure {
            AppLogStore.e(TAG, "play() failed for $url", it)
            events?.onError(IMediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    fun pause() {
        playWhenReady = false
        player?.pause()
    }

    fun release() {
        synchronized(this) {
            player?.release()
            player = null
        }
        currentUrl = null
        playWhenReady = false
        setState(PlaybackState.IDLE)
    }

    fun setSurface(surface: Surface?) {
        currentSurface = surface
        player?.setSurface(surface)
    }

    fun currentPositionMs(): Long = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)

    /** IJK 的视频缓存时长(ms)，作为 bufferedPosition 的近似。 */
    fun bufferedPositionMs(): Long = runCatching { player?.videoCachedDuration ?: 0L }.getOrDefault(0L)

    fun isPlaying(): Boolean = runCatching { player?.isPlaying ?: false }.getOrDefault(false)

    private fun setState(next: PlaybackState) {
        if (playbackState == next) return
        playbackState = next
        events?.onStateChanged(next)
    }

    private fun createPlayer(): IjkMediaPlayer {
        val p = IjkMediaPlayer()
        runCatching { IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_WARN) }

        p.setOnPreparedListener { mp ->
            AppLogStore.i(TAG, "onPrepared")
            setState(PlaybackState.READY)
            runCatching { mp.start() }
        }
        p.setOnErrorListener { _, what, extra ->
            AppLogStore.w(TAG, "onError what=$what extra=$extra")
            setState(PlaybackState.IDLE)
            events?.onError(what, extra)
            true
        }
        p.setOnCompletionListener {
            AppLogStore.i(TAG, "onCompletion")
            setState(PlaybackState.ENDED)
        }
        p.setOnInfoListener { _, what, extra ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> setState(PlaybackState.BUFFERING)
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> setState(PlaybackState.READY)
            }
            true
        }
        p.setOnVideoSizeChangedListener { _, width, height, sarNum, sarDen ->
            AppLogStore.i(TAG, "videoSize ${width}x$height sar=$sarNum:$sarDen")
            events?.onVideoSizeChanged(width, height, sarNum, sarDen)
        }
        return p
    }

    /** 深浅拷贝自 DIYP：硬解 + 直播低延迟 + 自动重连。 */
    private fun applyOptions(p: IjkMediaPlayer) {
        // PLAYER(4)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec_all_videos", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg2", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-mpeg4", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", OPT_FRAMEDROP)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", OPT_OVERLAY_FORMAT_RV32)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)

        // FORMAT(1)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", USER_AGENT)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", OPT_PROBESIZE)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", OPT_ANALYZEDURATION)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "delay-optimization", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache-buffer-duration", OPT_CACHE_BUFFER_DURATION_MS)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp")

        // CODEC(2)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", OPT_SKIP_LOOP_FILTER)
    }
}
