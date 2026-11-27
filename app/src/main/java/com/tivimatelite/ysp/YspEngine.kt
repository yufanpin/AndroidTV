package com.tivimatelite.ysp

import android.content.Context
import com.gary.ysplive.core.migu.MiguGateway
import com.gary.ysplive.core.spi.Gateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 引擎单例：持有 [MiguGateway]（咪咕内核），向界面与 Web 控制台暴露统一状态。
 * 生命周期由 [YspEngineService] 负责，这里只保证「同一个进程只有一个实例」。
 */
object YspEngine {

    data class State(
        val starting: Boolean = false,
        val ready: Boolean = false,
        val failed: Boolean = false,
        val stage: String = "",
        val error: String = "",
        val port: Int = 0,
        val channels: Int = 0,
        val highBitrateReady: Boolean = false,
        val uptimeMs: Long = 0L,
        val cacheEntries: Int = 0,
        val cacheHitRate: Double = 0.0,
        val guid: String = "",
        val heartbeat: Int = 0
    ) {
        val running: Boolean get() = ready && port > 0
    }

    data class ChannelInfo(
        val slug: String,
        val name: String,
        val group: String,
        val mode: String,
        val defn: String,
        val segments: Int,
        val lastOkSec: Double,
        val error: String
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()

    @Volatile
    private var gateway: Gateway? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var lastStartError = ""

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            YspLogBridge.install()
            val existing = gateway
            if (existing != null && existing.isRunning()) {
                refresh()
                return
            }
            if (existing != null) {
                runCatching { existing.stop() }
                gateway = null
            }
            val config = YspConfig.load(app)
            com.gary.ysplive.core.spi.Log.setMinLevel(config.logLevel)
            val created = MiguGateway(config.toGatewayConfig(app))
            gateway = created
            val gen = ++generation
            lastStartError = ""
            _state.value = State(starting = true, stage = "正在初始化引擎…")
            try {
                created.start(object : Gateway.StartListener {
                    override fun onStage(stage: String) {
                        if (gen != generation) return
                        _state.update {
                            it.copy(starting = !it.ready, failed = false, stage = stage)
                        }
                        YspEvents.record("", YspEvents.STAGE_OUTPUT, stage)
                    }

                    override fun onReady(snapshot: Gateway.Snapshot) {
                        if (gen != generation) return
                        applySnapshot(snapshot, "运行中")
                        YspEvents.record(
                            "", YspEvents.STAGE_OUTPUT,
                            "引擎就绪，端口 ${snapshot.port}，频道 ${snapshot.channelCount}"
                        )
                    }

                    override fun onFailed(reason: String) {
                        if (gen != generation) return
                        lastStartError = reason
                        _state.update {
                            it.copy(starting = false, ready = false, failed = true, error = reason)
                        }
                        YspEvents.record("", YspEvents.STAGE_ERROR, reason, 3)
                    }
                })
            } catch (t: Throwable) {
                lastStartError = t.message ?: t.javaClass.simpleName
                _state.update {
                    it.copy(
                        starting = false, ready = false, failed = true,
                        error = lastStartError
                    )
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            val current = gateway
            gateway = null
            runCatching { current?.stop() }
            _state.value = State()
        }
    }

    fun restart(context: Context) {
        stop()
        start(context)
    }

    /** 刷新 uptime / 缓存 / 心跳等随时间变化的字段。 */
    fun refresh() {
        val current = gateway ?: return
        runCatching { applySnapshot(current.snapshot(), _state.value.stage.ifEmpty { "运行中" }) }
    }

    private fun applySnapshot(snapshot: Gateway.Snapshot?, stage: String) {
        if (snapshot == null) return
        _state.value = State(
            starting = !snapshot.running,
            ready = snapshot.running,
            failed = false,
            stage = stage,
            error = snapshot.lastError.orEmpty(),
            port = snapshot.port,
            channels = snapshot.channelCount,
            highBitrateReady = snapshot.highBitrateReady,
            uptimeMs = snapshot.uptimeMs,
            cacheEntries = snapshot.cacheEntries,
            cacheHitRate = snapshot.cacheHitRate,
            guid = snapshot.cloudGuid.orEmpty(),
            heartbeat = snapshot.heartbeatCount
        )
    }

    fun playlistUrl(): String {
        val port = _state.value.port
        return if (port > 0) "http://127.0.0.1:$port/all.m3u" else ""
    }

    fun lastError(): String = lastStartError

    /** 咪咕内核无后台刷新线程，切台无需停止旧频道（保留空实现以兼容调用方）。 */
    fun stopChannel(slug: String) { }

    fun channels(): List<ChannelInfo> {
        val mg = gateway as? MiguGateway ?: return emptyList()
        return runCatching {
            mg.channels().map { ch ->
                ChannelInfo(
                    slug = ch.slug(),
                    name = ch.name(),
                    group = ch.group,
                    mode = "咪咕",
                    defn = "直播",
                    segments = 0,
                    lastOkSec = 0.0,
                    error = ""
                )
            }
        }.getOrElse { emptyList() }
    }
}
