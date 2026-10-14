package com.tivimatelite.player

import android.content.Context
import android.view.Surface
import com.tivimatelite.web.AppLogStore
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * IJKPlayer 内核封装（debugly/ijkplayer k0.8.9 预编译 AAR，替代原 ExoPlayer/Media3、
 * 以及在无 Vulkan 设备上无法构造的 FSPlayer）。
 *
 * 选项名均已与本项目所用 `app/libs/ijkplayer-cmake-release.aar` 内 `jni/<abi>/libijkplayer.so`
 * 中的字符串逐一核对；category 编号：1=FORMAT, 2=CODEC, 3=SWS, 4=PLAYER。
 *
 * 与 FSPlayer 不同，此 IJK 构建的安卓渲染后端为 OpenGL ES（无 `libvulkan.so` 依赖），
 * 因此在无 Vulkan 驱动的设备（含 S905L3 机顶盒、MuMu 模拟器）上可正常出画。
 */
object PlayerManager {
    private const val TAG = "PlayerManager"

    // ---- 播放选项（DIYP 对内 JS 移植而来的 IPTV 播放参数）----
    private const val USER_AGENT = "SYTV/1.6"
    private const val OPT_PROBESIZE = "1024000"
    private const val OPT_ANALYZEDURATION = "2000000"
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

    /**
     * 若原生库加载失败（例如 APK 未打包对应 ABI 的 `libijkplayer.so`），构造会抛
     * `UnsatisfiedLinkError`。一旦失败即置位，避免 channelSwitcher 每换一次源就重建
     * 播放器导致重试风暴——ABI 缺失不会在运行期自愈。
     */
    @Volatile
    private var playerCreationFailed = false

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

    fun getPlayer(context: Context): IjkMediaPlayer? {
        player?.let { return it }
        if (playerCreationFailed) return null

        var created: IjkMediaPlayer? = null
        var failure: Throwable? = null
        synchronized(this) {
            created = player
            if (created == null && !playerCreationFailed) {
                try {
                    created = createPlayer().also { player = it }
                } catch (t: Throwable) {
                    // 构造失败通常源于原生库加载失败（UnsatisfiedLinkError）。
                    // 兜住 Error 防止整个 App 崩溃，并置位永久失败标志。
                    playerCreationFailed = true
                    failure = t
                }
            }
        }

        failure?.let {
            AppLogStore.e(TAG, "IjkMediaPlayer 创建失败：原生库加载异常（检查 ABI 是否匹配）", it)
            events?.onError(IMediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
        return created
    }

    fun setEvents(value: Events?) {
        events = value
    }

    fun play(context: Context, url: String, forceHls: Boolean = false) {
        val p = getPlayer(context) ?: run {
            AppLogStore.e(TAG, "play() aborted: IjkMediaPlayer 不可用，url=$url")
            return
        }
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
            // 释放后允许下次 getPlayer 重新尝试构造（例如 Activity 重建 / App 重启）。
            playerCreationFailed = false
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
        AppLogStore.i(TAG, "createPlayer: IjkMediaPlayer")
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

    /**
     * IJK 的 IPTV 播放选项：硬解 + 直播低延迟 + 自动重连。
     * 已剔除本 AAR 的 `libijkplayer.so` 未注册的选项（如 `rtsp_flags`/`prefer_tcp`：
     * 注册名缺失时 `av_opt_set_dict` 会静默忽略，属于无效设置）。
     */
    private fun applyOptions(p: IjkMediaPlayer) {
        // PLAYER(4)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1L)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1L)
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
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)

        // CODEC(2)
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", OPT_SKIP_LOOP_FILTER)
    }
}
