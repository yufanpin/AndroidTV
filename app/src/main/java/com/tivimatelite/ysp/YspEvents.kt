package com.tivimatelite.ysp

import com.gary.ysplive.core.spi.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 直播源处理流程事件。把引擎日志归并到「请求 / 选源 / 上游 / 改写 / 输出 / 播放器」六段，
 * 供 5221 控制台的流程页使用。
 */
object YspEvents {

    const val STAGE_REQUEST = "request"
    const val STAGE_SELECT = "select"
    const val STAGE_UPSTREAM = "upstream"
    const val STAGE_REWRITE = "rewrite"
    const val STAGE_OUTPUT = "output"
    const val STAGE_PLAYER = "player"
    const val STAGE_ERROR = "error"

    private const val MAX_EVENTS = 300
    private val lock = Any()
    private val seq = AtomicLong(0)
    private val ring = ArrayDeque<Event>(MAX_EVENTS)

    data class Event(
        val seq: Long,
        val at: Long,
        val channel: String,
        val stage: String,
        val message: String,
        val level: Int
    )

    fun record(channel: String, stage: String, message: String, level: Int = Log.INFO) {
        val event = Event(
            seq = seq.incrementAndGet(),
            at = System.currentTimeMillis(),
            channel = channel,
            stage = stage,
            message = message.take(240),
            level = level
        )
        synchronized(lock) {
            if (ring.size >= MAX_EVENTS) ring.removeFirst()
            ring.addLast(event)
        }
    }

    /** 把 core 的一条日志翻译成流程事件。 */
    fun recordCore(entry: Log.Entry) {
        val message = entry.message ?: return
        val tag = entry.tag.orEmpty()
        val channel = if (tag.matches(Regex("[a-z0-9]{3,12}"))) tag else ""
        record(channel, classify(message), message, entry.level)
    }

    private fun classify(message: String): String = when {
        message.contains("请求直播源") || message.contains("正在向央视频") -> STAGE_REQUEST
        message.contains("直播源获取成功") || message.contains("高码率") -> STAGE_UPSTREAM
        message.contains("备用流缓冲就绪") || message.contains("后台拉取启动") -> STAGE_REWRITE
        message.contains("换台防抖") || message.contains("自动切换") -> STAGE_SELECT
        message.contains("播放列表") -> STAGE_OUTPUT
        message.contains("失败") || message.contains("异常") || message.contains("错误") -> STAGE_ERROR
        else -> STAGE_OUTPUT
    }

    fun since(afterSeq: Long): List<Event> {
        synchronized(lock) {
            return ring.filter { it.seq > afterSeq }
        }
    }

    fun recent(limit: Int): List<Event> {
        synchronized(lock) {
            return ring.toList().takeLast(limit.coerceIn(1, MAX_EVENTS))
        }
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
    }
}
