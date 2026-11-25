package com.tivimatelite.web

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogStore {
    private const val TAG = "TiviMateLite"
    private const val MAX_LINES = 600
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<Entry>(MAX_LINES)
    private var nextSeq = 0L

    private data class Entry(val seq: Long, val text: String)

    fun i(tag: String, message: String) = append("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        append("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        append("E", tag, message, throwable)
    }

    fun dump(): String {
        synchronized(lock) {
            return lines.joinToString("\n") { it.text }
        }
    }

    /** 单调递增的日志序号（环形缓冲覆盖后仍然只增不减）。 */
    fun totalLines(): Long {
        synchronized(lock) {
            return nextSeq
        }
    }

    /** 返回 seq 大于 afterSeq 的日志，以及新的 total。 */
    fun dumpFrom(afterSeq: Long): Pair<Long, String> {
        synchronized(lock) {
            val text = lines.asSequence()
                .filter { it.seq > afterSeq }
                .joinToString("\n") { it.text }
            return nextSeq to text
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        val timestamp = dateFormat.format(Date())

        when (level) {
            "E" -> Log.e(TAG, "$tag: $message", throwable)
            "W" -> Log.w(TAG, "$tag: $message", throwable)
            else -> Log.i(TAG, "$tag: $message")
        }

        synchronized(lock) {
            addLocked("$timestamp $level/$tag: $message")
            if (throwable != null) {
                for (traceLine in throwable.stackTraceToString().lineSequence()) {
                    addLocked("$timestamp   $traceLine")
                }
            }
        }
    }

    private fun addLocked(text: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(Entry(nextSeq++, text))
    }
}
