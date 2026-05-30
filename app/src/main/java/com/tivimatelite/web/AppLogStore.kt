package com.tivimatelite.web

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogStore {
    private const val TAG = "TiviMateLite"
    private const val MAX_LINES = 500
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>(MAX_LINES)

    fun i(tag: String, message: String) = append("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        append("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        append("E", tag, message, throwable)
    }

    fun dump(): String {
        synchronized(lock) {
            return lines.joinToString("\n")
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp $level/$tag: $message"

        when (level) {
            "E" -> Log.e(TAG, "$tag: $message", throwable)
            "W" -> Log.w(TAG, "$tag: $message", throwable)
            else -> Log.i(TAG, "$tag: $message")
        }

        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)

            if (throwable != null) {
                for (traceLine in throwable.stackTraceToString().lineSequence()) {
                    if (lines.size >= MAX_LINES) lines.removeFirst()
                    lines.addLast("$timestamp   $traceLine")
                }
            }
        }
    }
}
